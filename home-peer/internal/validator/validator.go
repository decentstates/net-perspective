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

// ValidateDRDSignature verifies the cryptographic signature on a DirectRelationsDependencies document.
func ValidateDRDSignature(drd *model.DirectRelationsDependencies) error {
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

// DRPointerValidator validates records at /dr/<user-id>: a DRPointer mapping a user to
// their current DirectRelations content address.
type DRPointerValidator struct{}

func (DRPointerValidator) Validate(key string, value []byte) error {
	parts := strings.SplitN(key, "/", 3)
	if len(parts) != 3 || parts[1] != "dr" {
		return fmt.Errorf("invalid dr key: %s", key)
	}
	expectedUserID := parts[2]

	var ptr model.DRPointer
	if err := json.Unmarshal(value, &ptr); err != nil {
		return fmt.Errorf("unmarshal DRPointer: %w", err)
	}
	if ptr.UserID != expectedUserID {
		return fmt.Errorf("user_id %s does not match key %s", ptr.UserID, expectedUserID)
	}
	if ptr.ContentAddress == "" {
		return fmt.Errorf("content_address is empty")
	}
	return nil
}

func (DRPointerValidator) Select(_ string, values [][]byte) (int, error) {
	best := -1
	var bestTs int64
	for i, v := range values {
		var ptr model.DRPointer
		if err := json.Unmarshal(v, &ptr); err != nil {
			continue
		}
		if best == -1 || ptr.Timestamp > bestTs {
			best = i
			bestTs = ptr.Timestamp
		}
	}
	if best == -1 {
		return 0, fmt.Errorf("no valid DRPointer records")
	}
	return best, nil
}

// DRDataValidator validates records at /dr-data/<hash>: raw DirectRelations bytes.
// Checks that SHA-256 of the value matches the key hash and that the DR signature is valid.
type DRDataValidator struct{}

func (DRDataValidator) Validate(key string, value []byte) error {
	parts := strings.SplitN(key, "/", 3)
	if len(parts) != 3 || parts[1] != "dr-data" {
		return fmt.Errorf("invalid dr-data key: %s", key)
	}
	expectedAddr := parts[2]
	if model.ContentAddr(value) != expectedAddr {
		return fmt.Errorf("content address mismatch: data does not hash to %s", expectedAddr)
	}
	var dr model.DirectRelations
	if err := json.Unmarshal(value, &dr); err != nil {
		return fmt.Errorf("unmarshal DirectRelations: %w", err)
	}
	return ValidateDR(&dr)
}

func (DRDataValidator) Select(_ string, values [][]byte) (int, error) {
	// Content-addressed: all valid values are identical; return any valid one.
	for i, v := range values {
		var dr model.DirectRelations
		if json.Unmarshal(v, &dr) == nil {
			return i, nil
		}
	}
	return 0, fmt.Errorf("no valid DR data records")
}

// DRDPointerValidator validates records at /drd/<dr-content-address>: a DRDPointer mapping
// a DR content address to its computed DRD content address.
type DRDPointerValidator struct{}

func (DRDPointerValidator) Validate(key string, value []byte) error {
	parts := strings.SplitN(key, "/", 3)
	if len(parts) != 3 || parts[1] != "drd" {
		return fmt.Errorf("invalid drd key: %s", key)
	}
	expectedDRAddr := parts[2]

	var ptr model.DRDPointer
	if err := json.Unmarshal(value, &ptr); err != nil {
		return fmt.Errorf("unmarshal DRDPointer: %w", err)
	}
	if ptr.DRContentAddress != expectedDRAddr {
		return fmt.Errorf("dr_content_address %s does not match key %s", ptr.DRContentAddress, expectedDRAddr)
	}
	if ptr.DRDContentAddress == "" {
		return fmt.Errorf("drd_content_address is empty")
	}
	return nil
}

func (DRDPointerValidator) Select(_ string, values [][]byte) (int, error) {
	best := -1
	var bestTs int64
	for i, v := range values {
		var ptr model.DRDPointer
		if err := json.Unmarshal(v, &ptr); err != nil {
			continue
		}
		if best == -1 || ptr.Timestamp > bestTs {
			best = i
			bestTs = ptr.Timestamp
		}
	}
	if best == -1 {
		return 0, fmt.Errorf("no valid DRDPointer records")
	}
	return best, nil
}

// DRDDataValidator validates records at /drd-data/<hash>: raw DirectRelationsDependencies bytes.
// Checks that SHA-256 of the value matches the key hash and that the DRD signature is valid.
type DRDDataValidator struct{}

func (DRDDataValidator) Validate(key string, value []byte) error {
	parts := strings.SplitN(key, "/", 3)
	if len(parts) != 3 || parts[1] != "drd-data" {
		return fmt.Errorf("invalid drd-data key: %s", key)
	}
	expectedAddr := parts[2]
	if model.ContentAddr(value) != expectedAddr {
		return fmt.Errorf("content address mismatch: data does not hash to %s", expectedAddr)
	}
	var drd model.DirectRelationsDependencies
	if err := json.Unmarshal(value, &drd); err != nil {
		return fmt.Errorf("unmarshal DirectRelationsDependencies: %w", err)
	}
	return ValidateDRDSignature(&drd)
}

func (DRDDataValidator) Select(_ string, values [][]byte) (int, error) {
	// Content-addressed: all valid values are identical; return any valid one.
	for i, v := range values {
		var drd model.DirectRelationsDependencies
		if json.Unmarshal(v, &drd) == nil {
			return i, nil
		}
	}
	return 0, fmt.Errorf("no valid DRD data records")
}
