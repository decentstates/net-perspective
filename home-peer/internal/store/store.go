package store

import (
	"encoding/json"
	"fmt"
	"strings"

	badger "github.com/dgraph-io/badger/v4"
	"github.com/net-perspective/home-peer/internal/model"
)

const (
	prefixDR    = "dr:"
	prefixDRD   = "drd:"
	prefixCache = "cache:"
	keyUsers    = "meta:users"
)

// drdKey returns the local storage key for a per-context DRD.
// Format: drd:<peer-id>:<dot-joined-context>
func drdKey(userID string, contextPath []string) string {
	return prefixDRD + userID + ":" + model.ContextDotJoin(contextPath)
}

// Store wraps a Badger database with typed helpers.
type Store struct {
	db *badger.DB
}

// Open opens (or creates) a Badger database at the given path.
func Open(path string) (*Store, error) {
	opts := badger.DefaultOptions(path).WithLogger(nil)
	db, err := badger.Open(opts)
	if err != nil {
		return nil, fmt.Errorf("opening badger at %s: %w", path, err)
	}
	return &Store{db: db}, nil
}

// Close closes the underlying database.
func (s *Store) Close() error {
	return s.db.Close()
}

// PutDR stores a DirectRelations document keyed by peer ID.
func (s *Store) PutDR(userID string, dr *model.DirectRelations) error {
	data, err := json.Marshal(dr)
	if err != nil {
		return err
	}
	return s.db.Update(func(txn *badger.Txn) error {
		return txn.Set([]byte(prefixDR+userID), data)
	})
}

// GetDR retrieves a DirectRelations document by peer ID.
func (s *Store) GetDR(userID string) (*model.DirectRelations, error) {
	var dr model.DirectRelations
	err := s.db.View(func(txn *badger.Txn) error {
		item, err := txn.Get([]byte(prefixDR + userID))
		if err != nil {
			return err
		}
		return item.Value(func(val []byte) error {
			return json.Unmarshal(val, &dr)
		})
	})
	if err == badger.ErrKeyNotFound {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return &dr, nil
}

// PutDRD stores a per-context DirectRelationsDependencies document.
func (s *Store) PutDRD(userID string, contextPath []string, drd *model.DirectRelationsDependencies) error {
	data, err := json.Marshal(drd)
	if err != nil {
		return err
	}
	return s.db.Update(func(txn *badger.Txn) error {
		return txn.Set([]byte(drdKey(userID, contextPath)), data)
	})
}

// GetDRD retrieves a per-context DirectRelationsDependencies document.
func (s *Store) GetDRD(userID string, contextPath []string) (*model.DirectRelationsDependencies, error) {
	var drd model.DirectRelationsDependencies
	err := s.db.View(func(txn *badger.Txn) error {
		item, err := txn.Get([]byte(drdKey(userID, contextPath)))
		if err != nil {
			return err
		}
		return item.Value(func(val []byte) error {
			return json.Unmarshal(val, &drd)
		})
	})
	if err == badger.ErrKeyNotFound {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return &drd, nil
}

// GetAllDRDs returns all stored DRDs for a given user (one per context).
func (s *Store) GetAllDRDs(userID string) ([]*model.DirectRelationsDependencies, error) {
	prefix := []byte(prefixDRD + userID + ":")
	var results []*model.DirectRelationsDependencies

	err := s.db.View(func(txn *badger.Txn) error {
		opts := badger.DefaultIteratorOptions
		opts.Prefix = prefix
		it := txn.NewIterator(opts)
		defer it.Close()
		for it.Rewind(); it.Valid(); it.Next() {
			var drd model.DirectRelationsDependencies
			if err := it.Item().Value(func(val []byte) error {
				return json.Unmarshal(val, &drd)
			}); err != nil {
				return err
			}
			results = append(results, &drd)
		}
		return nil
	})
	return results, err
}

// PutCache stores a cached DirectRelations document for use in dep computations.
func (s *Store) PutCache(userID string, dr *model.DirectRelations) error {
	data, err := json.Marshal(dr)
	if err != nil {
		return err
	}
	return s.db.Update(func(txn *badger.Txn) error {
		return txn.Set([]byte(prefixCache+userID), data)
	})
}

// GetCache retrieves a cached DirectRelations document by peer ID.
func (s *Store) GetCache(userID string) (*model.DirectRelations, error) {
	var dr model.DirectRelations
	err := s.db.View(func(txn *badger.Txn) error {
		item, err := txn.Get([]byte(prefixCache + userID))
		if err != nil {
			return err
		}
		return item.Value(func(val []byte) error {
			return json.Unmarshal(val, &dr)
		})
	})
	if err == badger.ErrKeyNotFound {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return &dr, nil
}

// AddUser adds a user peer ID to the meta:users list (idempotent).
func (s *Store) AddUser(userID string) error {
	return s.db.Update(func(txn *badger.Txn) error {
		existing, err := getUsersInTxn(txn)
		if err != nil {
			return err
		}
		for _, u := range existing {
			if u == userID {
				return nil
			}
		}
		existing = append(existing, userID)
		return txn.Set([]byte(keyUsers), []byte(strings.Join(existing, "\n")))
	})
}

// GetUsers returns all homed user peer IDs.
func (s *Store) GetUsers() ([]string, error) {
	var users []string
	err := s.db.View(func(txn *badger.Txn) error {
		var err error
		users, err = getUsersInTxn(txn)
		return err
	})
	return users, err
}

func getUsersInTxn(txn *badger.Txn) ([]string, error) {
	item, err := txn.Get([]byte(keyUsers))
	if err == badger.ErrKeyNotFound {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	var raw string
	if err := item.Value(func(val []byte) error {
		raw = string(val)
		return nil
	}); err != nil {
		return nil, err
	}
	if raw == "" {
		return nil, nil
	}
	return strings.Split(raw, "\n"), nil
}
