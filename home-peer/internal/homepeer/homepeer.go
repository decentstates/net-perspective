package homepeer

import (
	"bytes"
	"compress/gzip"
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"log"
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

// HealthInfo holds the peer's current runtime status.
type HealthInfo struct {
	PeerID           string   `json:"peer_id"`
	RoutingTableSize int      `json:"routing_table_size"`
	NumUsers         int      `json:"num_users"`
	Addrs            []string `json:"addrs"`
}

// HomePeer manages homed users, computes DRDs, and publishes to the DHT.
// All user state is stored in the DHT; only the set of homed user IDs is kept in memory.
type HomePeer struct {
	host    host.Host
	dht     *dht.IpfsDHT
	store   *store.Store
	users   map[string]struct{} // set of homed peer ID strings
	privKey crypto.PrivKey
}

// New creates a HomePeer for the given user IDs and starts the background cycle.
func New(h host.Host, d *dht.IpfsDHT, s *store.Store, privKey crypto.PrivKey, userIDs []peer.ID) (*HomePeer, error) {
	hp := &HomePeer{
		host:    h,
		dht:     d,
		store:   s,
		users:   make(map[string]struct{}, len(userIDs)),
		privKey: privKey,
	}
	for _, pid := range userIDs {
		hp.users[pid.String()] = struct{}{}
	}
	go hp.runCycle(context.Background())
	return hp, nil
}

// Publish stores a new DirectRelations for a homed user and triggers DHT publishing.
// Returns ErrNotHomed if the user was not configured at startup.
func (hp *HomePeer) Publish(dr *model.DirectRelations) error {
	if _, ok := hp.users[dr.UserID]; !ok {
		return ErrNotHomed
	}
	if err := validator.ValidateDR(dr); err != nil {
		return fmt.Errorf("validation: %w", err)
	}
	data, err := json.Marshal(dr)
	if err != nil {
		return fmt.Errorf("marshalling DR: %w", err)
	}
	addr := model.ContentAddr(data)
	if err := hp.store.Put(addr, data); err != nil {
		return fmt.Errorf("storing DR: %w", err)
	}
	go hp.publishDRToDHT(dr.UserID, addr, data, dr.Timestamp)
	go hp.publishDRD(context.Background(), dr, addr)
	return nil
}

// Feed returns the pre-built dependency bundle for a homed user as a decompressed
// slice of DirectRelations, fetching the current state from the DHT.
func (hp *HomePeer) Feed(ctx context.Context, userID string) ([]model.DirectRelations, error) {
	if _, ok := hp.users[userID]; !ok {
		return nil, ErrNotHomed
	}

	_, drAddr := hp.fetchDRForUser(ctx, userID)
	if drAddr == "" {
		return nil, nil
	}

	drd, err := hp.fetchDRD(ctx, drAddr)
	if err != nil || drd == nil || drd.DependenciesContentAddress == "" {
		return nil, nil
	}

	bundleData, err := hp.fetchContent(ctx, "/dep/"+drd.DependenciesContentAddress, drd.DependenciesContentAddress)
	if err != nil || bundleData == nil {
		return nil, nil
	}

	return decompressBundle(bundleData)
}

// Deps returns the current DRD header for a homed user, fetching from the DHT.
func (hp *HomePeer) Deps(ctx context.Context, userID string) (*model.DirectRelationsDependencies, error) {
	if _, ok := hp.users[userID]; !ok {
		return nil, ErrNotHomed
	}
	_, drAddr := hp.fetchDRForUser(ctx, userID)
	if drAddr == "" {
		return nil, nil
	}
	return hp.fetchDRD(ctx, drAddr)
}

// CachedDR returns a DirectRelations for any user by fetching the current pointer from the DHT.
func (hp *HomePeer) CachedDR(ctx context.Context, userID string) (*model.DirectRelations, error) {
	dr, _ := hp.fetchDRForUser(ctx, userID)
	return dr, nil
}

// GetDep retrieves a raw dependency bundle by content address.
func (hp *HomePeer) GetDep(addr string) ([]byte, error) {
	return hp.store.Get(addr)
}

// Health returns the peer's current runtime status.
func (hp *HomePeer) Health() HealthInfo {
	rt := hp.dht.RoutingTable()
	addrs := make([]string, len(hp.host.Addrs()))
	for i, a := range hp.host.Addrs() {
		addrs[i] = a.String()
	}
	return HealthInfo{
		PeerID:           hp.host.ID().String(),
		RoutingTableSize: rt.Size(),
		NumUsers:         len(hp.users),
		Addrs:            addrs,
	}
}

// dhtGet fetches a single value from the DHT with a standard timeout.
func (hp *HomePeer) dhtGet(ctx context.Context, key string) ([]byte, error) {
	fetchCtx, cancel := context.WithTimeout(ctx, dhtTimeout)
	defer cancel()
	return hp.dht.GetValue(fetchCtx, key)
}

// dhtPut publishes a value to the DHT with a standard timeout, logging on failure.
func (hp *HomePeer) dhtPut(ctx context.Context, key string, data []byte) {
	putCtx, cancel := context.WithTimeout(ctx, 30*time.Second)
	defer cancel()
	if err := hp.dht.PutValue(putCtx, key, data); err != nil {
		log.Printf("DHT put %s: %v", key, err)
	}
}

// fetchContent returns the bytes for a content-addressed item, checking the local store
// first. On a cache miss it fetches from the DHT using dhtKey, stores the result, and
// returns it. Returns nil, nil when the item is absent from both.
func (hp *HomePeer) fetchContent(ctx context.Context, dhtKey, addr string) ([]byte, error) {
	if data, err := hp.store.Get(addr); err != nil {
		return nil, err
	} else if data != nil {
		return data, nil
	}
	data, err := hp.dhtGet(ctx, dhtKey)
	if err != nil {
		return nil, err
	}
	_ = hp.store.Put(addr, data)
	return data, nil
}

// fetchDRForUser resolves a user's current DR from the DHT pointer and fetches the content.
func (hp *HomePeer) fetchDRForUser(ctx context.Context, userID string) (*model.DirectRelations, string) {
	ptrData, err := hp.dhtGet(ctx, "/dr/"+userID)
	if err != nil {
		return nil, ""
	}
	var ptr model.DRPointer
	if json.Unmarshal(ptrData, &ptr) != nil {
		return nil, ""
	}
	drData, err := hp.fetchContent(ctx, "/dr-data/"+ptr.ContentAddress, ptr.ContentAddress)
	if err != nil {
		return nil, ""
	}
	var dr model.DirectRelations
	if json.Unmarshal(drData, &dr) != nil {
		return nil, ""
	}
	return &dr, ptr.ContentAddress
}

// fetchDRD resolves the DRD for a given DR content address from the DHT pointer and fetches the content.
func (hp *HomePeer) fetchDRD(ctx context.Context, drAddr string) (*model.DirectRelationsDependencies, error) {
	ptrData, err := hp.dhtGet(ctx, "/drd/"+drAddr)
	if err != nil {
		return nil, nil
	}
	var ptr model.DRDPointer
	if json.Unmarshal(ptrData, &ptr) != nil {
		return nil, nil
	}
	drdData, err := hp.fetchContent(ctx, "/drd-data/"+ptr.DRDContentAddress, ptr.DRDContentAddress)
	if err != nil {
		return nil, nil
	}
	var drd model.DirectRelationsDependencies
	if json.Unmarshal(drdData, &drd) != nil {
		return nil, nil
	}
	return &drd, nil
}

// publishDRToDHT publishes DR content and the user-id pointer to the DHT.
func (hp *HomePeer) publishDRToDHT(userID, addr string, data []byte, timestamp int64) {
	ctx := context.Background()
	hp.dhtPut(ctx, "/dr-data/"+addr, data)
	ptr := model.DRPointer{UserID: userID, ContentAddress: addr, Timestamp: timestamp}
	ptrData, _ := json.Marshal(ptr)
	hp.dhtPut(ctx, "/dr/"+userID, ptrData)
}

// buildDependencyBundle fetches DR documents for all given user IDs, bundles them into a
// gzip-compressed JSON array, stores it in the content store, and returns the bytes and
// content address.
func (hp *HomePeer) buildDependencyBundle(ctx context.Context, depUserIDs []string) ([]byte, string, error) {
	var drs []model.DirectRelations
	for _, uid := range depUserIDs {
		if dr, _ := hp.fetchDRForUser(ctx, uid); dr != nil {
			drs = append(drs, *dr)
		}
	}

	jsonBytes, err := json.Marshal(drs)
	if err != nil {
		return nil, "", fmt.Errorf("marshalling bundle: %w", err)
	}

	var buf bytes.Buffer
	gz := gzip.NewWriter(&buf)
	if _, err := gz.Write(jsonBytes); err != nil {
		return nil, "", fmt.Errorf("compressing bundle: %w", err)
	}
	if err := gz.Close(); err != nil {
		return nil, "", fmt.Errorf("closing gzip writer: %w", err)
	}

	compressed := buf.Bytes()
	addr := model.ContentAddr(compressed)
	if err := hp.store.Put(addr, compressed); err != nil {
		return nil, "", fmt.Errorf("storing bundle: %w", err)
	}
	return compressed, addr, nil
}

// decompressBundle decompresses a gzip-compressed JSON array of DirectRelations.
func decompressBundle(data []byte) ([]model.DirectRelations, error) {
	gr, err := gzip.NewReader(bytes.NewReader(data))
	if err != nil {
		return nil, err
	}
	defer gr.Close()
	var drs []model.DirectRelations
	if err := json.NewDecoder(gr).Decode(&drs); err != nil {
		return nil, err
	}
	return drs, nil
}

// extractHop1Users collects the unique set of hop-1 target users from a DR document.
func extractHop1Users(dr *model.DirectRelations) []string {
	seen := map[string]bool{}
	var users []string
	for _, ctxEntry := range dr.Contexts {
		for _, rel := range ctxEntry.Relations {
			if rel.Type == "link" && rel.TargetUser != "" && !seen[rel.TargetUser] {
				seen[rel.TargetUser] = true
				users = append(users, rel.TargetUser)
			}
		}
	}
	return users
}

// mergeRemoteHops fetches each hop-1 user's DRD from the DHT and merges their hop
// lists into hops (offset by +1). Returns the list of DRD content addresses consumed.
func (hp *HomePeer) mergeRemoteHops(ctx context.Context, hop1Users []string, hops model.HopDependencies) (sources []string) {
	visited := make(map[string]bool, len(hop1Users))
	for _, uid := range hop1Users {
		visited[uid] = true
	}
	seenSources := map[string]bool{}

	for _, targetUID := range hop1Users {
		drPtrData, err := hp.dhtGet(ctx, "/dr/"+targetUID)
		if err != nil {
			continue
		}
		var drPtr model.DRPointer
		if json.Unmarshal(drPtrData, &drPtr) != nil {
			continue
		}
		theirDRAddr := drPtr.ContentAddress
		_, _ = hp.fetchContent(ctx, "/dr-data/"+theirDRAddr, theirDRAddr)

		drdPtrData, err := hp.dhtGet(ctx, "/drd/"+theirDRAddr)
		if err != nil {
			continue
		}
		var drdPtr model.DRDPointer
		if json.Unmarshal(drdPtrData, &drdPtr) != nil {
			continue
		}
		drdAddr := drdPtr.DRDContentAddress

		drdData, err := hp.fetchContent(ctx, "/drd-data/"+drdAddr, drdAddr)
		if err != nil {
			continue
		}
		var remoteDRD model.DirectRelationsDependencies
		if json.Unmarshal(drdData, &remoteDRD) != nil {
			continue
		}

		if !seenSources[drdAddr] {
			seenSources[drdAddr] = true
			sources = append(sources, drdAddr)
		}
		for k := 1; k < maxHops; k++ {
			for _, uid := range remoteDRD.Hops[fmt.Sprintf("%d", k)] {
				if !visited[uid] {
					visited[uid] = true
					hops[fmt.Sprintf("%d", k+1)] = append(hops[fmt.Sprintf("%d", k+1)], uid)
				}
			}
		}
	}
	return sources
}

// computeDRD fetches dependency DRDs, builds the dependency bundle, then produces and
// stores the DRD header. Returns the DRD, its serialised bytes, and its content address.
func (hp *HomePeer) computeDRD(ctx context.Context, dr *model.DirectRelations, drAddr string) (*model.DirectRelationsDependencies, []byte, string, error) {
	hop1Users := extractHop1Users(dr)

	hops := make(model.HopDependencies)
	hops["1"] = hop1Users
	for i := 2; i <= maxHops; i++ {
		hops[fmt.Sprintf("%d", i)] = []string{}
	}

	sources := hp.mergeRemoteHops(ctx, hop1Users, hops)

	allDepSeen := map[string]bool{dr.UserID: true}
	var allDepUsers []string
	for _, uids := range hops {
		for _, uid := range uids {
			if !allDepSeen[uid] {
				allDepSeen[uid] = true
				allDepUsers = append(allDepUsers, uid)
			}
		}
	}

	_, bundleAddr, err := hp.buildDependencyBundle(ctx, allDepUsers)
	if err != nil {
		log.Printf("building dependency bundle for %s: %v", dr.UserID, err)
		bundleAddr = ""
	}

	drd := &model.DirectRelationsDependencies{
		Version:                    1,
		UserID:                     dr.UserID,
		DRContentAddress:           drAddr,
		Hops:                       hops,
		Sources:                    sources,
		DependenciesContentAddress: bundleAddr,
		SourceTimestamp:            dr.Timestamp,
		ComputedAt:                 time.Now().Unix(),
		PeerID:                     hp.host.ID().String(),
	}
	if err := hp.signDRD(drd); err != nil {
		return nil, nil, "", fmt.Errorf("signing DRD: %w", err)
	}

	drdData, err := json.Marshal(drd)
	if err != nil {
		return nil, nil, "", err
	}
	drdAddr2 := model.ContentAddr(drdData)
	if err := hp.store.Put(drdAddr2, drdData); err != nil {
		return nil, nil, "", fmt.Errorf("storing DRD: %w", err)
	}
	return drd, drdData, drdAddr2, nil
}

// publishDRD computes and publishes the DRD header and dependency bundle to the DHT.
func (hp *HomePeer) publishDRD(ctx context.Context, dr *model.DirectRelations, drAddr string) {
	drd, drdData, drdAddr, err := hp.computeDRD(ctx, dr, drAddr)
	if err != nil {
		log.Printf("computing DRD for %s: %v", dr.UserID, err)
		return
	}

	hp.dhtPut(ctx, "/drd-data/"+drdAddr, drdData)

	if drd.DependenciesContentAddress != "" {
		if bundleData, _ := hp.store.Get(drd.DependenciesContentAddress); bundleData != nil {
			hp.dhtPut(ctx, "/dep/"+drd.DependenciesContentAddress, bundleData)
		}
	}

	ptr := model.DRDPointer{
		DRContentAddress:  drAddr,
		DRDContentAddress: drdAddr,
		Timestamp:         drd.ComputedAt,
		PeerID:            hp.host.ID().String(),
	}
	ptrData, _ := json.Marshal(ptr)
	hp.dhtPut(ctx, "/drd/"+drAddr, ptrData)
}

// signDRD signs a DirectRelationsDependencies document with the home peer's key.
func (hp *HomePeer) signDRD(drd *model.DirectRelationsDependencies) error {
	payload := map[string]any{
		"version":                      drd.Version,
		"user_id":                      drd.UserID,
		"dr_content_address":           drd.DRContentAddress,
		"hops":                         drd.Hops,
		"sources":                      drd.Sources,
		"dependencies_content_address": drd.DependenciesContentAddress,
		"source_timestamp":             drd.SourceTimestamp,
		"computed_at":                  drd.ComputedAt,
		"peer_id":                      drd.PeerID,
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

// runCycle recomputes and republishes DRDs for all homed users every 10 minutes.
func (hp *HomePeer) runCycle(ctx context.Context) {
	ticker := time.NewTicker(cycleInterval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			for uid := range hp.users {
				dr, drAddr := hp.fetchDRForUser(ctx, uid)
				if dr == nil {
					continue
				}
				hp.publishDRD(ctx, dr, drAddr)
			}
		}
	}
}
