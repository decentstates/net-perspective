package store

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
)

// Store is a dumb content-addressed file store.
// Each file is stored at <root>/<sha256hex> with no additional structure.
type Store struct {
	root string
}

// Open opens (or creates) a Store rooted at the given directory.
func Open(root string) (*Store, error) {
	if err := os.MkdirAll(root, 0o755); err != nil {
		return nil, fmt.Errorf("creating store dir: %w", err)
	}
	return &Store{root: root}, nil
}

// Close is a no-op.
func (s *Store) Close() error { return nil }

// Put stores data under its content address. Idempotent.
func (s *Store) Put(addr string, data []byte) error {
	path := filepath.Join(s.root, addr)
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, data, 0o644); err != nil {
		return fmt.Errorf("writing content %s: %w", addr, err)
	}
	return os.Rename(tmp, path)
}

// Get retrieves data by content address. Returns nil, nil if not found.
func (s *Store) Get(addr string) ([]byte, error) {
	data, err := os.ReadFile(filepath.Join(s.root, addr))
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return nil, nil
		}
		return nil, err
	}
	return data, nil
}
