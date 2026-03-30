package homepeer

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"sync"
	"time"

	dht "github.com/libp2p/go-libp2p-kad-dht"
	"github.com/libp2p/go-libp2p/core/crypto"
	"github.com/libp2p/go-libp2p/core/host"
	"github.com/libp2p/go-libp2p/core/peer"
	"github.com/net-perspective/home-peer/internal/model"
	"github.com/net-perspective/home-peer/internal/store"
)

const (
	maxHops       = 6
	cycleInterval = 10 * time.Minute
	dhtTimeout    = 15 * time.Second
)

// HomedUser holds in-memory state for a registered user.
// DR and DRPayload are kept in sync: DR is the signed envelope for serving,
// DRPayload is the parsed inner document for computation.
type HomedUser struct {
	UserID       peer.ID
	DR           *model.Envelope                // signed DR envelope
	DRPayload    *model.DirectRelationsPayload  // parsed from DR.Payload
	Dependencies map[string]*model.Envelope     // dot-joined context → signed DRD envelope
}

// HomePeer is the main server struct.
type HomePeer struct {
	host    host.Host
	dht     *dht.IpfsDHT
	store   *store.Store
	users   map[string]*HomedUser
	mu      sync.RWMutex
	privKey crypto.PrivKey
}

// New creates a new HomePeer and starts the background computation cycle.
func New(h host.Host, d *dht.IpfsDHT, s *store.Store, privKey crypto.PrivKey) (*HomePeer, error) {
	hp := &HomePeer{
		host:    h,
		dht:     d,
		store:   s,
		users:   make(map[string]*HomedUser),
		privKey: privKey,
	}

	userIDs, err := s.GetUsers()
	if err != nil {
		return nil, fmt.Errorf("loading users: %w", err)
	}
	for _, uid := range userIDs {
		pid, err := peer.Decode(uid)
		if err != nil {
			continue
		}
		hu := &HomedUser{
			UserID:       pid,
			Dependencies: make(map[string]*model.Envelope),
		}
		if drEnv, _ := s.GetDR(uid); drEnv != nil {
			if payload, err := drEnv.ParseDR(); err == nil {
				hu.DR = drEnv
				hu.DRPayload = payload
			}
		}
		if envs, _ := s.GetAllDRDs(uid); envs != nil {
			for i := range envs {
				if drdPayload, err := envs[i].ParseDRD(); err == nil {
					hu.Dependencies[model.ContextDotJoin(drdPayload.Context)] = &envs[i]
				}
			}
		}
		hp.users[uid] = hu
	}

	go hp.runCycle(context.Background())
	return hp, nil
}

// Handler returns an http.Handler with all routes registered.
func (hp *HomePeer) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("POST /users/register", hp.handleRegister)
	mux.HandleFunc("POST /users/{userid}/publish", hp.handlePublish)
	mux.HandleFunc("GET /users/{userid}/feed", hp.handleFeed)
	mux.HandleFunc("GET /users/{userid}/deps", hp.handleDeps)
	mux.HandleFunc("GET /direct-relations/{userid}", hp.handleGetDR)
	mux.HandleFunc("GET /health", hp.handleHealth)
	return mux
}

// validateDREnvelope verifies the signature on a DR envelope and returns the parsed payload.
func validateDREnvelope(env *model.Envelope) (*model.DirectRelationsPayload, error) {
	dr, err := env.ParseDR()
	if err != nil {
		return nil, fmt.Errorf("parsing payload: %w", err)
	}
	pid, err := peer.Decode(dr.UserID)
	if err != nil {
		return nil, fmt.Errorf("invalid user_id: %w", err)
	}
	pubKey, err := pid.ExtractPublicKey()
	if err != nil {
		return nil, fmt.Errorf("extracting public key: %w", err)
	}
	sigBytes, err := base64.StdEncoding.DecodeString(env.Signature)
	if err != nil {
		return nil, fmt.Errorf("decoding signature: %w", err)
	}
	ok, err := pubKey.Verify([]byte(env.Payload), sigBytes)
	if err != nil {
		return nil, fmt.Errorf("verify: %w", err)
	}
	if !ok {
		return nil, fmt.Errorf("signature invalid")
	}
	return dr, nil
}

