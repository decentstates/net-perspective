package homepeer

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
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

// HomedUser holds in-memory state for a registered user.
type HomedUser struct {
	UserID          peer.ID
	DirectRelations *model.DirectRelations
	Dependencies    *model.DirectRelationsDependencies
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

// New creates a new HomePeer.
func New(h host.Host, d *dht.IpfsDHT, s *store.Store, privKey crypto.PrivKey) (*HomePeer, error) {
	hp := &HomePeer{
		host:    h,
		dht:     d,
		store:   s,
		users:   make(map[string]*HomedUser),
		privKey: privKey,
	}

	// Load existing users from store
	userIDs, err := s.GetUsers()
	if err != nil {
		return nil, fmt.Errorf("loading users: %w", err)
	}
	for _, uid := range userIDs {
		pid, err := peer.Decode(uid)
		if err != nil {
			continue
		}
		hu := &HomedUser{UserID: pid}
		dr, _ := s.GetDR(uid)
		hu.DirectRelations = dr
		drd, _ := s.GetDRD(uid)
		hu.Dependencies = drd
		hp.users[uid] = hu
	}

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

func writeJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(v)
}

func writeError(w http.ResponseWriter, code int, msg string) {
	writeJSON(w, code, map[string]string{"error": msg})
}

// handleRegister registers a new user with an initial signed DirectRelations document.
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

	// Publish to DHT async
	go hp.publishDR(dr.UserID, &dr)

	writeJSON(w, http.StatusCreated, map[string]string{"status": "registered", "user_id": dr.UserID})
}

// handlePublish handles updated DirectRelations submissions.
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
	defer hp.mu.Unlock()

	hu, exists := hp.users[userID]
	if !exists {
		writeError(w, http.StatusNotFound, "user not registered")
		return
	}

	hu.DirectRelations = &dr

	if err := hp.store.PutDR(userID, &dr); err != nil {
		writeError(w, http.StatusInternalServerError, "storing DR: "+err.Error())
		return
	}
	if err := hp.store.PutCache(userID, &dr); err != nil {
		writeError(w, http.StatusInternalServerError, "storing cache: "+err.Error())
		return
	}

	go hp.publishDR(userID, &dr)

	writeJSON(w, http.StatusOK, map[string]string{"status": "published", "user_id": userID})
}

// handleFeed returns all cached direct-relations for a user's dep set.
// Stub: returns just the user's own DR.
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
	writeJSON(w, http.StatusOK, feed)
}

// handleDeps returns the DirectRelationsDependencies document for a user.
func (hp *HomePeer) handleDeps(w http.ResponseWriter, r *http.Request) {
	userID := r.PathValue("userid")

	hp.mu.RLock()
	hu, exists := hp.users[userID]
	hp.mu.RUnlock()

	if !exists {
		writeError(w, http.StatusNotFound, "user not registered")
		return
	}

	if hu.Dependencies == nil {
		writeJSON(w, http.StatusOK, nil)
		return
	}
	writeJSON(w, http.StatusOK, hu.Dependencies)
}

// handleGetDR returns a single cached DirectRelations document.
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

// handleHealth returns peer status information.
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

// publishDR publishes a DirectRelations document to the DHT.
func (hp *HomePeer) publishDR(userID string, dr *model.DirectRelations) {
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	data, err := json.Marshal(dr)
	if err != nil {
		return
	}
	key := "/dr/" + userID
	_ = hp.dht.PutValue(ctx, key, data)
}
