package relay

import (
	"os"
	"strconv"
	"time"
)

// ServerConfig chứa toàn bộ cấu hình kỹ thuật và môi trường cho Relay Server.
// Hỗ trợ nạp linh hoạt từ biến môi trường (ENV) với giá trị dự phòng chuẩn kỹ thuật.
type ServerConfig struct {
	Port                string
	MaxActiveBridges    int
	ChannelQueueSize    int
	SocketBufferSize    int
	AttachTimeout       time.Duration
	WriteTimeout        time.Duration
	ReadTimeout         time.Duration
	IdleTimeout         time.Duration
	GracefulStopTimeout time.Duration
}

// DefaultConfig nạp cấu hình mặc định, ưu tiên đọc biến môi trường nếu có.
func DefaultConfig() *ServerConfig {
	port := getEnv("PORT", "8080")
	maxBridges := getEnvAsInt("MAX_ACTIVE_BRIDGES", 200)
	queueSize := getEnvAsInt("CHANNEL_QUEUE_SIZE", 80)
	socketBuf := getEnvAsInt("SOCKET_BUFFER_BYTES", 64*1024)

	return &ServerConfig{
		Port:                port,
		MaxActiveBridges:    maxBridges,
		ChannelQueueSize:    queueSize,
		SocketBufferSize:    socketBuf,
		AttachTimeout:       15 * time.Second,
		WriteTimeout:        30 * time.Second,
		ReadTimeout:         30 * time.Second,
		IdleTimeout:         120 * time.Second,
		GracefulStopTimeout: 10 * time.Second,
	}
}

func getEnv(key, fallback string) string {
	if val := os.Getenv(key); val != "" {
		return val
	}
	return fallback
}

func getEnvAsInt(key string, fallback int) int {
	if valStr := os.Getenv(key); valStr != "" {
		if val, err := strconv.Atoi(valStr); err == nil {
			return val
		}
	}
	return fallback
}

