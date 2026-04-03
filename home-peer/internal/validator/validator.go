package validator

import (
	"encoding/base64"
	"encoding/json"
	"fmt"
	"strings"
	"time"

	canonicaljson "github.com/gibson042/canonicaljson-go"
	"github.com/libp2p/go-libp2p/core/peer"
	"github.com/net-perspective/home-peer/internal/model"
)

// parseKey splits a DHT key like "/namespace/value" and validates the namespace.
func parseKey(key, namespace string) (string, error) {
	parts := strings.SplitN(key, "/", 3)
	if len(parts) != 3 || parts[1] != namespace {
		return "", fmt.Errorf("invalid %s key: %s", namespace, key)
	}
	return parts[2], nil
}

// verifySignature verifies that signatureB64 is a valid signature over the canonical
// JSON encoding of payload, using the public key embedded in peerIDStr.
func verifySignature(peerIDStr, signatureB64 string, payload map[string]any) error {
	pid, err := peer.Decode(peerIDStr)
	if err != nil {
		return fmt.Errorf("invalid peer ID %q: %w", peerIDStr, err)
	}
	pubKey, err := pid.ExtractPublicKey()
	if err != nil {
		return fmt.Errorf("extracting public key from peer ID: %w", err)
	}
	sigBytes, err := base64.StdEncoding.DecodeString(signatureB64)
	if err != nil {
		return fmt.Errorf("decoding signature: %w", err)
	}
	canonical, err := canonicaljson.Marshal(payload)
	if err != nil {
		return fmt.Errorf("canonical JSON: %w", err)
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

// selectLatestTimestamp picks the index of the value with the highest timestamp that
// is not greater than fetchTime. getTs extracts the timestamp from a raw value; it
// returns (0, false) when the value cannot be parsed.
func selectLatestTimestamp(values [][]byte, fetchTime int64, getTs func([]byte) (int64, bool)) (int, error) {
	best := -1
	var bestTs int64
	for i, v := range values {
		ts, ok := getTs(v)
		if !ok || ts > fetchTime {
			continue
		}
		if best == -1 || ts > bestTs {
			best = i
			bestTs = ts
		}
	}
	if best == -1 {
		return 0, fmt.Errorf("no valid records")
	}
	return best, nil
}

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
	return verifySignature(dr.UserID, dr.Signature, map[string]any{
		"version":   dr.Version,
		"user_id":   dr.UserID,
		"timestamp": dr.Timestamp,
		"contexts":  dr.Contexts,
	})
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
	return verifySignature(drd.PeerID, drd.Signature, map[string]any{
		"version":                      drd.Version,
		"user_id":                      drd.UserID,
		"dr_content_address":           drd.DRContentAddress,
		"hops":                         drd.Hops,
		"sources":                      drd.Sources,
		"dependencies_content_address": drd.DependenciesContentAddress,
		"source_timestamp":             drd.SourceTimestamp,
		"computed_at":                  drd.ComputedAt,
		"peer_id":                      drd.PeerID,
	})
}

// DRPointerValidator validates records at /dr/<user-id>: a DRPointer mapping a user to
// their current DirectRelations content address.
type DRPointerValidator struct{}

func (DRPointerValidator) Validate(key string, value []byte) error {
	return validateDRPointer(key, value, time.Now().Unix())
}

func validateDRPointer(key string, value []byte, fetchTime int64) error {
	expectedUserID, err := parseKey(key, "dr")
	if err != nil {
		return err
	}
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
	if ptr.Timestamp > fetchTime {
		return fmt.Errorf("timestamp is in the future")
	}
	return nil
}

func (DRPointerValidator) Select(_ string, values [][]byte) (int, error) {
	return selectLatestTimestamp(values, time.Now().Unix(), func(v []byte) (int64, bool) {
		var ptr model.DRPointer
		if json.Unmarshal(v, &ptr) != nil {
			return 0, false
		}
		return ptr.Timestamp, true
	})
}

// DRDataValidator validates records at /dr-data/<hash>: raw DirectRelations bytes.
// Checks that SHA-256 of the value matches the key hash and that the DR signature is valid.
type DRDataValidator struct{}

func (DRDataValidator) Validate(key string, value []byte) error {
	return validateDRData(key, value, time.Now().Unix())
}

func validateDRData(key string, value []byte, fetchTime int64) error {
	expectedAddr, err := parseKey(key, "dr-data")
	if err != nil {
		return err
	}
	if model.ContentAddr(value) != expectedAddr {
		return fmt.Errorf("content address mismatch: data does not hash to %s", expectedAddr)
	}
	var dr model.DirectRelations
	if err := json.Unmarshal(value, &dr); err != nil {
		return fmt.Errorf("unmarshal DirectRelations: %w", err)
	}
	if dr.Timestamp > fetchTime {
		return fmt.Errorf("timestamp is in the future")
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
	return validateDRDPointer(key, value, time.Now().Unix())
}

func validateDRDPointer(key string, value []byte, fetchTime int64) error {
	expectedDRAddr, err := parseKey(key, "drd")
	if err != nil {
		return err
	}
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
	if ptr.Timestamp > fetchTime {
		return fmt.Errorf("timestamp is in the future")
	}
	return nil
}

func (DRDPointerValidator) Select(_ string, values [][]byte) (int, error) {
	return selectLatestTimestamp(values, time.Now().Unix(), func(v []byte) (int64, bool) {
		var ptr model.DRDPointer
		if json.Unmarshal(v, &ptr) != nil {
			return 0, false
		}
		return ptr.Timestamp, true
	})
}

// DRDDataValidator validates records at /drd-data/<hash>: raw DirectRelationsDependencies bytes.
// Checks that SHA-256 of the value matches the key hash and that the DRD signature is valid.
type DRDDataValidator struct{}

func (DRDDataValidator) Validate(key string, value []byte) error {
	return validateDRDData(key, value, time.Now().Unix())
}

func validateDRDData(key string, value []byte, fetchTime int64) error {
	expectedAddr, err := parseKey(key, "drd-data")
	if err != nil {
		return err
	}
	if model.ContentAddr(value) != expectedAddr {
		return fmt.Errorf("content address mismatch: data does not hash to %s", expectedAddr)
	}
	var drd model.DirectRelationsDependencies
	if err := json.Unmarshal(value, &drd); err != nil {
		return fmt.Errorf("unmarshal DirectRelationsDependencies: %w", err)
	}
	if drd.ComputedAt > fetchTime {
		return fmt.Errorf("computed_at is in the future")
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

// DepDataValidator validates records at /dep/<hash>: gzip-compressed dependency bundles.
// Checks that SHA-256 of the value matches the key hash. Content integrity is guaranteed
// by the hash; authenticity is guaranteed by the DRD header signature that references this address.
type DepDataValidator struct{}

func (DepDataValidator) Validate(key string, value []byte) error {
	expectedAddr, err := parseKey(key, "dep")
	if err != nil {
		return err
	}
	if model.ContentAddr(value) != expectedAddr {
		return fmt.Errorf("content address mismatch: data does not hash to %s", expectedAddr)
	}
	return nil
}

func (DepDataValidator) Select(_ string, values [][]byte) (int, error) {
	if len(values) > 0 {
		return 0, nil
	}
	return 0, fmt.Errorf("no dep records")
}