// makeDRDEnvelope marshals a DRD payload, signs the raw bytes, and returns the envelope.
func (hp *HomePeer) makeDRDEnvelope(payload *model.DirectRelationsDependenciesPayload) (model.Envelope, error) {
	data, err := json.Marshal(payload)
	if err != nil {
		return model.Envelope{}, fmt.Errorf("marshaling DRD payload: %w", err)
	}
	sig, err := hp.privKey.Sign(data)
	if err != nil {
		return model.Envelope{}, fmt.Errorf("signing: %w", err)
	}
	return model.Envelope{
		Payload:   string(data),
		Signature: base64.StdEncoding.EncodeToString(sig),
	}, nil
}

func writeJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(v)
}

func writeError(w http.ResponseWriter, code int, msg string) {
	writeJSON(w, code, map[string]string{"error": msg})
}

func (hp *HomePeer) handleRegister(w http.ResponseWriter, r *http.Request) {
	var env model.Envelope
	if err := json.NewDecoder(r.Body).Decode(&env); err != nil {
		writeError(w, http.StatusBadRequest, "invalid JSON: "+err.Error())
		return
	}
	dr, err := validateDREnvelope(&env)
	if err != nil {
		writeError(w, http.StatusBadRequest, "signature validation failed: "+err.Error())
		return
	}

	pid, _ := peer.Decode(dr.UserID)

	hp.mu.Lock()
	defer hp.mu.Unlock()

	if _, exists := hp.users[dr.UserID]; exists {
		writeError(w, http.StatusConflict, "user already registered")
		return
	}

	hu := &HomedUser{
		UserID:       pid,
		DR:           &env,
		DRPayload:    dr,
		Dependencies: make(map[string]*model.Envelope),
	}
	hp.users[dr.UserID] = hu

	if err := hp.store.PutDR(dr.UserID, env); err != nil {
		writeError(w, http.StatusInternalServerError, "storing DR: "+err.Error())
		return
	}
	if err := hp.store.PutCache(dr.UserID, env); err != nil {
		writeError(w, http.StatusInternalServerError, "storing cache: "+err.Error())
		return
	}

	go hp.publishDR(dr.UserID, env)
	go hp.computeAndPublishDeps(context.Background(), hu)

	writeJSON(w, http.StatusCreated, map[string]string{"status": "registered", "user_id": dr.UserID})
}

func (hp *HomePeer) handlePublish(w http.ResponseWriter, r *http.Request) {
	userID := r.PathValue("userid")

	var env model.Envelope
	if err := json.NewDecoder(r.Body).Decode(&env); err != nil {
		writeError(w, http.StatusBadRequest, "invalid JSON: "+err.Error())
		return
	}
	dr, err := validateDREnvelope(&env)
	if err != nil {
		writeError(w, http.StatusBadRequest, "signature validation failed: "+err.Error())
		return
	}
	if dr.UserID != userID {
		writeError(w, http.StatusBadRequest, "user_id mismatch")
		return
	}

	hp.mu.Lock()
	hu, exists := hp.users[userID]
	if !exists {
		hp.mu.Unlock()
		writeError(w, http.StatusNotFound, "user not registered")
		return
	}
	hu.DR = &env
	hu.DRPayload = dr
	hp.mu.Unlock()

	if err := hp.store.PutDR(userID, env); err != nil {
		writeError(w, http.StatusInternalServerError, "storing DR: "+err.Error())
		return
	}
	if err := hp.store.PutCache(userID, env); err != nil {
		writeError(w, http.StatusInternalServerError, "storing cache: "+err.Error())
		return
	}

	go hp.publishDR(userID, env)
	go hp.computeAndPublishDeps(context.Background(), hu)

	writeJSON(w, http.StatusOK, map[string]string{"status": "published", "user_id": userID})
}

