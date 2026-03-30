package validator

import (
	"encoding/base64"
	"encoding/json"
	"fmt"
	"strings"

	canonicaljson "github.com/gibson042/canonicaljson-go"
	"github.com/libp2p/go-libp2p/core/peer"
	"github.com/net-perspective/home-peer/internal/model"
)

func validateContextPaths(dr *model.DirectRelations) error {
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

// ValidateDRSignature verifies the cryptographic signature on a DirectRelations document.
func ValidateDRSignature(dr *model.DirectRelations) error {
	pid, err := peer.Decode(dr.UserID)
	if err != nil {
		return fmt.Errorf("invalid user_id peer ID: %w", err)
	}
	pubKey, err := pid.ExtractPublicKey()
	if err != nil {
		return fmt.Errorf("extracting public key from peer ID: %w", err)
	}
	sigBytes, err := base64.StdEncoding.DecodeString(dr.Signature)
	if err != nil {
		return fmt.Errorf("decoding signature: %w", err)
	}
	payload := map[string]any{
		"version":   dr.Version,
		"user_id":   dr.UserID,
		"timestamp": dr.Timestamp,
		"contexts":  dr.Contexts,
	}
	canonical, err := canonicaljson.Marshal(payload)
	if err != nil {
		return fmt.Errorf("canonical JSON for DR: %w", err)
	}
	ok, err := pubKey.Verify(canonical, sigBytes)
	if err != nil {
		return fmt.Errorf("signature verification error: %w", err)
	}
	if !ok {
		return fmt.Errorf("signature verification failed")
	}
	return nil
}

// ValidateDR validates context paths and verifies the signature on a DirectRelations document.
func ValidateDR(dr *model.DirectRelations) error {
	if err := validateContextPaths(dr); err != nil {
		return fmt.Errorf("invalid context path: %w", err)
	}
	return ValidateDRSignature(dr)
}

// DRValidator validates DirectRelations records for the /dr/ namespace.
type DRValidator struct{}

func (DRValidator) Validate(key string, value []byte) error {
	// key is "/dr/<peer-id>"
	parts := strings.SplitN(key, "/", 3)
	if len(parts) != 3 || parts[1] != "dr" {
		return fmt.Errorf("invalid dr key: %s", key)
	}
	expectedUserID := parts[2]

	var dr model.DirectRelations
	if err := json.Unmarshal(value, &dr); err != nil {
		return fmt.Errorf("unmarshal DirectRelations: %w", err)
	}

	if dr.UserID != expectedUserID {
		return fmt.Errorf("user_id %s does not match key %s", dr.UserID, expectedUserID)
	}

	return ValidateDR(&dr)
}

func (DRValidator) Select(key string, values [][]byte) (int, error) {
	best := -1
	var bestTs int64
	for i, v := range values {
		var dr model.DirectRelations
		if err := json.Unmarshal(v, &dr); err != nil {
			continue
		}
		if best == -1 || dr.Timestamp > bestTs {
			best = i
			bestTs = dr.Timestamp
		}
	}
	if best == -1 {
		return 0, fmt.Errorf("no valid DirectRelations records")
	}
	return best, nil
}

// DRDValidator validates DirectRelationsDependencies records for the /drd/ namespace.
type DRDValidator struct{}

func (DRDValidator) Validate(key string, value []byte) error {
	// key is "/drd/<peer-id>/<dot-joined-context>"
	parts := strings.SplitN(key, "/", 4)
	if len(parts) != 4 || parts[1] != "drd" {
		return fmt.Errorf("invalid drd key: %s", key)
	}
	expectedUserID := parts[2]
	expectedCtx := parts[3]

	var drd model.DirectRelationsDependencies
	if err := json.Unmarshal(value, &drd); err != nil {
		return fmt.Errorf("unmarshal DirectRelationsDependencies: %w", err)
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
		return fmt.Errorf("extracting public key from peer_id: %w", err)
	}

	sigBytes, err := base64.StdEncoding.DecodeString(drd.Signature)
	if err != nil {
		return fmt.Errorf("decoding signature: %w", err)
	}

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
		return fmt.Errorf("canonical JSON for DRD: %w", err)
	}

	ok, err := pubKey.Verify(canonical, sigBytes)
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
		var drd model.DirectRelationsDependencies
		if err := json.Unmarshal(v, &drd); err != nil {
			continue
		}
		if best == -1 || drd.ComputedAt > bestTs {
			best = i
			bestTs = drd.ComputedAt
		}
	}
	if best == -1 {
		return 0, fmt.Errorf("no valid DirectRelationsDependencies records")
	}
	return best, nil
}

// PermissiveValidator always validates and selects index 0.
type PermissiveValidator struct{}

func (PermissiveValidator) Validate(key string, value []byte) error {
	return nil
}

func (PermissiveValidator) Select(key string, values [][]byte) (int, error) {
	return 0, nil
}
