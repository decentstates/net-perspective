package identity

import (
	"crypto/rand"
	"fmt"
	"os"

	"github.com/libp2p/go-libp2p/core/crypto"
)

// LoadOrCreate loads an Ed25519 keypair from the given file path.
// If the file does not exist, it generates a new keypair and saves it.
func LoadOrCreate(path string) (crypto.PrivKey, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		if os.IsNotExist(err) {
			return createAndSave(path)
		}
		return nil, fmt.Errorf("reading key file %s: %w", path, err)
	}

	priv, err := crypto.UnmarshalPrivateKey(data)
	if err != nil {
		return nil, fmt.Errorf("unmarshaling private key from %s: %w", path, err)
	}
	return priv, nil
}

func createAndSave(path string) (crypto.PrivKey, error) {
	priv, _, err := crypto.GenerateEd25519Key(rand.Reader)
	if err != nil {
		return nil, fmt.Errorf("generating Ed25519 key: %w", err)
	}

	data, err := crypto.MarshalPrivateKey(priv)
	if err != nil {
		return nil, fmt.Errorf("marshaling private key: %w", err)
	}

	if err := os.WriteFile(path, data, 0600); err != nil {
		return nil, fmt.Errorf("writing key file %s: %w", path, err)
	}

	return priv, nil
}