// handleFeed returns signed DR envelopes for the user and their full dependency set.
func (hp *HomePeer) handleFeed(w http.ResponseWriter, r *http.Request) {
	userID := r.PathValue("userid")

	hp.mu.RLock()
	hu, exists := hp.users[userID]
	hp.mu.RUnlock()

	if !exists {
		writeError(w, http.StatusNotFound, "user not registered")
		return
	}

	var feed []model.Envelope
	if hu.DR != nil {
		feed = append(feed, *hu.DR)
	}

	seen := map[string]bool{userID: true}
	for _, drdEnv := range hu.Dependencies {
		drd, err := drdEnv.ParseDRD()
		if err != nil {
			continue
		}
		for _, uids := range drd.Hops {
			for _, uid := range uids {
				if seen[uid] {
					continue
				}
				seen[uid] = true
				env, _ := hp.store.GetCache(uid)
				if env != nil {
					feed = append(feed, *env)
				}
			}
		}
	}

	writeJSON(w, http.StatusOK, feed)
}

// handleDeps returns all signed DRD envelopes for a user (one per context).
func (hp *HomePeer) handleDeps(w http.ResponseWriter, r *http.Request) {
	userID := r.PathValue("userid")

	hp.mu.RLock()
	hu, exists := hp.users[userID]
	hp.mu.RUnlock()

	if !exists {
		writeError(w, http.StatusNotFound, "user not registered")
		return
	}

	drds := make([]model.Envelope, 0, len(hu.Dependencies))
	for _, env := range hu.Dependencies {
		drds = append(drds, *env)
	}
	writeJSON(w, http.StatusOK, drds)
}

// handleGetDR returns the cached signed DR envelope for any user.
func (hp *HomePeer) handleGetDR(w http.ResponseWriter, r *http.Request) {
	userID := r.PathValue("userid")
	env, err := hp.store.GetCache(userID)
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	if env == nil {
		writeError(w, http.StatusNotFound, "not found")
		return
	}
	writeJSON(w, http.StatusOK, env)
}

func (hp *HomePeer) handleHealth(w http.ResponseWriter, r *http.Request) {
	hp.mu.RLock()
	numUsers := len(hp.users)
	hp.mu.RUnlock()

	rt := hp.dht.RoutingTable()
	writeJSON(w, http.StatusOK, map[string]any{
		"peer_id":            hp.host.ID().String(),
		"routing_table_size": rt.Size(),
		"num_users":          numUsers,
		"addrs":              hp.host.Addrs(),
	})
}

func (hp *HomePeer) publishDR(userID string, env model.Envelope) {
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	data, _ := json.Marshal(env)
	_ = hp.dht.PutValue(ctx, "/dr/"+userID, data)
}

func (hp *HomePeer) publishDRD(userID string, contextPath []string, env model.Envelope) {
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	data, _ := json.Marshal(env)
	_ = hp.dht.PutValue(ctx, "/drd/"+userID+"/"+model.ContextDotJoin(contextPath), data)
}

// fetchRemoteDRD fetches a per-context DRD envelope from the DHT. Returns nil if unavailable.
func (hp *HomePeer) fetchRemoteDRD(ctx context.Context, userID string, contextPath []string) *model.Envelope {
	key := "/drd/" + userID + "/" + model.ContextDotJoin(contextPath)
	val, err := hp.dht.GetValue(ctx, key)
	if err != nil {
		return nil
	}
	var env model.Envelope
	if err := json.Unmarshal(val, &env); err != nil {
		return nil
	}
	return &env
}

// fetchAndCacheDR fetches a DR envelope from the DHT and stores it in the local cache.
func (hp *HomePeer) fetchAndCacheDR(ctx context.Context, userID string) {
	val, err := hp.dht.GetValue(ctx, "/dr/"+userID)
	if err != nil {
		return
	}
	var env model.Envelope
	if err := json.Unmarshal(val, &env); err != nil {
		return
	}
	_ = hp.store.PutCache(userID, env)
}

