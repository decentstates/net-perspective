package main

import (
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"os"
	"strings"
	"time"

	dht "github.com/libp2p/go-libp2p-kad-dht"
	libp2p "github.com/libp2p/go-libp2p"
	"github.com/libp2p/go-libp2p/core/peer"
	record "github.com/libp2p/go-libp2p-record"
	"github.com/multiformats/go-multiaddr"
	"github.com/net-perspective/home-peer/internal/identity"
	"github.com/net-perspective/home-peer/internal/validator"
)

func main() {
	keyPath := flag.String("key", "peer.key", "Path to identity key file")
	bootstrapFlag := flag.String("bootstrap", "", "Comma-separated bootstrap multiaddrs")
	flag.Parse()

	args := flag.Args()
	if len(args) == 0 {
		fmt.Fprintln(os.Stderr, "Usage: peer [flags] <subcommand> [args]")
		fmt.Fprintln(os.Stderr, "Subcommands:")
		fmt.Fprintln(os.Stderr, "  put <key> <value>        Put a value under /myapp/<key>")
		fmt.Fprintln(os.Stderr, "  get <key>                Get a value from /myapp/<key>")
		fmt.Fprintln(os.Stderr, "  put-dr <json-file>       Put a signed DirectRelations document")
		os.Exit(1)
	}

	privKey, err := identity.LoadOrCreate(*keyPath)
	if err != nil {
		log.Fatalf("Loading identity: %v", err)
	}

	h, err := libp2p.New(libp2p.Identity(privKey))
	if err != nil {
		log.Fatalf("Creating libp2p host: %v", err)
	}
	defer h.Close()

	ns := record.NamespacedValidator{
		"dr":    validator.DRValidator{},
		"drd":   validator.DRDValidator{},
		"myapp": validator.PermissiveValidator{},
	}

	ctx := context.Background()
	d, err := dht.New(ctx, h,
		dht.Mode(dht.ModeServer),
		dht.Validator(ns),
	)
	if err != nil {
		log.Fatalf("Creating DHT: %v", err)
	}

	// Connect to bootstrap nodes
	if *bootstrapFlag != "" {
		for _, addrStr := range strings.Split(*bootstrapFlag, ",") {
			addrStr = strings.TrimSpace(addrStr)
			if addrStr == "" {
				continue
			}
			ma, err := multiaddr.NewMultiaddr(addrStr)
			if err != nil {
				log.Printf("Invalid bootstrap addr %s: %v", addrStr, err)
				continue
			}
			ai, err := peer.AddrInfoFromP2pAddr(ma)
			if err != nil {
				log.Printf("Parsing bootstrap addr %s: %v", addrStr, err)
				continue
			}
			if err := h.Connect(ctx, *ai); err != nil {
				log.Printf("Connecting to bootstrap %s: %v", addrStr, err)
			} else {
				log.Printf("Connected to bootstrap %s", addrStr)
			}
		}
	}

	if err := d.Bootstrap(ctx); err != nil {
		log.Fatalf("Bootstrapping DHT: %v", err)
	}

	// Allow some time for routing table to populate
	time.Sleep(500 * time.Millisecond)

	subcommand := args[0]
	switch subcommand {
	case "put":
		if len(args) < 3 {
			log.Fatalf("put requires <key> <value>")
		}
		key := "/myapp/" + args[1]
		value := []byte(args[2])
		putCtx, cancel := context.WithTimeout(ctx, 30*time.Second)
		defer cancel()
		if err := d.PutValue(putCtx, key, value); err != nil {
			log.Fatalf("PutValue %s: %v", key, err)
		}
		fmt.Printf("Put key=%s\n", key)

	case "get":
		if len(args) < 2 {
			log.Fatalf("get requires <key>")
		}
		key := "/myapp/" + args[1]
		getCtx, cancel := context.WithTimeout(ctx, 30*time.Second)
		defer cancel()
		val, err := d.GetValue(getCtx, key)
		if err != nil {
			log.Fatalf("GetValue %s: %v", key, err)
		}
		fmt.Printf("%s\n", val)

	case "put-dr":
		if len(args) < 2 {
			log.Fatalf("put-dr requires <json-file>")
		}
		data, err := os.ReadFile(args[1])
		if err != nil {
			log.Fatalf("Reading file %s: %v", args[1], err)
		}
		var tmp struct {
			UserID string `json:"user_id"`
		}
		if err := json.Unmarshal(data, &tmp); err != nil {
			log.Fatalf("Parsing JSON: %v", err)
		}
		key := "/dr/" + tmp.UserID
		putCtx, cancel := context.WithTimeout(ctx, 30*time.Second)
		defer cancel()
		if err := d.PutValue(putCtx, key, data); err != nil {
			log.Fatalf("PutValue %s: %v", key, err)
		}
		fmt.Printf("Put DR for user_id=%s\n", tmp.UserID)

	default:
		log.Fatalf("Unknown subcommand: %s", subcommand)
	}
}
