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
	libp2p "github.com/libp2p/go-libp2p"
	"github.com/libp2p/go-libp2p/core/peer"
	record "github.com/libp2p/go-libp2p-record"
	"github.com/multiformats/go-multiaddr"
	"github.com/net-perspective/home-peer/internal/homepeer"
	"github.com/net-perspective/home-peer/internal/identity"
	"github.com/net-perspective/home-peer/internal/store"
	"github.com/net-perspective/home-peer/internal/validator"
)

func main() {
	keyPath := flag.String("key", "home-peer.key", "Path to identity key file")
	bootstrapFlag := flag.String("bootstrap", "", "Comma-separated bootstrap multiaddrs")
	httpAddr := flag.String("http-addr", ":8080", "HTTP listen address")
	dataDir := flag.String("data-dir", "home-peer-data", "Badger data directory")
	flag.Parse()

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

	s, err := store.Open(*dataDir)
	if err != nil {
		log.Fatalf("Opening store at %s: %v", *dataDir, err)
	}
	defer s.Close()

	hp, err := homepeer.New(h, d, s, privKey)
	if err != nil {
		log.Fatalf("Creating home peer: %v", err)
	}

	srv := &http.Server{
		Addr:    *httpAddr,
		Handler: hp.Handler(),
	}

	go func() {
		log.Printf("HTTP API listening on %s", *httpAddr)
		log.Printf("Peer ID: %s", h.ID())
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatalf("HTTP server: %v", err)
		}
	}()

	ch := make(chan os.Signal, 1)
	signal.Notify(ch, syscall.SIGINT, syscall.SIGTERM)
	<-ch
	log.Println("Shutting down...")
	_ = srv.Shutdown(context.Background())
}