// computeAndPublishDeps performs the purely inductive DRD computation for a homed user.
// One DRD envelope is produced and published per context in the user's DR.
func (hp *HomePeer) computeAndPublishDeps(ctx context.Context, hu *HomedUser) {
	hp.mu.RLock()
	dr := hu.DRPayload
	hp.mu.RUnlock()

	if dr == nil {
		return
	}

	userIDStr := hu.UserID.String()
	now := time.Now().Unix()
	allDepUIDs := map[string]bool{}

	for _, ctxEntry := range dr.Contexts {
		type hop1Target struct {
			userID        string
			targetContext []string
		}

		// Hop 1: direct link targets from the user's own signed DR.
		var hop1 []hop1Target
		hop1Seen := map[string]bool{}
		for _, rel := range ctxEntry.Relations {
			if rel.Type != "link" || rel.TargetUser == "" {
				continue
			}
			if !hop1Seen[rel.TargetUser] {
				hop1Seen[rel.TargetUser] = true
				hop1 = append(hop1, hop1Target{
					userID:        rel.TargetUser,
					targetContext: rel.TargetContext,
				})
			}
		}

		hops := make(model.HopDependencies)
		hop1UIDs := make([]string, 0, len(hop1))
		for _, t := range hop1 {
			hop1UIDs = append(hop1UIDs, t.userID)
			allDepUIDs[t.userID] = true
		}
		hops["1"] = hop1UIDs
		for i := 2; i <= maxHops; i++ {
			hops[fmt.Sprintf("%d", i)] = []string{}
		}

		visited := make(map[string]bool, len(hop1UIDs))
		for _, uid := range hop1UIDs {
			visited[uid] = true
		}

		// Hops 2–6: fetch the DRD for each hop-1 target and shift hop indices by 1.
		sources := []string{}
		seenSources := map[string]bool{}

		for _, t := range hop1 {
			fetchCtx, cancel := context.WithTimeout(ctx, dhtTimeout)
			remoteDRDEnv := hp.fetchRemoteDRD(fetchCtx, t.userID, t.targetContext)
			cancel()

			if remoteDRDEnv == nil {
				continue
			}
			remoteDRD, err := remoteDRDEnv.ParseDRD()
			if err != nil {
				continue
			}

			drdKey := "/drd/" + t.userID + "/" + model.ContextDotJoin(t.targetContext)
			if !seenSources[drdKey] {
				seenSources[drdKey] = true
				sources = append(sources, drdKey)
			}

			for k := 1; k < maxHops; k++ {
				theirHop := remoteDRD.Hops[fmt.Sprintf("%d", k)]
				ourHop := fmt.Sprintf("%d", k+1)
				for _, uid := range theirHop {
					if !visited[uid] {
						visited[uid] = true
						hops[ourHop] = append(hops[ourHop], uid)
						allDepUIDs[uid] = true
					}
				}
			}
		}

		payload := &model.DirectRelationsDependenciesPayload{
			Version:         1,
			UserID:          userIDStr,
			Context:         ctxEntry.Path,
			Hops:            hops,
			Sources:         sources,
			SourceTimestamp: dr.Timestamp,
			ComputedAt:      now,
			PeerID:          hp.host.ID().String(),
		}

		env, err := hp.makeDRDEnvelope(payload)
		if err != nil {
			log.Printf("signing DRD for %s context %v: %v", userIDStr, ctxEntry.Path, err)
			continue
		}

		ctxKey := model.ContextDotJoin(ctxEntry.Path)

		hp.mu.Lock()
		hu.Dependencies[ctxKey] = &env
		hp.mu.Unlock()

		if err := hp.store.PutDRD(userIDStr, ctxEntry.Path, env); err != nil {
			log.Printf("storing DRD for %s context %v: %v", userIDStr, ctxEntry.Path, err)
		}

		go hp.publishDRD(userIDStr, ctxEntry.Path, env)
	}

	// Cache the DR envelope for every user in the dependency set.
	for uid := range allDepUIDs {
		existing, _ := hp.store.GetCache(uid)
		if existing == nil {
			fetchCtx, cancel := context.WithTimeout(ctx, dhtTimeout)
			hp.fetchAndCacheDR(fetchCtx, uid)
			cancel()
		}
	}
}

// runCycle recomputes deps for all homed users every 10 minutes.
func (hp *HomePeer) runCycle(ctx context.Context) {
	ticker := time.NewTicker(cycleInterval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			hp.mu.RLock()
			users := make([]*HomedUser, 0, len(hp.users))
			for _, hu := range hp.users {
				users = append(users, hu)
			}
			hp.mu.RUnlock()
			for _, hu := range users {
				hp.computeAndPublishDeps(ctx, hu)
			}
		}
	}
}
