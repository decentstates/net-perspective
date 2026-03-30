package model

import (
	"encoding/json"
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
			case r == '-':
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
// characters exclude dots. Used for DHT keys and storage keys only.
func ContextDotJoin(path []string) string {
	return strings.Join(path, ".")
}

// Envelope is the signed wire format for all documents.
// The signature is an Ed25519 signature over the raw bytes of Payload.
// No canonical JSON is required: the signer marshals the payload struct
// once with encoding/json and signs those exact bytes.
type Envelope struct {
	Payload   string `json:"payload"`   // raw JSON of the inner document
	Signature string `json:"signature"` // base64 Ed25519 signature over Payload bytes
}

// ParseDR unmarshals the payload of a DirectRelations envelope.
func (e *Envelope) ParseDR() (*DirectRelationsPayload, error) {
	var p DirectRelationsPayload
	if err := json.Unmarshal([]byte(e.Payload), &p); err != nil {
		return nil, err
	}
	return &p, nil
}

// ParseDRD unmarshals the payload of a DirectRelationsDependencies envelope.
func (e *Envelope) ParseDRD() (*DirectRelationsDependenciesPayload, error) {
	var p DirectRelationsDependenciesPayload
	if err := json.Unmarshal([]byte(e.Payload), &p); err != nil {
		return nil, err
	}
	return &p, nil
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

// DirectRelationsPayload is the inner content of a signed DirectRelations document.
type DirectRelationsPayload struct {
	Version   int            `json:"version"`
	UserID    string         `json:"user_id"`
	Timestamp int64          `json:"timestamp"`
	Contexts  []ContextEntry `json:"contexts"`
}

// HopDependencies maps hop number ("1"–"6") to the list of user peer IDs at that depth.
type HopDependencies map[string][]string

// DirectRelationsDependenciesPayload is the inner content of a signed DRD document.
// One document is produced per context in the user's DirectRelations.
type DirectRelationsDependenciesPayload struct {
	Version         int             `json:"version"`
	UserID          string          `json:"user_id"`
	Context         []string        `json:"context"`
	Hops            HopDependencies `json:"hops"`
	Sources         []string        `json:"sources"`
	SourceTimestamp int64           `json:"source_timestamp"`
	ComputedAt      int64           `json:"computed_at"`
	PeerID          string          `json:"peer_id"`
}
