package main

import (
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	dht "github.com/libp2p/go-libp2p-kad-dht"
	"github.com/libp2p/go-libp2p"
	"github.com/libp2p/go-libp2p/core/peer"
	record "github.com/libp2p/go-libp2p-record"
	"github.com/multiformats/go-multiaddr"
	"github.com/net-perspective/home-peer/internal/homepeer"
	"github.com/net-perspective/home-peer/internal/identity"
	"github.com/net-perspective/home-peer/internal/store"
	"github.com/net-perspective/home-peer/internal/validator"
)

func main() {
	keyPath := flag.String("key", "peer.key", "Path to identity key file")
	listenAddr := flag.String("addr", "/ip4/0.0.0.0/tcp/4001", "libp2p listen address")
	bootstrapFlag := flag.String("bootstrap", "", "Comma-separated bootstrap peer multiaddrs")
	httpAddr := flag.String("http-addr", "", "Enable home peer HTTP API on this address (e.g. :8080)")
	dataDir := flag.String("data-dir", "peer-data", "Data directory for home peer storage (used with --http-addr)")
	flag.Parse()

	// --- Identity ---
	privKey, err := identity.LoadOrCreate(*keyPath)
	if err != nil {
		log.Fatalf("Loading identity: %v", err)
	}

	// --- libp2p host ---
	ma, err := multiaddr.NewMultiaddr(*listenAddr)
	if err != nil {
		log.Fatalf("Invalid listen addr: %v", err)
	}
	h, err := libp2p.New(
		libp2p.Identity(privKey),
		libp2p.ListenAddrs(ma),
	)
	if err != nil {
		log.Fatalf("Creating libp2p host: %v", err)
	}
	defer h.Close()

	// --- DHT (always server mode) ---
	ns := record.NamespacedValidator{
		"dr":    validator.DRValidator{},
		"drd":   validator.DRDValidator{},
		"myapp": validator.PermissiveValidator{},
	}
	ctx := context.Background()
	d, err := dht.New(ctx, h,
		dht.Mode(dht.ModeServer),
		dht.ProtocolPrefix("/net-perspective"),
		dht.Validator(ns),
	)
	if err != nil {
		log.Fatalf("Creating DHT: %v", err)
	}

	// --- Connect to bootstrap peers ---
	for _, addrStr := range splitAddrs(*bootstrapFlag) {
		bma, err := multiaddr.NewMultiaddr(addrStr)
		if err != nil {
			log.Printf("Invalid bootstrap addr %s: %v", addrStr, err)
			continue
		}
		ai, err := peer.AddrInfoFromP2pAddr(bma)
		if err != nil {
			log.Printf("Parsing bootstrap addr %s: %v", addrStr, err)
			continue
		}
		if err := h.Connect(ctx, *ai); err != nil {
			log.Printf("Connecting to %s: %v", addrStr, err)
		} else {
			log.Printf("Connected to %s", addrStr)
		}
	}

	if err := d.Bootstrap(ctx); err != nil {
		log.Fatalf("Bootstrapping DHT: %v", err)
	}

	log.Printf("Peer ID: %s", h.ID())
	for _, a := range h.Addrs() {
		log.Printf("Listening on: %s/p2p/%s", a, h.ID())
	}

	// --- One-shot CLI subcommands (put/get/put-dr) ---
	if args := flag.Args(); len(args) > 0 {
		time.Sleep(500 * time.Millisecond) // let routing table settle
		runSubcommand(ctx, d, args)
		return
	}

	// --- Home peer HTTP API (optional) ---
	if *httpAddr != "" {
		s, err := store.Open(*dataDir)
		if err != nil {
			log.Fatalf("Opening store at %s: %v", *dataDir, err)
		}
		defer s.Close()

		hp, err := homepeer.New(h, d, s, privKey)
		if err != nil {
			log.Fatalf("Creating home peer: %v", err)
		}

		srv := &http.Server{Addr: *httpAddr, Handler: hp.Handler()}
		go func() {
			log.Printf("HTTP API listening on %s", *httpAddr)
			if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
				log.Fatalf("HTTP server: %v", err)
			}
		}()
		defer srv.Shutdown(context.Background())
	}

	// --- Block until signal ---
	ch := make(chan os.Signal, 1)
	signal.Notify(ch, syscall.SIGINT, syscall.SIGTERM)
	<-ch
	log.Println("Shutting down...")
}

func splitAddrs(s string) []string {
	var out []string
	for _, a := range strings.Split(s, ",") {
		a = strings.TrimSpace(a)
		if a != "" {
			out = append(out, a)
		}
	}
	return out
}

func runSubcommand(ctx context.Context, d *dht.IpfsDHT, args []string) {
	switch args[0] {
	case "put":
		if len(args) < 3 {
			log.Fatalf("put requires <key> <value>")
		}
		key := "/myapp/" + args[1]
		c, cancel := context.WithTimeout(ctx, 30*time.Second)
		defer cancel()
		if err := d.PutValue(c, key, []byte(args[2])); err != nil {
			log.Fatalf("PutValue %s: %v", key, err)
		}
		fmt.Printf("Put key=%s\n", key)

	case "get":
		if len(args) < 2 {
			log.Fatalf("get requires <key>")
		}
		key := "/myapp/" + args[1]
		c, cancel := context.WithTimeout(ctx, 30*time.Second)
		defer cancel()
		val, err := d.GetValue(c, key)
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
			log.Fatalf("Reading file: %v", err)
		}
		var tmp struct {
			UserID string `json:"user_id"`
		}
		if err := json.Unmarshal(data, &tmp); err != nil {
			log.Fatalf("Parsing JSON: %v", err)
		}
		key := "/dr/" + tmp.UserID
		c, cancel := context.WithTimeout(ctx, 30*time.Second)
		defer cancel()
		if err := d.PutValue(c, key, data); err != nil {
			log.Fatalf("PutValue %s: %v", key, err)
		}
		fmt.Printf("Put DR for user_id=%s\n", tmp.UserID)

	default:
		log.Fatalf("Unknown subcommand: %s (available: put, get, put-dr)", args[0])
	}
}
