package model

import (
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"strings"
)

// ContentAddr returns the SHA-256 hex content address of data.
func ContentAddr(data []byte) string {
	h := sha256.Sum256(data)
	return hex.EncodeToString(h[:])
}

// ValidateContextPath validates that each element in a context path matches the
// permissible character set: [a-z0-9\-], not starting with a digit.
func ValidateContextPath(path []string) error {
	if len(path) == 0 {
		return fmt.Errorf("context path must not be empty")
	}
	for i, elem := range path {
		if len(elem) == 0 {
			return fmt.Errorf("element %d is empty", i)
		}
		for j, r := range elem {
			switch {
			case r >= 'a' && r <= 'z':
				// ok
			case r == '-':
				// ok
			case r >= '0' && r <= '9':
				if j == 0 {
					return fmt.Errorf("element %d %q: must not start with a digit", i, elem)
				}
			default:
				return fmt.Errorf("element %d %q: invalid character %q (only [a-z0-9\\-] permitted)", i, elem, r)
			}
		}
	}
	return nil
}

// Relation represents a single relation (link or leaf).
type Relation struct {
	Type          string         `json:"type"`
	UserLinkUserID    string         `json:"target_user,omitempty"`
	UserLinkTargetContext []string       `json:"target_context,omitempty"`
	URLLinkURL           string         `json:"url,omitempty"`
	Properties    map[string]any `json:"properties"`
}

// ContextEntry holds the relations for one context path.
type ContextEntry struct {
	Path      []string   `json:"path"`
	Relations []Relation `json:"relations"`
}

// DirectRelations is the primary document published per user.
// DHT pointer:  /dr/<user-id>           → DRPointer
type DirectRelations struct {
	Version   int            `json:"version"`
	UserID    string         `json:"user_id"`
	Timestamp int64          `json:"timestamp"`
	Contexts  []ContextEntry `json:"contexts"`
	Signature string         `json:"signature"`
}

// DRPointer is the DHT record at /dr/<user-id>.
// It maps a user identity to their current DirectRelations content address.
type DRPointer struct {
	DRContentAddress string `json:"dr_content_address"`
	Timestamp      int64  `json:"timestamp"`
}

type ContextDependencies struct {
	Context                    []string   `json:"context"`
	DependeniesByHop           map[int][]string `json:"dependencies_by_hop"`
	DependenciesContentAddress string `json:"dependencies_content_address"`
	BytesByHop				   map[int]int  `json:"bytes_by_hop"`
}

// DirectRelationsDependencies is produced by a home peer for a specific DR version.
// It covers all contexts in that DR.
// DHT pointer:  /drd/<dr-content-addr>    → DRDPointer
type DirectRelationsDependencies struct {
	Version                    int             `json:"version"`
	UserID                     string          `json:"user_id"`
	DRContentAddress           string          `json:"dr_content_address"`
	Contexts				   ContextDependencies `json:context_dependencies`
	Hops                       HopDependencies `json:"hops"`
	Sources                    []string        `json:"sources"`                      // content addresses of remote DRDs consumed
	DependenciesContentAddress string          `json:"dependencies_content_address"` // content address of gzip dependency bundle
	ComputationTimestamp       int64           `json:"computed_at"` // Timestamp set at the start of the process, all sources should the newest that is less than this timestamp.
	PeerID                     string          `json:"peer_id"`
	Signature                  string          `json:"signature"`
}

// DRDPointer is the DHT record at /drd/<dr-content-address>.
// It maps a DirectRelations content address to its computed DRD content address.
type DRDPointer struct {
	DRDContentAddress string `json:"drd_content_address"`
	Timestamp         int64  `json:"timestamp"`
}

