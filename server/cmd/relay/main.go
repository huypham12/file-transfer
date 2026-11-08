package main

import (
	"context"
	"flag"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"teamshare/relay/internal/relay"
)

func main() {
	var addr string
	flag.StringVar(&addr, "addr", "", "Địa chỉ và cổng lắng nghe (vd: :8080 hoặc 0.0.0.0:8080)")
	flag.Parse()

	if addr == "" {
		port := os.Getenv("PORT")
		if port == "" {
			port = "8080"
		}
		addr = ":" + port
	}

	hub := relay.NewHub()

	mux := http.NewServeMux()
	mux.HandleFunc("/v1/stream", relay.HandleStream(hub))
	mux.HandleFunc("/health", relay.HandleHealth(hub))

	server := &http.Server{
		Addr:         addr,
		Handler:      mux,
		ReadTimeout:  30 * time.Second,
		WriteTimeout: 30 * time.Second,
		IdleTimeout:  120 * time.Second,
	}

	stopChan := make(chan os.Signal, 1)
	signal.Notify(stopChan, os.Interrupt, syscall.SIGTERM)

	go func() {
		log.Println("==================================================================")
		log.Println("  TeamShare Zero-Knowledge Relay Server (Production Service)")
		log.Println("  Cam ket bao mat: Chuyen tiep RAM truc tiep, KHONG LUU TRU DIA")
		log.Println("==================================================================")
		log.Printf("[TeamShare Relay] May chu chuyen tiep dang lang nghe tai %s", addr)
		log.Println("[TeamShare Relay] Stream Endpoint : /v1/stream (WebSocket E2EE)")
		log.Println("[TeamShare Relay] Health Endpoint : /health    (HTTP JSON)")

		if err := server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatalf("[TeamShare Relay] Loi may chu: %v", err)
		}
	}()

	<-stopChan
	log.Println("[TeamShare Relay] Nhan tin hieu dung, dang tat may chu an toan (Graceful Shutdown)...")

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	hub.CloseAll()
	if err := server.Shutdown(ctx); err != nil {
		log.Printf("[TeamShare Relay] Loi khi tat may chu: %v", err)
	}

	log.Println("[TeamShare Relay] May chu da dung hoan toan.")
}
