package store

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"github.com/net-perspective/home-peer/internal/model"
)

// Store is a filesystem-backed store for DR, DRD, and cached DR envelopes.
//
// Directory layout:
//
//	<root>/dr/<peer-id>.json
//	<root>/drd/<peer-id>/<dot-joined-context>.json
//	<root>/cache/<peer-id>.json
type Store struct {
	root string
}

// Open opens (or creates) a Store rooted at the given directory.
func Open(root string) (*Store, error) {
	for _, sub := range []string{"dr", "drd", "cache"} {
		if err := os.MkdirAll(filepath.Join(root, sub), 0o755); err != nil {
			return nil, fmt.Errorf("creating store dir %s: %w", sub, err)
		}
	}
	return &Store{root: root}, nil
}

// Close is a no-op.
func (s *Store) Close() error { return nil }

// PutDR atomically writes a signed DR envelope.
func (s *Store) PutDR(userID string, env model.Envelope) error {
	return writeJSON(filepath.Join(s.root, "dr", userID+".json"), env)
}

// GetDR reads a signed DR envelope. Returns nil, nil if not found.
func (s *Store) GetDR(userID string) (*model.Envelope, error) {
	return readEnvelope(filepath.Join(s.root, "dr", userID+".json"))
}

// PutDRD atomically writes a signed DRD envelope for a specific context.
func (s *Store) PutDRD(userID string, contextPath []string, env model.Envelope) error {
	dir := filepath.Join(s.root, "drd", userID)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return fmt.Errorf("creating drd dir: %w", err)
	}
	return writeJSON(filepath.Join(dir, model.ContextDotJoin(contextPath)+".json"), env)
}

// GetDRD reads a signed DRD envelope for a specific context. Returns nil, nil if not found.
func (s *Store) GetDRD(userID string, contextPath []string) (*model.Envelope, error) {
	return readEnvelope(filepath.Join(s.root, "drd", userID, model.ContextDotJoin(contextPath)+".json"))
}

// GetAllDRDs returns all stored DRD envelopes for a given user (one per context).
func (s *Store) GetAllDRDs(userID string) ([]model.Envelope, error) {
	dir := filepath.Join(s.root, "drd", userID)
	entries, err := os.ReadDir(dir)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return nil, nil
		}
		return nil, err
	}
	var results []model.Envelope
	for _, e := range entries {
		if e.IsDir() || !strings.HasSuffix(e.Name(), ".json") {
			continue
		}
		env, err := readEnvelope(filepath.Join(dir, e.Name()))
		if err != nil {
			return nil, err
		}
		results = append(results, *env)
	}
	return results, nil
}

// PutCache atomically writes a cached DR envelope.
func (s *Store) PutCache(userID string, env model.Envelope) error {
	return writeJSON(filepath.Join(s.root, "cache", userID+".json"), env)
}

// GetCache reads a cached DR envelope. Returns nil, nil if not found.
func (s *Store) GetCache(userID string) (*model.Envelope, error) {
	return readEnvelope(filepath.Join(s.root, "cache", userID+".json"))
}

// GetUsers returns the peer IDs of all homed users by scanning the dr/ directory.
func (s *Store) GetUsers() ([]string, error) {
	entries, err := os.ReadDir(filepath.Join(s.root, "dr"))
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return nil, nil
		}
		return nil, err
	}
	var users []string
	for _, e := range entries {
		if e.IsDir() || !strings.HasSuffix(e.Name(), ".json") {
			continue
		}
		users = append(users, strings.TrimSuffix(e.Name(), ".json"))
	}
	return users, nil
}

// AddUser is a no-op: the user list is derived from the dr/ directory.
func (s *Store) AddUser(_ string) error { return nil }

func readEnvelope(path string) (*model.Envelope, error) {
	var env model.Envelope
	data, err := os.ReadFile(path)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return nil, nil
		}
		return nil, err
	}
	if err := json.Unmarshal(data, &env); err != nil {
		return nil, err
	}
	return &env, nil
}

func writeJSON(path string, v any) error {
	data, err := json.Marshal(v)
	if err != nil {
		return err
	}
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, data, 0o644); err != nil {
		return err
	}
	return os.Rename(tmp, path)
}
