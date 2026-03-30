package homepeer

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"sync"
	"time"

	canonicaljson "github.com/gibson042/canonicaljson-go"
	dht "github.com/libp2p/go-libp2p-kad-dht"
	"github.com/libp2p/go-libp2p/core/crypto"
	"github.com/libp2p/go-libp2p/core/host"
	"github.com/libp2p/go-libp2p/core/peer"
	"github.com/net-perspective/home-peer/internal/model"
	"github.com/net-perspective/home-peer/internal/store"
	"github.com/net-perspective/home-peer/internal/validator"
)

const (
	maxHops       = 6
	cycleInterval = 10 * time.Minute
	dhtTimeout    = 15 * time.Second
)

// ErrNotHomed is returned when an operation targets a user not configured on this home peer.
var ErrNotHomed = errors.New("user not homed")

// homedUser holds in-memory state for a homed user.
// Dependencies is keyed by dot-joined context for fast lookup.
type homedUser struct {
	userID          peer.ID
	directRelations *model.DirectRelations
	dependencies    map[string]*model.DirectRelationsDependencies
}

// HealthInfo holds the peer's current runtime status.
type HealthInfo struct {
	PeerID           string   `json:"peer_id"`
	RoutingTableSize int      `json:"routing_table_size"`
	NumUsers         int      `json:"num_users"`
	Addrs            []string `json:"addrs"`
}

// HomePeer is the core server: manages homed users, computes DRDs, and publishes to the DHT.
type HomePeer struct {
	host    host.Host
	dht     *dht.IpfsDHT
	store   *store.Store
	users   map[string]*homedUser
	mu      sync.RWMutex
	privKey crypto.PrivKey
}

// New creates a HomePeer for the given user IDs, loading any persisted data from the store.
// It starts the background dependency computation cycle.
func New(h host.Host, d *dht.IpfsDHT, s *store.Store, privKey crypto.PrivKey, userIDs []peer.ID) (*HomePeer, error) {
	hp := &HomePeer{
		host:    h,
		dht:     d,
		store:   s,
		users:   make(map[string]*homedUser, len(userIDs)),
		privKey: privKey,
	}

	for _, pid := range userIDs {
		uid := pid.String()
		hu := &homedUser{
			userID:       pid,
			dependencies: make(map[string]*model.DirectRelationsDependencies),
		}
		hu.directRelations, _ = s.GetDR(uid)
		drds, _ := s.GetAllDRDs(uid)
		for _, drd := range drds {
			hu.dependencies[model.ContextDotJoin(drd.Context)] = drd
		}
		hp.users[uid] = hu
	}

	go hp.runCycle(context.Background())
	return hp, nil
}

// Publish stores and publishes a new DirectRelations for a homed user.
// Returns ErrNotHomed if the user was not configured at startup.
func (hp *HomePeer) Publish(dr *model.DirectRelations) error {
	if err := validator.ValidateDR(dr); err != nil {
		return fmt.Errorf("validation: %w", err)
	}

	hp.mu.Lock()
	hu, exists := hp.users[dr.UserID]
	if !exists {
		hp.mu.Unlock()
		return ErrNotHomed
	}
	hu.directRelations = dr
	hp.mu.Unlock()

	if err := hp.store.PutDR(dr.UserID, dr); err != nil {
		return fmt.Errorf("storing DR: %w", err)
	}
	if err := hp.store.PutCache(dr.UserID, dr); err != nil {
		return fmt.Errorf("storing cache: %w", err)
	}

	go hp.publishDR(dr.UserID, dr)
	go hp.computeAndPublishDeps(context.Background(), hu)
	return nil
}

// Feed returns all cached DirectRelations for the full dependency set of a homed user.
func (hp *HomePeer) Feed(userID string) ([]*model.DirectRelations, error) {
	hp.mu.RLock()
	hu, exists := hp.users[userID]
	hp.mu.RUnlock()
	if !exists {
		return nil, ErrNotHomed
	}

	feed := []*model.DirectRelations{}
	if hu.directRelations != nil {
		feed = append(feed, hu.directRelations)
	}

	seen := map[string]bool{userID: true}
	for _, drd := range hu.dependencies {
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
	return feed, nil
}

// Deps returns all computed DRDs for a homed user.
func (hp *HomePeer) Deps(userID string) ([]*model.DirectRelationsDependencies, error) {
	hp.mu.RLock()
	hu, exists := hp.users[userID]
	hp.mu.RUnlock()
	if !exists {
		return nil, ErrNotHomed
	}
	drds := make([]*model.DirectRelationsDependencies, 0, len(hu.dependencies))
	for _, drd := range hu.dependencies {
		drds = append(drds, drd)
	}
	return drds, nil
}

// CachedDR returns a cached DirectRelations by peer ID (any user, not just homed).
func (hp *HomePeer) CachedDR(userID string) (*model.DirectRelations, error) {
	return hp.store.GetCache(userID)
}

// Health returns the peer's current runtime status.
func (hp *HomePeer) Health() HealthInfo {
	hp.mu.RLock()
	numUsers := len(hp.users)
	hp.mu.RUnlock()

	rt := hp.dht.RoutingTable()
	addrs := make([]string, len(hp.host.Addrs()))
	for i, a := range hp.host.Addrs() {
		addrs[i] = a.String()
	}
	return HealthInfo{
		PeerID:           hp.host.ID().String(),
		RoutingTableSize: rt.Size(),
		NumUsers:         numUsers,
		Addrs:            addrs,
	}
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
func (hp *HomePeer) computeAndPublishDeps(ctx context.Context, hu *homedUser) {
	hp.mu.RLock()
	dr := hu.directRelations
	hp.mu.RUnlock()

	if dr == nil {
		return
	}

	userIDStr := hu.userID.String()
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
		hu.dependencies[ctxKey] = drd
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
			users := make([]*homedUser, 0, len(hp.users))
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
