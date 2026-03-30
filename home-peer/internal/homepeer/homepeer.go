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

	canonicaljson "github.com/gibson042/canonicaljson-go"
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
// Dependencies is keyed by dot-joined context (e.g. "work.engineering") for fast lookup.
type HomedUser struct {
	UserID          peer.ID
	DirectRelations *model.DirectRelations
	Dependencies    map[string]*model.DirectRelationsDependencies
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
			Dependencies: make(map[string]*model.DirectRelationsDependencies),
		}
		hu.DirectRelations, _ = s.GetDR(uid)
		drds, _ := s.GetAllDRDs(uid)
		for _, drd := range drds {
			hu.Dependencies[model.ContextDotJoin(drd.Context)] = drd
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

// validateDR checks the signature on a DirectRelations document.
func validateDR(dr *model.DirectRelations) error {
	pid, err := peer.Decode(dr.UserID)
	if err != nil {
		return fmt.Errorf("invalid user_id: %w", err)
	}
	pubKey, err := pid.ExtractPublicKey()
	if err != nil {
		return fmt.Errorf("extracting public key: %w", err)
	}
	sigBytes, err := base64.StdEncoding.DecodeString(dr.Signature)
	if err != nil {
		return fmt.Errorf("decoding signature: %w", err)
	}
	payload := map[string]any{
		"version":   dr.Version,
		"user_id":   dr.UserID,
		"timestamp": dr.Timestamp,
		"contexts":  dr.Contexts,
	}
	canonical, err := canonicaljson.Marshal(payload)
	if err != nil {
		return fmt.Errorf("canonical JSON: %w", err)
	}
	ok, err := pubKey.Verify(canonical, sigBytes)
	if err != nil {
		return fmt.Errorf("verify: %w", err)
	}
	if !ok {
		return fmt.Errorf("signature invalid")
	}
	return nil
}

// signDRD signs a DirectRelationsDependencies document with the home peer's key.
func (hp *HomePeer) signDRD(drd *model.DirectRelationsDependencies) error {
	payload := map[string]any{
		"version":          drd.Version,
		"user_id":          drd.UserID,
		"context":          drd.Context,
		"hops":             drd.Hops,
		"sources":          drd.Sources,
		"source_timestamp": drd.SourceTimestamp,
		"computed_at":      drd.ComputedAt,
		"peer_id":          drd.PeerID,
	}
	canonical, err := canonicaljson.Marshal(payload)
	if err != nil {
		return fmt.Errorf("canonical JSON: %w", err)
	}
	sig, err := hp.privKey.Sign(canonical)
	if err != nil {
		return fmt.Errorf("signing: %w", err)
	}
	drd.Signature = base64.StdEncoding.EncodeToString(sig)
	return nil
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
	var dr model.DirectRelations
	if err := json.NewDecoder(r.Body).Decode(&dr); err != nil {
		writeError(w, http.StatusBadRequest, "invalid JSON: "+err.Error())
		return
	}
	if err := validateDR(&dr); err != nil {
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
		UserID:          pid,
		DirectRelations: &dr,
		Dependencies:    make(map[string]*model.DirectRelationsDependencies),
	}
	hp.users[dr.UserID] = hu

	if err := hp.store.PutDR(dr.UserID, &dr); err != nil {
		writeError(w, http.StatusInternalServerError, "storing DR: "+err.Error())
		return
	}
	if err := hp.store.PutCache(dr.UserID, &dr); err != nil {
		writeError(w, http.StatusInternalServerError, "storing cache: "+err.Error())
		return
	}
	if err := hp.store.AddUser(dr.UserID); err != nil {
		writeError(w, http.StatusInternalServerError, "storing user list: "+err.Error())
		return
	}

	go hp.publishDR(dr.UserID, &dr)
	go hp.computeAndPublishDeps(context.Background(), hu)

	writeJSON(w, http.StatusCreated, map[string]string{"status": "registered", "user_id": dr.UserID})
}

func (hp *HomePeer) handlePublish(w http.ResponseWriter, r *http.Request) {
	userID := r.PathValue("userid")

	var dr model.DirectRelations
	if err := json.NewDecoder(r.Body).Decode(&dr); err != nil {
		writeError(w, http.StatusBadRequest, "invalid JSON: "+err.Error())
		return
	}
	if dr.UserID != userID {
		writeError(w, http.StatusBadRequest, "user_id mismatch")
		return
	}
	if err := validateDR(&dr); err != nil {
		writeError(w, http.StatusBadRequest, "signature validation failed: "+err.Error())
		return
	}

	hp.mu.Lock()
	hu, exists := hp.users[userID]
	if !exists {
		hp.mu.Unlock()
		writeError(w, http.StatusNotFound, "user not registered")
		return
	}
	hu.DirectRelations = &dr
	hp.mu.Unlock()

	if err := hp.store.PutDR(userID, &dr); err != nil {
		writeError(w, http.StatusInternalServerError, "storing DR: "+err.Error())
		return
	}
	if err := hp.store.PutCache(userID, &dr); err != nil {
		writeError(w, http.StatusInternalServerError, "storing cache: "+err.Error())
		return
	}

	go hp.publishDR(userID, &dr)
	go hp.computeAndPublishDeps(context.Background(), hu)

	writeJSON(w, http.StatusOK, map[string]string{"status": "published", "user_id": userID})
}

// handleFeed returns all cached direct-relations for a user's full dependency set.
func (hp *HomePeer) handleFeed(w http.ResponseWriter, r *http.Request) {
	userID := r.PathValue("userid")

	hp.mu.RLock()
	hu, exists := hp.users[userID]
	hp.mu.RUnlock()

	if !exists {
		writeError(w, http.StatusNotFound, "user not registered")
		return
	}

	feed := []*model.DirectRelations{}
	if hu.DirectRelations != nil {
		feed = append(feed, hu.DirectRelations)
	}

	seen := map[string]bool{userID: true}
	for _, drd := range hu.Dependencies {
		for _, uids := range drd.Hops {
			for _, uid := range uids {
				if seen[uid] {
					continue
				}
				seen[uid] = true
				dr, _ := hp.store.GetCache(uid)
				if dr != nil {
					feed = append(feed, dr)
				}
			}
		}
	}

	writeJSON(w, http.StatusOK, feed)
}

func (hp *HomePeer) handleDeps(w http.ResponseWriter, r *http.Request) {
	userID := r.PathValue("userid")

	hp.mu.RLock()
	hu, exists := hp.users[userID]
	hp.mu.RUnlock()

	if !exists {
		writeError(w, http.StatusNotFound, "user not registered")
		return
	}
	drds := make([]*model.DirectRelationsDependencies, 0, len(hu.Dependencies))
	for _, drd := range hu.Dependencies {
		drds = append(drds, drd)
	}
	writeJSON(w, http.StatusOK, drds)
}

func (hp *HomePeer) handleGetDR(w http.ResponseWriter, r *http.Request) {
	userID := r.PathValue("userid")
	dr, err := hp.store.GetCache(userID)
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	if dr == nil {
		writeError(w, http.StatusNotFound, "not found")
		return
	}
	writeJSON(w, http.StatusOK, dr)
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

func (hp *HomePeer) publishDR(userID string, dr *model.DirectRelations) {
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	data, err := json.Marshal(dr)
	if err != nil {
		return
	}
	_ = hp.dht.PutValue(ctx, "/dr/"+userID, data)
}

func (hp *HomePeer) publishDRD(userID string, contextPath []string, drd *model.DirectRelationsDependencies) {
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	data, err := json.Marshal(drd)
	if err != nil {
		return
	}
	key := "/drd/" + userID + "/" + model.ContextDotJoin(contextPath)
	_ = hp.dht.PutValue(ctx, key, data)
}

// fetchRemoteDRD retrieves a per-context DRD from the DHT. Returns nil if unavailable.
func (hp *HomePeer) fetchRemoteDRD(ctx context.Context, userID string, contextPath []string) *model.DirectRelationsDependencies {
	key := "/drd/" + userID + "/" + model.ContextDotJoin(contextPath)
	val, err := hp.dht.GetValue(ctx, key)
	if err != nil {
		return nil
	}
	var drd model.DirectRelationsDependencies
	if err := json.Unmarshal(val, &drd); err != nil {
		return nil
	}
	return &drd
}

// fetchAndCacheDR fetches a DirectRelations from the DHT and stores it in the local cache.
func (hp *HomePeer) fetchAndCacheDR(ctx context.Context, userID string) {
	val, err := hp.dht.GetValue(ctx, "/dr/"+userID)
	if err != nil {
		return
	}
	var dr model.DirectRelations
	if err := json.Unmarshal(val, &dr); err != nil {
		return
	}
	_ = hp.store.PutCache(userID, &dr)
}

// computeAndPublishDeps performs the purely inductive DRD computation for a homed user.
// One DRD is produced and published per context in the user's DirectRelations.
// Hop 1 is read from the user's signed DR; hops 2–6 are composed exclusively from
// the signed DRDs of hop-1 users fetched at /drd/<user-id>/<context>.
// There is no fallback to direct DR traversal.
func (hp *HomePeer) computeAndPublishDeps(ctx context.Context, hu *HomedUser) {
	hp.mu.RLock()
	dr := hu.DirectRelations
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

		// Hops 2–6: fetch the DRD for each hop-1 target at
		// /drd/<user-id>/<dot-joined-target-context> and shift hop indices by 1.
		sources := []string{}
		seenSources := map[string]bool{}

		for _, t := range hop1 {
			fetchCtx, cancel := context.WithTimeout(ctx, dhtTimeout)
			remoteDRD := hp.fetchRemoteDRD(fetchCtx, t.userID, t.targetContext)
			cancel()

			if remoteDRD == nil {
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

		drd := &model.DirectRelationsDependencies{
			Version:         1,
			UserID:          userIDStr,
			Context:         ctxEntry.Path,
			Hops:            hops,
			Sources:         sources,
			SourceTimestamp: dr.Timestamp,
			ComputedAt:      now,
			PeerID:          hp.host.ID().String(),
		}

		if err := hp.signDRD(drd); err != nil {
			log.Printf("signing DRD for %s context %v: %v", userIDStr, ctxEntry.Path, err)
			continue
		}

		ctxKey := model.ContextDotJoin(ctxEntry.Path)

		hp.mu.Lock()
		hu.Dependencies[ctxKey] = drd
		hp.mu.Unlock()

		if err := hp.store.PutDRD(userIDStr, ctxEntry.Path, drd); err != nil {
			log.Printf("storing DRD for %s context %v: %v", userIDStr, ctxEntry.Path, err)
		}

		go hp.publishDRD(userIDStr, ctxEntry.Path, drd)
	}

	// Cache the DR for every user in the full dependency set.
	for uid := range allDepUIDs {
		existing, _ := hp.store.GetCache(uid)
		if existing == nil {
			fetchCtx, cancel := context.WithTimeout(ctx, dhtTimeout)
			hp.fetchAndCacheDR(fetchCtx, uid)
			cancel()
		}
	}
}

// runCycle runs the dependency computation cycle every 10 minutes.
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
