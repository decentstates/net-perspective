package homepeer

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"os"
	"path/filepath"
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
type homedUser struct {
	userID         peer.ID
	drContentAddr  string // SHA-256 hex of their current DR; "" if none published yet
	drd            *model.DirectRelationsDependencies
	drdContentAddr string // SHA-256 hex of their current DRD; "" if none computed yet
}

// HealthInfo holds the peer's current runtime status.
type HealthInfo struct {
	PeerID           string   `json:"peer_id"`
	RoutingTableSize int      `json:"routing_table_size"`
	NumUsers         int      `json:"num_users"`
	Addrs            []string `json:"addrs"`
}

// savedState is the on-disk state snapshot for recovery across restarts.
type savedState struct {
	HomedUsers    map[string]savedUserState `json:"homed_users"`
	CachedDRAddrs map[string]string         `json:"cached_dr_addrs"` // userID → dr content addr
}

type savedUserState struct {
	DRContentAddr  string `json:"dr_content_addr,omitempty"`
	DRDContentAddr string `json:"drd_content_addr,omitempty"`
}

// HomePeer manages homed users, computes DRDs, and publishes to the DHT.
type HomePeer struct {
	host          host.Host
	dht           *dht.IpfsDHT
	store         *store.Store
	users         map[string]*homedUser
	cachedDRAddrs map[string]string // userID → dr content addr for dependency users
	mu            sync.RWMutex
	privKey       crypto.PrivKey
	stateFile     string
}

