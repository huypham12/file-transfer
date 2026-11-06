package main

import (
	"context"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"teamshare/relay/internal/relay"
)

func main() {
	port := os.Getenv("PORT")
	if port == "" {
		port = "8080"
	}

	hub := relay.NewHub()
	syncHub := relay.NewSyncHub()

	mux := http.NewServeMux()
	mux.HandleFunc("/v1/stream", relay.HandleStream(hub))
	mux.HandleFunc("/v1/sync", relay.HandleSync(syncHub))
	mux.HandleFunc("/health", relay.HandleHealth(hub))

	server := &http.Server{
		Addr:         ":" + port,
		Handler:      mux,
		ReadTimeout:  30 * time.Second,
		WriteTimeout: 30 * time.Second,
		IdleTimeout:  120 * time.Second,
	}

	stopChan := make(chan os.Signal, 1)
	signal.Notify(stopChan, os.Interrupt, syscall.SIGTERM)

	go func() {
		log.Printf("[TeamShare Relay] Máy chủ chuyển tiếp Zero-Knowledge đang lắng nghe trên cổng :%s", port)
		log.Println("[TeamShare Relay] CAM KẾT BẢO MẬT: Chuyển tiếp luồng byte thuần túy trong RAM, KHÔNG LƯU TRỮ ĐĨA.")
		if hostIP := relay.GetHostLanIP(); hostIP != "" {
			log.Printf("[TeamShare Relay] Đã phát hiện IP Wi-Fi máy tính: %s (Endpoint: ws://%s:%s/v1/sync)", hostIP, hostIP, port)
		}
		if err := server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatalf("[TeamShare Relay] Lỗi máy chủ: %v", err)
		}
	}()

	<-stopChan
	log.Println("[TeamShare Relay] Đang tắt máy chủ một cách an toàn (Graceful Shutdown)...")

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	hub.CloseAll()
	if err := server.Shutdown(ctx); err != nil {
		log.Printf("[TeamShare Relay] Lỗi khi tắt máy chủ: %v", err)
	}

	log.Println("[TeamShare Relay] Máy chủ đã dừng hoạt động hoàn toàn.")
}

