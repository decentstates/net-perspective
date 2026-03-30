package model

import (
	"fmt"
	"strings"
)

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
// characters exclude dots. Used for DHT keys and local storage keys only —
// never stored in document data.
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

// DirectRelations is the primary document stored per user in the DHT.
// DHT key: /dr/<peer-id-string>
type DirectRelations struct {
	Version   int            `json:"version"`
	UserID    string         `json:"user_id"`
	Timestamp int64          `json:"timestamp"`
	Contexts  []ContextEntry `json:"contexts"`
	Signature string         `json:"signature"`
}

// HopDependencies maps hop number ("1"–"6") to the list of user peer IDs at that depth.
type HopDependencies map[string][]string

// DirectRelationsDependencies is a per-(user, context) document signed by the home peer.
// One document is produced per context in the user's DirectRelations.
// DHT key: /drd/<peer-id-string>/<dot-joined-context>
type DirectRelationsDependencies struct {
	Version         int             `json:"version"`
	UserID          string          `json:"user_id"`
	Context         []string        `json:"context"`          // the specific context this DRD covers
	Hops            HopDependencies `json:"hops"`             // hop number → peer IDs
	Sources         []string        `json:"sources"`          // DHT keys of remote DRDs consumed
	SourceTimestamp int64           `json:"source_timestamp"` // timestamp of the DR this was derived from
	ComputedAt      int64           `json:"computed_at"`
	PeerID          string          `json:"peer_id"`
	Signature       string          `json:"signature"`
}
