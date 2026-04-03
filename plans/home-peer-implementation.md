# Home Peer Distributed Document Store — Implementation Plan

## 1. System Overview

### What We're Building

A distributed document store where:

- Each user is identified by a **libp2p peer ID** (derived from a public/private keypair using libp2p's identity system).
- Each user has a single mutable JSON document called their **direct-relations**, containing typed relations organized under contexts.
- A relation is either a **link** to another user (targeting a specific user-id + context on the remote side) or a **leaf** (an arbitrary URL). Relations are grouped under contexts (represented as string arrays) and may carry arbitrary properties.
- Users connect to a **home peer** — a long-lived, DHT-connected server node — via HTTP.
- Home peers pre-compute and cache the full transitive dependency graph for each homed user via a second JSON document called **direct-relations-dependencies**, so client reads are O(1).
- The system guarantees no data is more than 10 minutes stale.

### Two Document Types in the DHT

| Document | DHT Key | Producer | Contents |
|---|---|---|---|
| **direct-relations** | `hash(user-id)` | User (via home peer) | Signed JSON document containing relations (links and leaves) organized under contexts |
| **direct-relations-dependencies** | `hash("dep:" + user-id + ":" + direct-relations-timestamp)` | Home peer | JSON document listing all user-ids in the transitive closure of a user's link relations, organized by source context and hop distance (max 6 hops) |

Both are stored, cached, replicated, and expired using the same DHT mechanisms.

### Architecture Diagram

```
┌──────────┐   HTTP    ┌────────────┐   libp2p/DHT   ┌────────────┐
│  Browser  │◄────────►│  Home Peer  │◄──────────────►│  Home Peer  │
│  Client   │          │     (A)     │                │     (B)     │
└──────────┘          └────────────┘                └────────────┘
                            │                              │
                            │        ┌────────────┐        │
                            └───────►│  Bootstrap  │◄──────┘
                                     │   Nodes     │
                                     └────────────┘
```

Clients never touch the DHT. Home peers are the DHT nodes. Bootstrap nodes are minimal DHT nodes used for peer discovery only.

---

## 2. Technology Stack

| Component | Choice | Rationale |
|---|---|---|
| Language | Go | Strong libp2p support, good concurrency primitives, suitable for server infrastructure |
| P2P networking | `github.com/libp2p/go-libp2p` | Mature Go implementation of libp2p |
| DHT | `github.com/libp2p/go-libp2p-kad-dht` | Kademlia DHT built on go-libp2p |
| Identity | libp2p peer ID (`github.com/libp2p/go-libp2p/core/peer`) | User-ids are libp2p peer IDs, derived from Ed25519 keypairs via libp2p's identity system. This gives us standard encoding, serialization, and interop with the rest of the libp2p stack. |
| Record validation | Custom `record.Validator` | Enforce signature checks and freshness on direct-relations |
| HTTP server | `net/http` (stdlib) | Client-facing API; no framework needed |
| Serialization | JSON (`encoding/json`) | Both document types are JSON. DHT records wrap the signed JSON payload in a thin envelope. |
| Local storage | Badger (`github.com/dgraph-io/badger/v4`) or Pebble | Persistent local cache of direct-relations and direct-relations-dependencies |

---

## 3. Core Data Model

### 3.1 — User Identity

A user-id is a **libp2p peer ID**, generated from an Ed25519 keypair using libp2p's standard identity derivation:

```go
import (
    "github.com/libp2p/go-libp2p/core/crypto"
    "github.com/libp2p/go-libp2p/core/peer"
)

privKey, pubKey, _ := crypto.GenerateEd25519Key(rand.Reader)
peerID, _ := peer.IDFromPublicKey(pubKey)
// peerID is the user-id, e.g. "12D3KooWRfYU5FZQ..."
```

The peer ID encodes the public key and can be serialized as a string (base58/multibase). All references to users throughout the system use this peer ID representation.

### 3.2 — Context

A **context** is an array of strings representing a hierarchical path or category under which relations are organized. Examples:

```json
["friends"]
["work", "engineering"]
["projects", "open-source", "libp2p"]
```

**Permissible characters:** Each element must match `[a-z0-9\-]` and must not start with a digit. This means elements are lowercase alphanumeric with hyphens, beginning with a letter or hyphen. No dots, spaces, uppercase, or other characters are allowed.

Contexts are represented as arrays everywhere — in JSON documents, DHT keys, and API responses. The dot-joined form (e.g., `"work.engineering"`) is a UI convenience for display only and is never used in the data layer. Because element characters exclude dots, the dot-joined form is always unambiguously reversible.

Contexts serve as namespaces within a user's direct-relations document. A user can have different relations under different contexts.

### 3.3 — Relation Types

A relation lives under a context and is one of two types:

**Link** — a directed edge from your context to another user's context:

```json
{
  "type": "link",
  "target_user": "12D3KooWRfYU5FZQ...",
  "target_context": ["collaborators"],
  "properties": {
    "label": "my colleague",
    "since": "2025-06-01"
  }
}
```

A link connects *your* context to *their* context. The source context is implicit (it's the context this relation is listed under). The target is a tuple of `(user-id, context)`.

**Leaf** — a terminal reference to an external resource:

```json
{
  "type": "leaf",
  "url": "https://example.com/my-project",
  "properties": {
    "description": "Project homepage",
    "pinned": true
  }
}
```

Leaves do not participate in transitive dependency resolution (they don't point to other users).

### 3.4 — direct-relations Document (JSON)

The full direct-relations document for a user:

```json
{
  "version": 1,
  "user_id": "12D3KooWRfYU5FZQ...",
  "timestamp": 1719849600,
  "contexts": [
    {
      "path": ["friends"],
      "relations": [
        {
          "type": "link",
          "target_user": "12D3KooWAbCdEfG...",
          "target_context": ["friends"],
          "properties": {"nickname": "Alice"}
        },
        {
          "type": "link",
          "target_user": "12D3KooWHiJkLmN...",
          "target_context": ["acquaintances"],
          "properties": {}
        },
        {
          "type": "leaf",
          "url": "https://friends-group.example.com",
          "properties": {"label": "group chat"}
        }
      ]
    },
    {
      "path": ["work", "engineering"],
      "relations": [
        {
          "type": "link",
          "target_user": "12D3KooWOpQrStU...",
          "target_context": ["work", "engineering"],
          "properties": {"role": "tech lead"}
        }
      ]
    }
  ],
  "signature": "<base64-encoded Ed25519 signature over the canonical JSON of all fields except signature>"
}
```

**Signature scope:** The signature covers the canonical JSON serialization of `version`, `user_id`, `timestamp`, and `contexts` (sorted keys, no whitespace). This allows any node to verify that the document was produced by the claimed user.

### 3.5 — direct-relations-dependencies Document (JSON)

Produced by a home peer, listing all user-ids transitively reachable through link relations. Dependencies are organized by the **source context** they originate from and by the **number of hops** from the user. The maximum hop depth is **6**.

```json
{
  "version": 1,
  "user_id": "12D3KooWRfYU5FZQ...",
  "context": ["work", "engineering"],
  "hops": {
    "1": ["12D3KooWOpQrStU..."],
    "2": ["12D3KooWMnOpQrS...", "12D3KooWJkLmNoP..."],
    "3": [],
    "4": [],
    "5": [],
    "6": []
  },
  "sources": [
    "/drd/a1b2c3d4e5f6...",
    "/drd/f6e5d4c3b2a1..."
  ],
  "source_timestamp": 1719849600,
  "computed_at": 1719850200,
  "peer_id": "12D3KooWHomePeerA...",
  "signature": "<base64-encoded signature by the home peer's key>"
}
```

- `version` is the document format version (currently `1`). Allows future schema evolution — peers that encounter an unknown version can reject or handle gracefully.
- `context` identifies which context of the user's DirectRelations this DRD covers (array of strings). One DRD document is produced per context.
- `hops` maps hop number (`"1"` through `"6"` as strings) to the peer IDs discovered at that depth from this context.
  - **Hop 1**: users directly linked from this context (the `target_user` values of link relations under this context).
  - **Hop 2**: users that hop-1 users link to from their `target_context` (i.e., following the link into the remote user's context, then extracting that context's links).
  - **Hop 3–6**: continuing the traversal, each hop following links from the target contexts reached at the previous hop.
- A user-id may appear at multiple hop levels across different contexts, or even within the same context if reachable via multiple paths. Each hop level lists the user-ids first discovered at that depth.
- The maximum depth is **6 hops**. Links beyond 6 hops are not followed.
- `sources` is an array of DHT keys identifying the direct-relations-dependencies documents that were consumed during computation. These are the dependency documents fetched from remote users' home peers and used to compose this document's transitive closure. This provides an auditable provenance chain — any peer can verify the computation by fetching the source documents and re-deriving the result. If the computation was done entirely by direct traversal (no remote dependency documents available), this array is empty.
- `source_timestamp` matches the `timestamp` of the direct-relations document this was derived from.
- `computed_at` is when the home peer ran the computation.
- `peer_id` identifies which home peer produced this.
- `signature` is by the home peer's libp2p identity key, allowing consumers to verify provenance.

**Context-aware traversal:** Unlike the flat dependency model, this structure respects context boundaries during traversal. A link from context `["friends"]` to `(UserB, ["friends"])` means we follow UserB's `["friends"]` context for the next hop — not their `["work"]` context. Each context in the user's direct-relations produces its own independent dependency tree. This means a user's "friends" dependency chain and "work" dependency chain are computed and stored separately.

---

## 4. Implementation Phases

### Phase 0: Bare DHT Playground (1–2 weeks)

**Goal:** Get a working Kademlia DHT where you can store and retrieve arbitrary key-value records from multiple nodes. No direct-relations, no dependency documents, no HTTP — just the DHT.

#### 0.1 — Bootstrap Node

Build a minimal Go binary that:

1. Generates a persistent libp2p identity (Ed25519 keypair) saved to disk.
2. Listens on a fixed TCP port and (optionally) QUIC.
3. Creates a Kademlia DHT in server mode (`dht.ModeServer`).
4. Logs its multiaddr on startup (e.g., `/ip4/203.0.113.1/tcp/4001/p2p/12D3KooW...`).

This is the seed node. Run 2–3 of these on known IPs/ports.

```go
// Pseudocode — Phase 0.1
host, _ := libp2p.New(
    libp2p.Identity(loadOrCreateKey("identity.key")),
    libp2p.ListenAddrStrings("/ip4/0.0.0.0/tcp/4001"),
)
kadDHT, _ := dht.New(ctx, host, dht.Mode(dht.ModeServer))
kadDHT.Bootstrap(ctx)
fmt.Println("Bootstrap node running:", host.Addrs(), host.ID())
select {} // block forever
```

#### 0.2 — Peer Node (Put/Get)

Build a second binary that:

1. Generates its own identity.
2. Connects to the bootstrap nodes.
3. Joins the DHT in server mode.
4. Exposes a CLI interface to `PUT key value` and `GET key` against the DHT.

Use `kadDHT.PutValue(ctx, "/myapp/somekey", value)` and `kadDHT.GetValue(ctx, "/myapp/somekey")`.

You'll need a custom `record.Validator` registered under the namespace `"myapp"` that accepts any value for now.

```go
// Minimal validator for Phase 0
type permissiveValidator struct{}
func (v *permissiveValidator) Validate(key string, value []byte) error { return nil }
func (v *permissiveValidator) Select(key string, vals [][]byte) (int, error) { return 0, nil }
```

Register it:

```go
kadDHT, _ := dht.New(ctx, host,
    dht.Mode(dht.ModeServer),
    dht.BootstrapPeers(bootstrapAddrs...),
    dht.Validator(record.NamespacedValidator{
        "myapp": &permissiveValidator{},
    }),
)
```

#### 0.3 — Experiment

With 3–5 nodes running (can be on localhost with different ports):

- Put a value from node A, get it from node B.
- Kill node A, verify node B can still retrieve the value (it's replicated).
- Measure put/get latency.
- Add more nodes, observe how routing table populates (enable DHT debug logging).
- Experiment with TTLs by looking at record expiration behavior.

**Milestone:** You can store and retrieve arbitrary bytes by key across a multi-node DHT, and data survives individual node failure.

---

### Phase 1: Data Model and Record Validation (1–2 weeks)

**Goal:** Define the direct-relations and direct-relations-dependencies formats, implement proper validation, and store/retrieve signed direct-relations documents.

#### 1.1 — Go Types for Direct-Relations

```go
type Relation struct {
    Type          string         `json:"type"`                     // "link" or "leaf"
    TargetUser    string         `json:"target_user,omitempty"`    // libp2p peer ID string (links only)
    TargetContext []string       `json:"target_context,omitempty"` // target context path (links only)
    URL           string         `json:"url,omitempty"`            // external URL (leaves only)
    Properties    map[string]any `json:"properties"`               // arbitrary key-value pairs
}

// ContextEntry holds the relations for one context path.
// Each element of Path must match [a-z0-9\-] and not start with a digit.
type ContextEntry struct {
    Path      []string   `json:"path"`
    Relations []Relation `json:"relations"`
}

type DirectRelations struct {
    Version   int            `json:"version"`   // document format version (currently 1)
    UserID    string         `json:"user_id"`   // libp2p peer ID of the owner
    Timestamp int64          `json:"timestamp"` // Unix timestamp (seconds)
    Contexts  []ContextEntry `json:"contexts"`  // ordered list of context entries
    Signature string         `json:"signature"` // base64 Ed25519 signature
}
```

Serialized as JSON. The DHT key is `/dr/<peer-id-string>`.

#### 1.2 — Go Types for Direct-Relations-Dependencies

```go
// HopDependencies maps hop number ("1"–"6") to the list of peer IDs at that depth.
type HopDependencies map[string][]string

// DirectRelationsDependencies is a per-(user, context) document signed by the home peer.
// One document is produced per context in the user's DirectRelations.
type DirectRelationsDependencies struct {
    Version         int             `json:"version"`          // document format version (currently 1)
    UserID          string          `json:"user_id"`          // whose dependency list this is
    Context         []string        `json:"context"`          // the specific context this DRD covers
    Hops            HopDependencies `json:"hops"`             // hop number → peer IDs
    Sources         []string        `json:"sources"`          // DHT keys of remote DRDs consumed
    SourceTimestamp int64           `json:"source_timestamp"` // timestamp of the DR this was derived from
    ComputedAt      int64           `json:"computed_at"`      // when the home peer computed this
    PeerID          string          `json:"peer_id"`          // home peer's libp2p peer ID
    Signature       string          `json:"signature"`        // signed by the home peer's key
}
```

DHT key: `/drd/<peer-id-string>/<dot-joined-context>` (e.g. `/drd/12D3KooW.../work.engineering`). One record per (user, context) pair.

#### 1.3 — Direct-Relations Validator

Replace the permissive validator with a real one:

```go
type directRelationsValidator struct{}

func (v *directRelationsValidator) Validate(key string, value []byte) error {
    var dr DirectRelations
    if err := json.Unmarshal(value, &dr); err != nil {
        return err
    }
    // Parse the claimed user-id as a libp2p peer ID
    pid, err := peer.Decode(dr.UserID)
    if err != nil {
        return fmt.Errorf("invalid user_id: %w", err)
    }
    // Extract the public key from the peer ID
    pubKey, err := pid.ExtractPublicKey()
    if err != nil {
        return fmt.Errorf("cannot extract public key: %w", err)
    }
    // Verify signature over canonical JSON (all fields except "signature")
    canonical := canonicalJSON(dr) // deterministic serialization of version, user_id, timestamp, contexts
    sigBytes, err := base64.StdEncoding.DecodeString(dr.Signature)
    if err != nil {
        return fmt.Errorf("invalid signature encoding: %w", err)
    }
    ok, err := pubKey.Verify(canonical, sigBytes)
    if err != nil || !ok {
        return errors.New("invalid signature")
    }
    // Verify the DHT key matches the user-id
    expectedKey := "/dr/" + dr.UserID
    if key != expectedKey {
        return errors.New("key mismatch")
    }
    return nil
}

func (v *directRelationsValidator) Select(key string, vals [][]byte) (int, error) {
    // Select the record with the highest timestamp (most recent wins)
    best := 0
    var bestTs int64
    for i, val := range vals {
        var dr DirectRelations
        json.Unmarshal(val, &dr)
        if dr.Timestamp > bestTs {
            bestTs = dr.Timestamp
            best = i
        }
    }
    return best, nil
}
```

#### 1.4 — Direct-Relations-Dependencies Validator

Similar structure, but verifies the home peer's signature rather than the user's. The `Select` method picks the document with the highest `ComputedAt` timestamp.

#### 1.5 — Canonical JSON for Signing

Signatures must be computed over a deterministic serialization. Implement a `canonicalJSON` function that:

1. Takes the `DirectRelations` struct (excluding the `Signature` field).
2. Serializes with sorted keys at all levels (including nested `properties` maps).
3. Uses no whitespace.
4. Produces identical output regardless of Go map iteration order.
5. Includes the `version` field in the signed payload to prevent cross-version replay attacks.

Consider using a library like `github.com/gibson042/canonicaljson-go` or implementing RFC 8785 (JCS — JSON Canonicalization Scheme).

**Milestone:** Direct-relations and direct-relations-dependencies documents are properly typed, signed, validated, and the DHT enforces correctness at the storage layer.

---

### Phase 2: Home Peer Core (2–3 weeks)

**Goal:** Build the home peer as a server that manages homed users, serves an HTTP API, and participates in the DHT.

#### 2.1 — Home Peer Structure

```go
type HomePeer struct {
    host     host.Host
    dht      *dht.IpfsDHT
    store    LocalStore              // Badger/Pebble for persistent cache
    users    map[string]*HomedUser   // keyed by peer ID string
    mu       sync.RWMutex
}

type HomedUser struct {
    UserID          peer.ID
    DirectRelations *DirectRelations
    Dependencies    *DirectRelationsDependencies
    Cache           map[string]*DirectRelations // cached direct-relations for all dependencies
}
```

#### 2.2 — HTTP API

Endpoints served over standard HTTP/HTTPS:

```
POST   /users/register                — Register a user-id on this home peer
POST   /users/{userid}/publish        — Submit a signed direct-relations update
GET    /users/{userid}/feed           — Get all cached direct-relations for this user's dependency set
GET    /users/{userid}/deps           — Get the current direct-relations-dependencies document
GET    /direct-relations/{userid}     — Get a single cached direct-relations document by user-id
GET    /health                        — Peer status, DHT connectivity, cache stats
```

**Register** takes a user-id (libp2p peer ID) and an initial signed direct-relations document. The home peer stores it locally and publishes to the DHT.

**Publish** accepts an updated signed direct-relations document. The home peer:

1. Validates the signature using the public key extracted from the peer ID.
2. Stores locally.
3. Publishes to DHT (async, doesn't block the response).
4. Immediately recomputes the direct-relations-dependencies (see Phase 3).
5. Returns success to the client.

**Feed** returns all direct-relations documents in the user's dependency set from local cache. This is the O(1) read path — the home peer already has everything.

#### 2.3 — Local Storage

Use Badger (or Pebble) with key prefixes:

```
dr:<peer-id>              → JSON-encoded DirectRelations
drd:<peer-id>             → JSON-encoded DirectRelationsDependencies
cache:<peer-id>           → JSON-encoded DirectRelations (cached for dependency purposes)
meta:users                → list of homed user peer IDs
```

All DHT data fetched during dependency resolution is written here so it survives restarts.

**Milestone:** A home peer that accepts user registrations, stores direct-relations, serves them over HTTP, and publishes to the DHT. Dependency computation not yet implemented — the feed endpoint returns only direct link relations' documents.

---

### Phase 3: Dependency Computation (2–3 weeks)

**Goal:** Implement the inductive direct-relations-dependencies computation with 10-minute cycle guarantees.

#### 3.1 — The Computation Cycle

Every 10 minutes, for each homed user:

```
function computeDependencies(user):
    dependencies = {}  // context key → hop number → set of peer IDs
    sources = []       // DHT keys of remote DRDs consumed; always fully populated

    for each (contextKey, relations) in user.DirectRelations.Contexts:
        // Hop 1: direct link targets from this context.
        // This is the only place a DR document is read; all subsequent hops are inductive.
        hop1Targets = []  // list of (user-id, target-context-key) pairs
        for each relation in relations where relation.type == "link":
            hop1Targets.append({
                user: relation.target_user,
                context: dotJoin(relation.target_context)
            })

        hop1UserIDs = unique user-ids from hop1Targets
        dependencies[contextKey] = {
            "1": hop1UserIDs,
            "2": [], "3": [], "4": [], "5": [], "6": []
        }

        if hop1Targets is empty:
            continue

        // Hops 2–6: purely inductive from the per-context DRDs of hop-1 users.
        // For each hop-1 target (targetUser, targetContext), fetch the DRD at
        // /drd/<targetUser>/<dot-joined-targetContext>. Their hop-k becomes our hop-(k+1).
        // If unavailable, skip — no fallback to direct DR traversal.
        visited = set(hop1UserIDs)

        for each (targetUserID, targetContext) in hop1Targets:
            drdKey = "/drd/" + targetUserID + "/" + dotJoin(targetContext)
            remoteDRD = fetchDRDFromDHT(drdKey)
            if remoteDRD == nil:
                continue  // no home peer or DRD not yet available; skip

            if drdKey not in sources:
                sources.append(drdKey)

            for k = 1 to 5:  // their hop-k → our hop-(k+1)
                theirHop = remoteDRD.Hops[str(k)]
                for each uid in theirHop:
                    if uid not in visited:
                        visited.add(uid)
                        dependencies[contextKey][str(k+1)].append(uid)

    newDepDoc = DirectRelationsDependencies{
        Version:         1,
        UserID:          user.UserID,
        Dependencies:    dependencies,
        Sources:         sources,
        SourceTimestamp:  user.DirectRelations.Timestamp,
        ComputedAt:      now(),
        PeerID:          homePeerID,
    }
    sign(newDepDoc, homePeerKey)
    store locally
    publish to DHT

    // Now ensure we have all direct-relations cached
    allUserIDs = flatten all user-ids across all contexts and hops in dependencies
    for each dep in allUserIDs:
        if not in local cache or stale:
            fetchAndCache(dep)  // DHT lookup for their direct-relations
```

**Context-aware traversal:** The computation follows context boundaries. A link from your `["friends"]` context to `(UserB, ["friends"])` means we compose from UserB's `["friends"]` dependencies. A link from your `["work"]` context to `(UserC, ["engineering"])` means we compose from UserC's `["engineering"]` dependencies. Each of your contexts produces an independent inductive tree.

**Note on link target extraction:** Only `"link"` type relations contribute to the dependency set. Leaf relations (URLs) are ignored for dependency computation.

**Hop depth cap:** The traversal stops at 6 hops. Because each hop-1 user's DRD already encodes their own 6-hop reach, we only need the DRDs of hop-1 users — we shift their hop indices by 1 and cap at 6.

#### 3.2 — Purely Inductive Composition and Auditability

The computation is entirely inductive. Hop 1 is derived from the user's own signed DR — the only DR document the home peer reads during a computation run. All subsequent hops are derived exclusively from the signed DRDs of the hop-1 users, fetched from the DHT at `/drd/<user-id>`.

**There is no fallback to direct DR traversal.** If a user has no home peer, or their home peer has not yet published a DRD, that user appears at hop 1 but their transitive dependencies do not propagate into the network. Users without home peers are leaf nodes. This is a deliberate invariant: it guarantees the `sources` array fully accounts for every input to the computation beyond hop 1.

**Auditability:** Because all inputs are signed committed documents, the computation is independently verifiable. Given Alice's signed DRD and the documents listed in `sources`:

- Every hop-1 user can be traced back to a link in Alice's signed DR.
- Every hop-N user (N ≥ 2) can be traced back to hop-(N-1) in at least one listed source DRD under the relevant target context.

False inclusion or omission at any inductive step can therefore be proved from signed documents alone, without re-running the traversal.

**Convergence on a fresh network:** On the first cycle, each home peer can only compute hop 1 (no remote DRDs exist yet). On the second cycle, hop-1 users' DRDs are available and hop 2 resolves. After 6 cycles (~60 minutes on a 10-minute cycle), the full depth resolves for all connected home peers.

#### 3.3 — Instant Recomputation on Publish

When a homed user publishes an updated direct-relations document (changes their relations):

1. Diff the old and new link target sets (extract `target_user` from all links in old vs new).
2. For added link targets: fetch their direct-relations-dependencies from DHT.
3. Recompute the full direct-relations-dependencies immediately (don't wait for the cycle).
4. Fetch and cache any newly-needed direct-relations documents.
5. Publish the updated direct-relations-dependencies to the DHT.

This ensures the publishing user's own home peer reflects changes instantly, even though remote dependents won't see the change until their next cycle.

#### 3.4 — Parallel Fetching

Both the cycle computation and the instant recomputation should fetch DHT records in parallel. Use a worker pool:

```go
func (hp *HomePeer) fetchMany(ctx context.Context, peerIDs []peer.ID) map[string]*DirectRelations {
    results := make(map[string]*DirectRelations)
    var mu sync.Mutex
    sem := make(chan struct{}, 50) // max 50 concurrent DHT lookups

    var wg sync.WaitGroup
    for _, pid := range peerIDs {
        wg.Add(1)
        sem <- struct{}{}
        go func(pid peer.ID) {
            defer wg.Done()
            defer func() { <-sem }()
            dr := hp.fetchDirectRelations(ctx, pid)
            if dr != nil {
                mu.Lock()
                results[pid.String()] = dr
                mu.Unlock()
            }
        }(pid)
    }
    wg.Wait()
    return results
}
```

#### 3.5 — Staleness Guarantee

The 10-minute guarantee is enforced by:

- The computation cycle runs every 10 minutes (use a `time.Ticker`).
- Direct-relations-dependencies records in the DHT have a TTL of 15 minutes (slightly over the cycle to handle jitter).
- If a direct-relations-dependencies document's `ComputedAt` is older than 15 minutes, consumers treat it as expired.
- Home peers republish their homed users' direct-relations on every cycle as well, ensuring DHT records don't expire.

**Milestone:** Full transitive dependency computation is working. A user's feed endpoint returns all direct-relations documents across the full dependency graph, served from local cache, with guaranteed freshness.

---

### Phase 4: Redundancy and Failover (1–2 weeks)

**Goal:** Users can register with multiple home peers for reliability.

#### 4.1 — Multi-Home Registration

A user registers with 2–3 home peers. Each home peer independently:

- Stores the user's direct-relations.
- Computes direct-relations-dependencies.
- Caches dependency direct-relations documents.
- Publishes to the DHT.

No coordination between home peers is needed — they independently maintain their view of the user's dependency graph. The DHT's `Select` method (most recent timestamp wins) handles deduplication naturally.

#### 4.2 — Client Failover

The client maintains an ordered list of home peer HTTP endpoints. On connection failure or timeout, it falls through to the next:

```
Primary:   https://home1.example.com
Secondary: https://home2.example.com
Tertiary:  https://home3.example.com
```

Since all home peers have the same data (within the staleness window), failover is seamless from the client's perspective.

#### 4.3 — Direct-Relations Consistency

When a user publishes an update, the client should send it to all home peers, not just the primary. This can be done client-side (fire-and-forget to secondaries after the primary acknowledges) or by having the primary home peer forward to known secondaries.

**Milestone:** Users survive home peer failures without data loss or read interruption.

---

### Phase 5: Production Hardening (2–3 weeks)

#### 5.1 — NAT Traversal

Configure libp2p with relay and hole-punching for home peers behind NATs:

```go
host, _ := libp2p.New(
    libp2p.EnableAutoRelay(),
    libp2p.EnableHolePunching(),
    libp2p.EnableNATService(),
)
```

In practice, home peers should be on public IPs or behind port-forwarding for reliable DHT participation.

#### 5.2 — TLS for HTTP

The client-facing HTTP API must be served over TLS. Use Let's Encrypt via `autocert` or terminate TLS at a reverse proxy.

#### 5.3 — Monitoring and Observability

Expose metrics via Prometheus:

- DHT routing table size and health.
- Number of homed users.
- Dependency set sizes (distribution).
- Cache hit/miss rates.
- Cycle computation duration.
- DHT put/get latency histograms.
- Number of failed DHT lookups per cycle.

#### 5.4 — Rate Limiting and Abuse Prevention

- Rate limit publish requests per user-id.
- Cap maximum dependency set size (the 6-hop depth limit helps bound this, but a user with high fan-out at each hop can still produce large dependency sets — consider a hard cap on total unique user-ids across all contexts and hops).
- Validate that direct-relations timestamps are recent (reject backdated or far-future timestamps).

#### 5.5 — Persistence and Recovery

On startup, a home peer:

1. Loads all homed user data from local storage.
2. Rejoins the DHT.
3. Immediately runs a dependency computation cycle.
4. Republishes all homed users' direct-relations and direct-relations-dependencies to the DHT.

This means a home peer can be restarted without data loss or client-visible interruption (beyond a brief HTTP downtime).

---

## 5. DHT Key Namespace Summary

```
/dr/<peer-id-string>                        → DirectRelations (signed by user; Select picks highest timestamp)
/drd/<peer-id-string>/<dot-joined-context>  → DirectRelationsDependencies (signed by home peer; Select picks highest computed_at)
```

DR uses a stable per-user key. DRD uses a stable per-(user, context) key — the dot-joined context is unambiguous because context element characters exclude dots. Each user produces one DRD record per context. Select picks the record with the highest `computed_at` when multiple versions exist.

---

## 6. Wire Formats

### Direct-Relations (JSON)

```json
{
  "version": 1,
  "user_id": "12D3KooW...",
  "timestamp": 1719849600,
  "contexts": {
    "friends": [
      {
        "type": "link",
        "target_user": "12D3KooW...",
        "target_context": ["friends"],
        "properties": {"nickname": "Alice"}
      },
      {
        "type": "leaf",
        "url": "https://example.com/group",
        "properties": {"label": "group chat"}
      }
    ],
    "work.engineering": [
      {
        "type": "link",
        "target_user": "12D3KooW...",
        "target_context": ["work", "engineering"],
        "properties": {"role": "tech lead"}
      }
    ]
  },
  "signature": "base64..."
}
```

### Direct-Relations-Dependencies (JSON)

```json
{
  "version": 1,
  "user_id": "12D3KooW...",
  "dependencies": {
    "friends": {
      "1": ["12D3KooWAbC...", "12D3KooWDeF..."],
      "2": ["12D3KooWGhI..."],
      "3": [],
      "4": [],
      "5": [],
      "6": []
    },
    "work.engineering": {
      "1": ["12D3KooWJkL..."],
      "2": ["12D3KooWMnO...", "12D3KooWPqR..."],
      "3": ["12D3KooWStu..."],
      "4": [],
      "5": [],
      "6": []
    }
  },
  "sources": [
    "/drd/a1b2c3d4e5f6...",
    "/drd/f6e5d4c3b2a1..."
  ],
  "source_timestamp": 1719849600,
  "computed_at": 1719850200,
  "peer_id": "12D3KooW...",
  "signature": "base64..."
}
```

---

## 7. Key Design Decisions and Rationale

### Why not use IPFS's DHT?

Dedicated network gives us control over replication, TTL, record types, and node behavior. IPFS's DHT is optimized for content-addressed block lookups and carries overhead from competing traffic. Our lookups will be faster on a smaller, purpose-built network.

### Why JSON?

Both document types are JSON for readability, debuggability, and broad client compatibility (browser clients parse JSON natively). The documents are small (a few KB typically), so the size overhead of JSON over a binary format is negligible. Canonical JSON (RFC 8785 / JCS) provides deterministic serialization for signatures.

### Why libp2p peer IDs for user identity?

Using libp2p's native identity system means user-ids, DHT node-ids, and cryptographic identities all use the same format and libraries. No custom identity layer to build or maintain. Peer IDs encode the public key, so signature verification doesn't require a separate key lookup. The peer ID format also handles key type evolution (e.g., switching from Ed25519 to another scheme in the future) via multicodec prefixes.

### Why 10-minute cycles and not faster?

The 10-minute window balances freshness against DHT traffic. Each cycle generates O(dependency_set_size) DHT lookups per homed user. Shorter cycles multiply this proportionally. The guarantee is that transitive dependencies are at most 10 minutes stale; direct relations on the same home peer are near-instant.

### Why sign direct-relations-dependencies?

Without signatures, a malicious DHT node could serve a truncated dependency list, causing a home peer to cache an incomplete set of direct-relations documents. Signing by the producing home peer allows consumers to verify authenticity. It doesn't guarantee correctness (the home peer could still lie), but it provides accountability — a misbehaving peer can be identified and blacklisted.

### Why do links target (user-id, context) rather than just user-id?

The context-to-context linking model allows a user to reference a specific facet of another user's relations, and — critically — it defines the traversal path for dependency computation. A link from your `["work", "engineering"]` context to `(UserB, ["work", "engineering"])` means the dependency traversal follows UserB's `["work", "engineering"]` context at the next hop, not their entire document. This produces context-separated dependency trees: your "friends" chain and "work" chain are independently computed and stored, giving the application layer fine-grained control over what gets fetched and cached.

### Why cap at 6 hops?

Six hops covers the vast majority of useful transitive relationships (analogous to "six degrees of separation" in social networks). The cap bounds the worst-case computation cost per cycle, bounds the size of the direct-relations-dependencies document, and guarantees convergence within 6 cycles (~60 minutes) on a fresh network. The hop-separated structure also lets the application layer make decisions based on distance — for example, rendering hop-1 and hop-2 relations prominently while treating hop-5 and hop-6 as peripheral.

---

## 8. Estimated Timeline

| Phase | Duration | Deliverable |
|---|---|---|
| 0: DHT Playground | 1–2 weeks | Multi-node DHT with put/get |
| 1: Data Model | 1–2 weeks | Signed direct-relations with proper validation |
| 2: Home Peer Core | 2–3 weeks | HTTP API, user management, DHT integration |
| 3: Dependency Computation | 2–3 weeks | Full transitive dependency resolution with 10-min cycle |
| 4: Redundancy | 1–2 weeks | Multi-home registration and failover |
| 5: Production Hardening | 2–3 weeks | TLS, monitoring, rate limiting, persistence |
| **Total** | **~10–15 weeks** | |

Phase 0 is explicitly designed to be a standalone playground — the developer can experiment with DHT behavior, latency, replication, and failure modes before committing to the full architecture.

---

## 9. Go Module Dependencies

```go
require (
    github.com/libp2p/go-libp2p             v0.37.x
    github.com/libp2p/go-libp2p-kad-dht     v0.28.x
    github.com/libp2p/go-libp2p-record       v0.2.x
    github.com/multiformats/go-multiaddr     v0.14.x
    github.com/dgraph-io/badger/v4           v4.4.x
    github.com/prometheus/client_golang      v1.20.x
    github.com/gibson042/canonicaljson-go    v1.x.x   // RFC 8785 canonical JSON for deterministic signatures
)
```

Pin to the latest stable versions at project start. The libp2p ecosystem releases frequently; use the versions that are mutually compatible (check go-libp2p's go.mod for its own dependency versions).

---

## 10. Open Questions for the Developer

1. **Home peer discovery:** How does a new user find a home peer to register with? Options include a well-known directory, DNS-based discovery, or manual configuration. This is an application-level concern, not a DHT concern.

2. **Revocation:** What happens when a user removes a link relation? The direct-relations-dependencies shrinks on the next computation, but cached direct-relations documents for removed dependencies may linger. Define a cache eviction policy (e.g., evict cached direct-relations not in any homed user's dependency set, checked after each cycle).

3. **Anti-spam on registration:** Without limits, an attacker could register millions of fake users on a home peer to exhaust its resources. Consider requiring a proof-of-work or rate-limiting registration.

4. **Context key escaping:** If context strings themselves contain dots, the dot-joined key encoding is ambiguous (e.g., `["a.b", "c"]` and `["a", "b.c"]` both become `"a.b.c"`). Consider an alternative encoding (e.g., JSON array as the key, or use a separator that's disallowed in context strings).

5. **Properties schema:** The `properties` field on relations is an arbitrary `map[string]any`. Decide whether the home peer should validate property structure or treat it as opaque. Opaque is simpler; validation enables richer application-level guarantees.