// New creates a HomePeer for the given user IDs, loading any persisted state from dataDir.
// It starts the background dependency computation cycle.
func New(h host.Host, d *dht.IpfsDHT, s *store.Store, privKey crypto.PrivKey, userIDs []peer.ID, dataDir string) (*HomePeer, error) {
	hp := &HomePeer{
		host:          h,
		dht:           d,
		store:         s,
		users:         make(map[string]*homedUser, len(userIDs)),
		cachedDRAddrs: make(map[string]string),
		privKey:       privKey,
		stateFile:     filepath.Join(dataDir, "state.json"),
	}

	state := hp.loadState()

	for _, pid := range userIDs {
		uid := pid.String()
		hu := &homedUser{userID: pid}
		if us, ok := state.HomedUsers[uid]; ok {
			hu.drContentAddr = us.DRContentAddr
			hu.drdContentAddr = us.DRDContentAddr
			if us.DRDContentAddr != "" {
				if drdData, _ := s.Get(us.DRDContentAddr); drdData != nil {
					var drd model.DirectRelationsDependencies
					if json.Unmarshal(drdData, &drd) == nil {
						hu.drd = &drd
					}
				}
			}
		}
		hp.users[uid] = hu
	}

	for uid, addr := range state.CachedDRAddrs {
		hp.cachedDRAddrs[uid] = addr
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

	data, err := json.Marshal(dr)
	if err != nil {
		return fmt.Errorf("marshalling DR: %w", err)
	}
	addr := model.ContentAddr(data)

	hp.mu.Lock()
	hu, exists := hp.users[dr.UserID]
	if !exists {
		hp.mu.Unlock()
		return ErrNotHomed
	}
	if err := hp.store.Put(addr, data); err != nil {
		hp.mu.Unlock()
		return fmt.Errorf("storing DR: %w", err)
	}
	hu.drContentAddr = addr
	hp.mu.Unlock()

	hp.saveState()

	go hp.publishDRToDHT(dr.UserID, addr, data, dr.Timestamp)
	go hp.publishDRD(context.Background(), hu, dr, addr)
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

	hp.mu.RLock()
	drAddr := hu.drContentAddr
	drd := hu.drd
	hp.mu.RUnlock()

	if drAddr != "" {
		if data, _ := hp.store.Get(drAddr); data != nil {
			var dr model.DirectRelations
			if json.Unmarshal(data, &dr) == nil {
				feed = append(feed, &dr)
			}
		}
	}

	if drd != nil {
		seen := map[string]bool{userID: true}
		for _, uids := range drd.Hops {
			for _, uid := range uids {
				if seen[uid] {
					continue
				}
				seen[uid] = true
				hp.mu.RLock()
				addr := hp.cachedDRAddrs[uid]
				hp.mu.RUnlock()
				if addr == "" {
					continue
				}
				if data, _ := hp.store.Get(addr); data != nil {
					var dr model.DirectRelations
					if json.Unmarshal(data, &dr) == nil {
						feed = append(feed, &dr)
					}
				}
			}
		}
	}

	return feed, nil
}

// Deps returns the current DRD for a homed user, or nil if not yet computed.
func (hp *HomePeer) Deps(userID string) (*model.DirectRelationsDependencies, error) {
	hp.mu.RLock()
	hu, exists := hp.users[userID]
	hp.mu.RUnlock()
	if !exists {
		return nil, ErrNotHomed
	}
	hp.mu.RLock()
	drd := hu.drd
	hp.mu.RUnlock()
	return drd, nil
}

// CachedDR returns a cached DirectRelations by peer ID (any user, not just homed).
func (hp *HomePeer) CachedDR(userID string) (*model.DirectRelations, error) {
	hp.mu.RLock()
	addr := ""
	if hu, ok := hp.users[userID]; ok {
		addr = hu.drContentAddr
	} else {
		addr = hp.cachedDRAddrs[userID]
	}
	hp.mu.RUnlock()

	if addr == "" {
		return nil, nil
	}
	data, err := hp.store.Get(addr)
	if err != nil || data == nil {
		return nil, err
	}
	var dr model.DirectRelations
	if err := json.Unmarshal(data, &dr); err != nil {
		return nil, err
	}
	return &dr, nil
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

// publishDRToDHT performs the two DHT publishing steps for a DirectRelations document.
// Step 2: store the raw bytes under their content address (/dr-data/<addr>).
// Step 3: store the user-id → content-address pointer (/dr/<user-id>).
func (hp *HomePeer) publishDRToDHT(userID, addr string, data []byte, timestamp int64) {
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	// Step 2: DHT entry for content address
	if err := hp.dht.PutValue(ctx, "/dr-data/"+addr, data); err != nil {
		log.Printf("publishing DR content for %s: %v", userID, err)
	}

	// Step 3: DHT pointer user-id → (content-address, timestamp)
	ptr := model.DRPointer{UserID: userID, ContentAddress: addr, Timestamp: timestamp}
	ptrData, _ := json.Marshal(ptr)
	if err := hp.dht.PutValue(ctx, "/dr/"+userID, ptrData); err != nil {
		log.Printf("publishing DR pointer for %s: %v", userID, err)
	}
}

// computeDRD fetches and stores the direct-relations-dependencies for each dependency user,
// then produces and stores the DRD for the given DR.
// Returns the DRD, its serialised bytes, and its content address.
func (hp *HomePeer) computeDRD(ctx context.Context, dr *model.DirectRelations, drAddr string) (*model.DirectRelationsDependencies, []byte, string, error) {
	// Collect unique hop-1 link targets across all contexts.
	hop1Seen := map[string]bool{}
	var hop1Users []string
	for _, ctxEntry := range dr.Contexts {
		for _, rel := range ctxEntry.Relations {
			if rel.Type != "link" || rel.TargetUser == "" {
				continue
			}
			if !hop1Seen[rel.TargetUser] {
				hop1Seen[rel.TargetUser] = true
				hop1Users = append(hop1Users, rel.TargetUser)
			}
		}
	}

	hops := make(model.HopDependencies)
	hops["1"] = hop1Users
	for i := 2; i <= maxHops; i++ {
		hops[fmt.Sprintf("%d", i)] = []string{}
	}

	visited := make(map[string]bool)
	for _, uid := range hop1Users {
		visited[uid] = true
	}

	sources := []string{}
	seenSources := map[string]bool{}

	// Step 1: fetch (and store) the DRD for each hop-1 dependency user.
	for _, targetUID := range hop1Users {
		// Fetch their DRPointer to find their current DR content address.
		fetchCtx, cancel := context.WithTimeout(ctx, dhtTimeout)
		drPtrData, err := hp.dht.GetValue(fetchCtx, "/dr/"+targetUID)
		cancel()
		if err != nil {
			continue
		}
		var drPtr model.DRPointer
		if err := json.Unmarshal(drPtrData, &drPtr); err != nil {
			continue
		}
		theirDRAddr := drPtr.ContentAddress

		// Cache their DR address and fetch their DR content if not already stored.
		hp.mu.Lock()
		hp.cachedDRAddrs[targetUID] = theirDRAddr
		hp.mu.Unlock()

		if existing, _ := hp.store.Get(theirDRAddr); existing == nil {
			fetchCtx2, cancel2 := context.WithTimeout(ctx, dhtTimeout)
			drData, err := hp.dht.GetValue(fetchCtx2, "/dr-data/"+theirDRAddr)
			cancel2()
			if err == nil {
				_ = hp.store.Put(theirDRAddr, drData)
			}
		}

		// Fetch their DRDPointer to find their current DRD content address.
		fetchCtx3, cancel3 := context.WithTimeout(ctx, dhtTimeout)
		drdPtrData, err := hp.dht.GetValue(fetchCtx3, "/drd/"+theirDRAddr)
		cancel3()
		if err != nil {
			continue
		}
		var drdPtr model.DRDPointer
		if err := json.Unmarshal(drdPtrData, &drdPtr); err != nil {
			continue
		}
		drdAddr := drdPtr.DRDContentAddress

		// Fetch and store their DRD content.
		drdData, _ := hp.store.Get(drdAddr)
		if drdData == nil {
			fetchCtx4, cancel4 := context.WithTimeout(ctx, dhtTimeout)
			drdData, err = hp.dht.GetValue(fetchCtx4, "/drd-data/"+drdAddr)
			cancel4()
			if err != nil {
				continue
			}
			_ = hp.store.Put(drdAddr, drdData)
		}

		var remoteDRD model.DirectRelationsDependencies
		if err := json.Unmarshal(drdData, &remoteDRD); err != nil {
			continue
		}

		if !seenSources[drdAddr] {
			seenSources[drdAddr] = true
			sources = append(sources, drdAddr)
		}

		// Inductively merge their hops into ours, shifted by one.
		for k := 1; k < maxHops; k++ {
			for _, uid := range remoteDRD.Hops[fmt.Sprintf("%d", k)] {
				if !visited[uid] {
					visited[uid] = true
					ourHop := fmt.Sprintf("%d", k+1)
					hops[ourHop] = append(hops[ourHop], uid)
				}
			}
		}
	}

	// Step 2: produce and store the DRD.
	drd := &model.DirectRelationsDependencies{
		Version:          1,
		UserID:           dr.UserID,
		DRContentAddress: drAddr,
		Hops:             hops,
		Sources:          sources,
		SourceTimestamp:  dr.Timestamp,
		ComputedAt:       time.Now().Unix(),
		PeerID:           hp.host.ID().String(),
	}
	if err := hp.signDRD(drd); err != nil {
		return nil, nil, "", fmt.Errorf("signing DRD: %w", err)
	}

	drdData, err := json.Marshal(drd)
	if err != nil {
		return nil, nil, "", err
	}
	drdAddr := model.ContentAddr(drdData)

	if err := hp.store.Put(drdAddr, drdData); err != nil {
		return nil, nil, "", fmt.Errorf("storing DRD: %w", err)
	}

	return drd, drdData, drdAddr, nil
}

// publishDRD computes and caches the DRD, then publishes it to the DHT.
// Step 1: produce and cache the DRD (via computeDRD).
// Step 2: announce the DRD content address (/drd-data/<drd-addr>).
// Step 3: store the DHT pointer ("drd", dr-content-address) → (drd-content-address, timestamp).
func (hp *HomePeer) publishDRD(ctx context.Context, hu *homedUser, dr *model.DirectRelations, drAddr string) {
	// Step 1: produce and cache.
	drd, drdData, drdAddr, err := hp.computeDRD(ctx, dr, drAddr)
	if err != nil {
		log.Printf("computing DRD for %s: %v", dr.UserID, err)
		return
	}

	hp.mu.Lock()
	hu.drd = drd
	hu.drdContentAddr = drdAddr
	hp.mu.Unlock()

	hp.saveState()

	pubCtx, cancel := context.WithTimeout(ctx, 30*time.Second)
	defer cancel()

	// Step 2: announce content address availability.
	if err := hp.dht.PutValue(pubCtx, "/drd-data/"+drdAddr, drdData); err != nil {
		log.Printf("publishing DRD content for %s: %v", dr.UserID, err)
	}

	// Step 3: store DHT pointer ("drd", dr-content-address) → (drd-content-address, timestamp).
	ptr := model.DRDPointer{
		DRContentAddress:  drAddr,
		DRDContentAddress: drdAddr,
		Timestamp:         drd.ComputedAt,
		PeerID:            hp.host.ID().String(),
	}
	ptrData, _ := json.Marshal(ptr)
	if err := hp.dht.PutValue(pubCtx, "/drd/"+drAddr, ptrData); err != nil {
		log.Printf("publishing DRD pointer for %s: %v", dr.UserID, err)
	}
}

// signDRD signs a DirectRelationsDependencies document with the home peer's key.
func (hp *HomePeer) signDRD(drd *model.DirectRelationsDependencies) error {
	payload := map[string]any{
		"version":            drd.Version,
		"user_id":            drd.UserID,
		"dr_content_address": drd.DRContentAddress,
		"hops":               drd.Hops,
		"sources":            drd.Sources,
		"source_timestamp":   drd.SourceTimestamp,
		"computed_at":        drd.ComputedAt,
		"peer_id":            drd.PeerID,
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

// loadState reads the persisted state file; returns an empty state if not found or unreadable.
func (hp *HomePeer) loadState() savedState {
	data, err := os.ReadFile(hp.stateFile)
	if err != nil {
		return savedState{HomedUsers: map[string]savedUserState{}, CachedDRAddrs: map[string]string{}}
	}
	var s savedState
	if err := json.Unmarshal(data, &s); err != nil {
		return savedState{HomedUsers: map[string]savedUserState{}, CachedDRAddrs: map[string]string{}}
	}
	if s.HomedUsers == nil {
		s.HomedUsers = map[string]savedUserState{}
	}
	if s.CachedDRAddrs == nil {
		s.CachedDRAddrs = map[string]string{}
	}
	return s
}

// saveState persists the current in-memory state to disk.
func (hp *HomePeer) saveState() {
	hp.mu.RLock()
	s := savedState{
		HomedUsers:    make(map[string]savedUserState, len(hp.users)),
		CachedDRAddrs: make(map[string]string, len(hp.cachedDRAddrs)),
	}
	for uid, hu := range hp.users {
		s.HomedUsers[uid] = savedUserState{
			DRContentAddr:  hu.drContentAddr,
			DRDContentAddr: hu.drdContentAddr,
		}
	}
	for uid, addr := range hp.cachedDRAddrs {
		s.CachedDRAddrs[uid] = addr
	}
	hp.mu.RUnlock()

	data, err := json.Marshal(s)
	if err != nil {
		log.Printf("marshalling state: %v", err)
		return
	}
	tmp := hp.stateFile + ".tmp"
	if err := os.WriteFile(tmp, data, 0o644); err != nil {
		log.Printf("writing state: %v", err)
		return
	}
	if err := os.Rename(tmp, hp.stateFile); err != nil {
		log.Printf("renaming state file: %v", err)
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
			type userSnapshot struct {
				hu    *homedUser
				drAddr string
			}
			var snapshots []userSnapshot
			for _, hu := range hp.users {
				snapshots = append(snapshots, userSnapshot{hu: hu, drAddr: hu.drContentAddr})
			}
			hp.mu.RUnlock()

			for _, snap := range snapshots {
				if snap.drAddr == "" {
					continue
				}
				data, err := hp.store.Get(snap.drAddr)
				if err != nil || data == nil {
					continue
				}
				var dr model.DirectRelations
				if json.Unmarshal(data, &dr) != nil {
					continue
				}
				hp.publishDRD(ctx, snap.hu, &dr, snap.drAddr)
			}
		}
	}
}
