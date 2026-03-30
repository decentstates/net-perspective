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

// Store is a filesystem-backed store for DR, DRD, and cached DR documents.
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

// Close is a no-op; present to satisfy the same interface as before.
func (s *Store) Close() error { return nil }

// PutDR atomically writes a DirectRelations document.
func (s *Store) PutDR(userID string, dr *model.DirectRelations) error {
	return writeJSON(filepath.Join(s.root, "dr", userID+".json"), dr)
}

// GetDR reads a DirectRelations document. Returns nil, nil if not found.
func (s *Store) GetDR(userID string) (*model.DirectRelations, error) {
	var dr model.DirectRelations
	if err := readJSON(filepath.Join(s.root, "dr", userID+".json"), &dr); err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return nil, nil
		}
		return nil, err
	}
	return &dr, nil
}

// PutDRD atomically writes a per-context DirectRelationsDependencies document.
func (s *Store) PutDRD(userID string, contextPath []string, drd *model.DirectRelationsDependencies) error {
	dir := filepath.Join(s.root, "drd", userID)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return fmt.Errorf("creating drd dir: %w", err)
	}
	return writeJSON(filepath.Join(dir, model.ContextDotJoin(contextPath)+".json"), drd)
}

// GetDRD reads a per-context DirectRelationsDependencies document. Returns nil, nil if not found.
func (s *Store) GetDRD(userID string, contextPath []string) (*model.DirectRelationsDependencies, error) {
	var drd model.DirectRelationsDependencies
	path := filepath.Join(s.root, "drd", userID, model.ContextDotJoin(contextPath)+".json")
	if err := readJSON(path, &drd); err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return nil, nil
		}
		return nil, err
	}
	return &drd, nil
}

// GetAllDRDs returns all stored DRDs for a given user (one per context).
func (s *Store) GetAllDRDs(userID string) ([]*model.DirectRelationsDependencies, error) {
	dir := filepath.Join(s.root, "drd", userID)
	entries, err := os.ReadDir(dir)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return nil, nil
		}
		return nil, err
	}
	var results []*model.DirectRelationsDependencies
	for _, e := range entries {
		if e.IsDir() || !strings.HasSuffix(e.Name(), ".json") {
			continue
		}
		var drd model.DirectRelationsDependencies
		if err := readJSON(filepath.Join(dir, e.Name()), &drd); err != nil {
			return nil, err
		}
		results = append(results, &drd)
	}
	return results, nil
}

// PutCache atomically writes a cached DirectRelations document.
func (s *Store) PutCache(userID string, dr *model.DirectRelations) error {
	return writeJSON(filepath.Join(s.root, "cache", userID+".json"), dr)
}

// GetCache reads a cached DirectRelations document. Returns nil, nil if not found.
func (s *Store) GetCache(userID string) (*model.DirectRelations, error) {
	var dr model.DirectRelations
	if err := readJSON(filepath.Join(s.root, "cache", userID+".json"), &dr); err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return nil, nil
		}
		return nil, err
	}
	return &dr, nil
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

// AddUser is a no-op: users are discovered via GetUsers by scanning dr/.
func (s *Store) AddUser(_ string) error { return nil }

// writeJSON marshals v to JSON and atomically writes it to path via a temp file.
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

// readJSON reads and unmarshals a JSON file into v.
func readJSON(path string, v any) error {
	data, err := os.ReadFile(path)
	if err != nil {
		return err
	}
	return json.Unmarshal(data, v)
}
