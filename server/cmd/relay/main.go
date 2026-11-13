package main

import (
	"context"
	"flag"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"

	"filetransfer/relay/internal/relay"
)

func main() {
	cfg := relay.DefaultConfig()

	var addr string
	flag.StringVar(&addr, "addr", "", "Địa chỉ và cổng lắng nghe (vd: :8080 hoặc 0.0.0.0:8080)")
	flag.Parse()

	if addr == "" {
		addr = ":" + cfg.Port
	}

	hub := relay.NewHub(cfg)

	mux := http.NewServeMux()
	mux.HandleFunc("/v1/stream", relay.HandleStream(hub))
	mux.HandleFunc("/health", relay.HandleHealth(hub))

	server := &http.Server{
		Addr:         addr,
		Handler:      mux,
		ReadTimeout:  0, // Không đặt timeout toàn cục để tránh đóng kết nối WebSocket streaming
		WriteTimeout: 0, // Thời gian chờ từng gói tin được quản lý riêng trong SafeConn.WriteMessage
		IdleTimeout:  cfg.IdleTimeout,
	}

	stopChan := make(chan os.Signal, 1)
	signal.Notify(stopChan, os.Interrupt, syscall.SIGTERM)

	go func() {
		log.Println("==================================================================")
		log.Println("  filetransfer Zero-Knowledge Relay Server (Production Service)")
		log.Println("  Cam ket bao mat: Chuyen tiep RAM truc tiep, KHONG LUU TRU DIA")
		log.Println("==================================================================")
		log.Printf("[filetransfer Relay] May chu chuyen tiep dang lang nghe tai %s", addr)
		log.Println("[filetransfer Relay] Stream Endpoint : /v1/stream (WebSocket E2EE)")
		log.Println("[filetransfer Relay] Health Endpoint : /health    (HTTP JSON)")

		if err := server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatalf("[filetransfer Relay] Loi may chu: %v", err)
		}
	}()

	<-stopChan
	log.Println("[filetransfer Relay] Nhan tin hieu dung, dang tat may chu an toan (Graceful Shutdown)...")

	ctx, cancel := context.WithTimeout(context.Background(), cfg.GracefulStopTimeout)
	defer cancel()

	hub.CloseAll()
	if err := server.Shutdown(ctx); err != nil {
		log.Printf("[filetransfer Relay] Loi khi tat may chu: %v", err)
	}

	log.Println("[filetransfer Relay] May chu da dung hoan toan.")
}
