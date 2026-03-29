package main

import (
	"context"
	"flag"
	"fmt"
	"log"
	"os"
	"os/signal"
	"syscall"

	dht "github.com/libp2p/go-libp2p-kad-dht"
	"github.com/libp2p/go-libp2p"
	record "github.com/libp2p/go-libp2p-record"
	"github.com/multiformats/go-multiaddr"
	"github.com/net-perspective/home-peer/internal/identity"
	"github.com/net-perspective/home-peer/internal/validator"
)

func main() {
	keyPath := flag.String("key", "bootstrap.key", "Path to identity key file")
	addr := flag.String("addr", "/ip4/0.0.0.0/tcp/4001", "Listen address")
	flag.Parse()

	privKey, err := identity.LoadOrCreate(*keyPath)
	if err != nil {
		log.Fatalf("Loading identity: %v", err)
	}

	listenAddr, err := multiaddr.NewMultiaddr(*addr)
	if err != nil {
		log.Fatalf("Invalid listen addr: %v", err)
	}

	h, err := libp2p.New(
		libp2p.Identity(privKey),
		libp2p.ListenAddrs(listenAddr),
	)
	if err != nil {
		log.Fatalf("Creating libp2p host: %v", err)
	}

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

	if err := d.Bootstrap(ctx); err != nil {
		log.Fatalf("Bootstrapping DHT: %v", err)
	}

	fmt.Fprintf(os.Stdout, "Bootstrap node started\n")
	fmt.Fprintf(os.Stdout, "Peer ID: %s\n", h.ID())
	for _, ma := range h.Addrs() {
		fmt.Fprintf(os.Stdout, "Listening on: %s/p2p/%s\n", ma, h.ID())
	}

	// Wait for signal
	ch := make(chan os.Signal, 1)
	signal.Notify(ch, syscall.SIGINT, syscall.SIGTERM)
	<-ch
	log.Println("Shutting down...")
	_ = h.Close()
}
