package model

// Relation represents a single relation (link or leaf).
type Relation struct {
	Type          string         `json:"type"`
	TargetUser    string         `json:"target_user,omitempty"`
	TargetContext []string       `json:"target_context,omitempty"`
	URL           string         `json:"url,omitempty"`
	Properties    map[string]any `json:"properties"`
}

// DirectRelations is the primary document stored per user in the DHT.
// DHT key: /dr/<peer-id-string>
type DirectRelations struct {
	Version   int                  `json:"version"`
	UserID    string               `json:"user_id"`
	Timestamp int64                `json:"timestamp"`
	Contexts  map[string][]Relation `json:"contexts"`
	Signature string               `json:"signature"`
}

// HopDependencies maps context strings to lists of user peer IDs.
type HopDependencies map[string][]string

// DirectRelationsDependencies is stored per user, signed by the home peer.
// DHT key: /drd/<hex(sha256("dep:"+user-id+":"+source-timestamp))>
type DirectRelationsDependencies struct {
	Version         int                        `json:"version"`
	UserID          string                     `json:"user_id"`
	Dependencies    map[string]HopDependencies `json:"dependencies"`
	Sources         []string                   `json:"sources"`
	SourceTimestamp int64                      `json:"source_timestamp"`
	ComputedAt      int64                      `json:"computed_at"`
	PeerID          string                     `json:"peer_id"`
	Signature       string                     `json:"signature"`
}
