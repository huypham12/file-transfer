package relay

import (
	"encoding/json"
	"log"
	"net/http"
	"time"

	"github.com/gorilla/websocket"
)

var upgrader = websocket.Upgrader{
	CheckOrigin: func(r *http.Request) bool {
		return true // Cho phép kết nối từ mọi thiết bị Android
	},
	ReadBufferSize:  64 * 1024,
	WriteBufferSize: 64 * 1024,
}

// AttachStreamMessage đại diện cho bản tin JSON thiết lập kênh từ Client theo SDD mục 6.3.
type AttachStreamMessage struct {
	Action     string `json:"action"`
	StreamId   string `json:"streamId"`
	Role       string `json:"role"` // "SENDER" hoặc "RECEIVER"
	AuthTicket string `json:"authTicket,omitempty"`
}

// AttachResponse phản hồi xác nhận kênh đã gắn thành công.
type AttachResponse struct {
	Status   string `json:"status"`
	StreamId string `json:"streamId"`
	Role     string `json:"role"`
	Message  string `json:"message,omitempty"`
}

// HandleStream xử lý nâng cấp WebSocket cho endpoint /v1/stream.
func HandleStream(hub *Hub) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		conn, err := upgrader.Upgrade(w, r, nil)
		if err != nil {
			log.Printf("[Handler] Lỗi nâng cấp WebSocket: %v", err)
			return
		}

		// Đặt thời gian chờ đọc bản tin ATTACH_STREAM ban đầu (15 giây)
		_ = conn.SetReadDeadline(time.Now().Add(15 * time.Second))
		_, msgBytes, err := conn.ReadMessage()
		if err != nil {
			log.Printf("[Handler] Lỗi đọc bản tin ATTACH_STREAM: %v", err)
			_ = conn.Close()
			return
		}
		_ = conn.SetReadDeadline(time.Time{}) // Hủy deadline sau khi đã gắn kênh

		var attachMsg AttachStreamMessage
		if err := json.Unmarshal(msgBytes, &attachMsg); err != nil || attachMsg.Action != "ATTACH_STREAM" || attachMsg.StreamId == "" {
			log.Printf("[Handler] Bản tin ATTACH_STREAM không hợp lệ: %s", string(msgBytes))
			_ = conn.WriteJSON(AttachResponse{
				Status:  "ERROR",
				Message: "Bản tin ATTACH_STREAM không hợp lệ",
			})
			_ = conn.Close()
			return
		}

		bridge, isNew := hub.GetOrCreateBridge(attachMsg.StreamId)
		attached := false

		switch attachMsg.Role {
		case "SENDER":
			attached = bridge.AttachSender(conn)
		case "RECEIVER":
			attached = bridge.AttachReceiver(conn)
		default:
			log.Printf("[Handler] Vai trò không hợp lệ: %s", attachMsg.Role)
			_ = conn.WriteJSON(AttachResponse{
				Status:  "ERROR",
				Message: "Vai trò không hợp lệ (yêu cầu SENDER hoặc RECEIVER)",
			})
			_ = conn.Close()
			return
		}

		if !attached {
			log.Printf("[Handler] Không thể gắn kết nối (kênh đã có %s hoặc đã đóng)", attachMsg.Role)
			_ = conn.WriteJSON(AttachResponse{
				Status:  "ERROR",
				Message: "Vai trò này đã được gắn trong phiên hoặc phiên đã đóng",
			})
			_ = conn.Close()
			return
		}

		// Phản hồi xác nhận gắn kênh thành công
		_ = conn.WriteJSON(AttachResponse{
			Status:   "ATTACHED",
			StreamId: attachMsg.StreamId,
			Role:     attachMsg.Role,
			Message:  "Đã kết nối vào kênh chuyển tiếp Zero-Knowledge thành công",
		})

		// Nếu là người tạo bridge mới thì kích hoạt goroutine chờ bên còn lại
		if isNew {
			go bridge.StartForwarding()
		}
	}
}

// HandleHealth xử lý endpoint kiểm tra sức khỏe hệ thống /health.
func HandleHealth(hub *Hub) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]interface{}{
			"status":        "UP",
			"service":       "teamshare-zero-knowledge-relay",
			"activeBridges": hub.CountActiveBridges(),
			"timestamp":     time.Now().Unix(),
		})
	}
}

