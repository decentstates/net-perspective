package main

import (
	"context"
	"flag"
	"log"
	"net/http"
	"os"
	"os/signal"
"strings"
	"syscall"

	dht "github.com/libp2p/go-libp2p-kad-dht"
	"github.com/libp2p/go-libp2p"
	"github.com/libp2p/go-libp2p/core/peer"
	record "github.com/libp2p/go-libp2p-record"
	"github.com/multiformats/go-multiaddr"
	"github.com/net-perspective/home-peer/internal/api"
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
	usersFlag := flag.String("users", "", "Comma-separated peer IDs of homed users (used with --http-addr)")
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
		"dr":       validator.DRPointerValidator{},
		"dr-data":  validator.DRDataValidator{},
		"drd":      validator.DRDPointerValidator{},
		"drd-data": validator.DRDDataValidator{},
		"dep":      validator.DepDataValidator{},
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
	for _, addrStr := range splitComma(*bootstrapFlag) {
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


	// TODO: I think we need to set up some kind of non-http communication for sharing files if the host is behind a NAT

	// --- Home peer HTTP API (optional) ---
	if *httpAddr != "" {
		userIDs := parseUserIDs(*usersFlag)
		if len(userIDs) == 0 {
			log.Println("Warning: --http-addr set but no --users provided; no users will be homed")
		}

		s, err := store.Open(*dataDir)
		if err != nil {
			log.Fatalf("Opening store at %s: %v", *dataDir, err)
		}
		defer s.Close()

		hp, err := homepeer.New(h, d, s, privKey, userIDs)
		if err != nil {
			log.Fatalf("Creating home peer: %v", err)
		}

		srv := &http.Server{Addr: *httpAddr, Handler: api.NewServer(hp).Handler()}
		go func() {
			log.Printf("HTTP API listening on %s", *httpAddr)
			if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
				log.Fatalf("HTTP server: %v", err)
			}
		}()
		defer srv.Shutdown(context.Background())
	}

	// Will need to implement kad dhts's advertise and routing discovery

	// --- Block until signal ---
	ch := make(chan os.Signal, 1)
	signal.Notify(ch, syscall.SIGINT, syscall.SIGTERM)
	<-ch
	log.Println("Shutting down...")
}

func splitComma(s string) []string {
	var out []string
	for _, a := range strings.Split(s, ",") {
		a = strings.TrimSpace(a)
		if a != "" {
			out = append(out, a)
		}
	}
	return out
}

func parseUserIDs(s string) []peer.ID {
	var ids []peer.ID
	for _, uid := range splitComma(s) {
		pid, err := peer.Decode(uid)
		if err != nil {
			log.Printf("Invalid user peer ID %s: %v (skipping)", uid, err)
			continue
		}
		ids = append(ids, pid)
	}
	return ids
}

