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

// ContextDotJoin joins a context path with dots. Unambiguous because element
// characters exclude dots. Used for display and UI purposes.
func ContextDotJoin(path []string) string {
	return strings.Join(path, ".")
}

// Relation represents a single relation (link or leaf).
type Relation struct {
	Type          string         `json:"type"`
	TargetUser    string         `json:"target_user,omitempty"`
	TargetContext []string       `json:"target_context,omitempty"`
	URL           string         `json:"url,omitempty"`
	Properties    map[string]any `json:"properties"`
}

// ContextEntry holds the relations for one context path.
type ContextEntry struct {
	Path      []string   `json:"path"`
	Relations []Relation `json:"relations"`
}

// DirectRelations is the primary document published per user.
// DHT pointer:  /dr/<user-id>           → DRPointer
// DHT content:  /dr-data/<content-addr> → raw bytes
type DirectRelations struct {
	Version   int            `json:"version"`
	UserID    string         `json:"user_id"`
	Timestamp int64          `json:"timestamp"`
	Contexts  []ContextEntry `json:"contexts"`
	Signature string         `json:"signature"`
}

// HopDependencies maps hop number ("1"–"6") to the list of user peer IDs at that depth.
type HopDependencies map[string][]string

// DRPointer is the DHT record at /dr/<user-id>.
// It maps a user identity to their current DirectRelations content address.
type DRPointer struct {
	UserID         string `json:"user_id"`
	ContentAddress string `json:"content_address"`
	Timestamp      int64  `json:"timestamp"`
}

// DRDPointer is the DHT record at /drd/<dr-content-address>.
// It maps a DirectRelations content address to its computed DRD content address.
type DRDPointer struct {
	DRContentAddress  string `json:"dr_content_address"`
	DRDContentAddress string `json:"drd_content_address"`
	Timestamp         int64  `json:"timestamp"`
	PeerID            string `json:"peer_id"`
}

// DirectRelationsDependencies is produced by a home peer for a specific DR version.
// It covers all contexts in that DR.
// DHT pointer:  /drd/<dr-content-addr>    → DRDPointer
// DHT content:  /drd-data/<content-addr>  → raw bytes
type DirectRelationsDependencies struct {
	Version          int             `json:"version"`
	UserID           string          `json:"user_id"`
	DRContentAddress string          `json:"dr_content_address"`
	Hops             HopDependencies `json:"hops"`
	Sources          []string        `json:"sources"`         // content addresses of remote DRDs consumed
	SourceTimestamp  int64           `json:"source_timestamp"`
	ComputedAt       int64           `json:"computed_at"`
	PeerID           string          `json:"peer_id"`
	Signature        string          `json:"signature"`
}
