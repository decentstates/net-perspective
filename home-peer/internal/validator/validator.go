package validator

import (
	"encoding/base64"
	"encoding/json"
	"fmt"
	"strings"

	"github.com/libp2p/go-libp2p/core/peer"
	"github.com/net-perspective/home-peer/internal/model"
)

// DRValidator validates DirectRelations envelopes for the /dr/ namespace.
// The DHT value is a JSON-encoded model.Envelope. The signature covers the
// exact bytes of the Payload string — no canonical JSON required.
type DRValidator struct{}

func (DRValidator) Validate(key string, value []byte) error {
	parts := strings.SplitN(key, "/", 3)
	if len(parts) != 3 || parts[1] != "dr" {
		return fmt.Errorf("invalid dr key: %s", key)
	}
	expectedUserID := parts[2]

	var env model.Envelope
	if err := json.Unmarshal(value, &env); err != nil {
		return fmt.Errorf("unmarshal DR envelope: %w", err)
	}

	dr, err := env.ParseDR()
	if err != nil {
		return fmt.Errorf("parsing DR payload: %w", err)
	}

	if dr.UserID != expectedUserID {
		return fmt.Errorf("user_id %s does not match key %s", dr.UserID, expectedUserID)
	}

	if err := validateContextPaths(dr); err != nil {
		return fmt.Errorf("invalid context path: %w", err)
	}

	pid, err := peer.Decode(dr.UserID)
	if err != nil {
		return fmt.Errorf("invalid user_id: %w", err)
	}
	pubKey, err := pid.ExtractPublicKey()
	if err != nil {
		return fmt.Errorf("extracting public key: %w", err)
	}
	sigBytes, err := base64.StdEncoding.DecodeString(env.Signature)
	if err != nil {
		return fmt.Errorf("decoding signature: %w", err)
	}

	ok, err := pubKey.Verify([]byte(env.Payload), sigBytes)
	if err != nil {
		return fmt.Errorf("signature verification error: %w", err)
	}
	if !ok {
		return fmt.Errorf("signature verification failed")
	}
	return nil
}

func (DRValidator) Select(key string, values [][]byte) (int, error) {
	best := -1
	var bestTs int64
	for i, v := range values {
		var env model.Envelope
		if err := json.Unmarshal(v, &env); err != nil {
			continue
		}
		dr, err := env.ParseDR()
		if err != nil {
			continue
		}
		if best == -1 || dr.Timestamp > bestTs {
			best = i
			bestTs = dr.Timestamp
		}
	}
	if best == -1 {
		return 0, fmt.Errorf("no valid DR records")
	}
	return best, nil
}

// DRDValidator validates DirectRelationsDependencies envelopes for the /drd/ namespace.
type DRDValidator struct{}

func (DRDValidator) Validate(key string, value []byte) error {
	// key is "/drd/<peer-id>/<dot-joined-context>"
	parts := strings.SplitN(key, "/", 4)
	if len(parts) != 4 || parts[1] != "drd" {
		return fmt.Errorf("invalid drd key: %s", key)
	}
	expectedUserID := parts[2]
	expectedCtx := parts[3]

	var env model.Envelope
	if err := json.Unmarshal(value, &env); err != nil {
		return fmt.Errorf("unmarshal DRD envelope: %w", err)
	}

	drd, err := env.ParseDRD()
	if err != nil {
		return fmt.Errorf("parsing DRD payload: %w", err)
	}

	if drd.UserID != expectedUserID {
		return fmt.Errorf("user_id %s does not match key %s", drd.UserID, expectedUserID)
	}
	if model.ContextDotJoin(drd.Context) != expectedCtx {
		return fmt.Errorf("context %v does not match key segment %s", drd.Context, expectedCtx)
	}

	pid, err := peer.Decode(drd.PeerID)
	if err != nil {
		return fmt.Errorf("invalid peer_id: %w", err)
	}
	pubKey, err := pid.ExtractPublicKey()
	if err != nil {
		return fmt.Errorf("extracting public key: %w", err)
	}
	sigBytes, err := base64.StdEncoding.DecodeString(env.Signature)
	if err != nil {
		return fmt.Errorf("decoding signature: %w", err)
	}

	ok, err := pubKey.Verify([]byte(env.Payload), sigBytes)
	if err != nil {
		return fmt.Errorf("signature verification error: %w", err)
	}
	if !ok {
		return fmt.Errorf("signature verification failed")
	}
	return nil
}

func (DRDValidator) Select(key string, values [][]byte) (int, error) {
	best := -1
	var bestTs int64
	for i, v := range values {
		var env model.Envelope
		if err := json.Unmarshal(v, &env); err != nil {
			continue
		}
		drd, err := env.ParseDRD()
		if err != nil {
			continue
		}
		if best == -1 || drd.ComputedAt > bestTs {
			best = i
			bestTs = drd.ComputedAt
		}
	}
	if best == -1 {
		return 0, fmt.Errorf("no valid DRD records")
	}
	return best, nil
}

// PermissiveValidator always validates and selects index 0.
type PermissiveValidator struct{}

func (PermissiveValidator) Validate(string, []byte) error        { return nil }
func (PermissiveValidator) Select(_ string, _ [][]byte) (int, error) { return 0, nil }

func validateContextPaths(dr *model.DirectRelationsPayload) error {
	for _, entry := range dr.Contexts {
		if err := model.ValidateContextPath(entry.Path); err != nil {
			return err
		}
		for _, rel := range entry.Relations {
			if rel.Type == "link" && len(rel.TargetContext) > 0 {
				if err := model.ValidateContextPath(rel.TargetContext); err != nil {
					return fmt.Errorf("target_context: %w", err)
				}
			}
		}
	}
	return nil
}
