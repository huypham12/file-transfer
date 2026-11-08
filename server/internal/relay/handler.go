package relay

import (
	"encoding/json"
	"log"
	"net"
	"net/http"
	"strings"
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

		// Đặt thời gian chờ đọc bản tin ATTACH_STREAM ban đầu
		_ = conn.SetReadDeadline(time.Now().Add(hub.Config().AttachTimeout))
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
		if bridge == nil {
			_ = conn.WriteJSON(AttachResponse{
				Status:  "ERROR",
				Message: "Máy chủ đang quá tải số phiên chuyển tiếp đồng thời (vui lòng thử lại sau)",
			})
			_ = conn.Close()
			return
		}
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
		log.Printf("[Relay] Stream [%s]: Da gan ket noi %s thanh cong (dia chi: %s)", attachMsg.StreamId, attachMsg.Role, r.RemoteAddr)

		// Nếu là người tạo bridge mới thì kích hoạt goroutine chờ bên còn lại
		if isNew {
			go bridge.StartForwarding()
		}
	}
}

// GetHostLanIP tự động phát hiện địa chỉ IPv4 Wi-Fi / LAN của máy tính đang chạy Relay Server.
func GetHostLanIP() string {
	interfaces, err := net.Interfaces()
	if err != nil {
		return ""
	}

	var candidates []string
	for _, iface := range interfaces {
		if iface.Flags&net.FlagUp == 0 || iface.Flags&net.FlagLoopback != 0 {
			continue
		}
		addrs, err := iface.Addrs()
		if err != nil {
			continue
		}
		for _, addr := range addrs {
			var ip net.IP
			switch v := addr.(type) {
			case *net.IPNet:
				ip = v.IP
			case *net.IPAddr:
				ip = v.IP
			}
			if ip == nil || ip.IsLoopback() || ip.To4() == nil {
				continue
			}
			ipStr := ip.String()
			// Bỏ qua dải APIPA 169.254.x và card ảo Docker/VMware
			if strings.HasPrefix(ipStr, "169.254.") {
				continue
			}
			nameLower := strings.ToLower(iface.Name)
			// Ưu tiên card Wi-Fi hoặc Wireless LAN
			if strings.Contains(nameLower, "wi-fi") || strings.Contains(nameLower, "wlan") || strings.Contains(nameLower, "wireless") {
				return ipStr
			}
			if !strings.Contains(nameLower, "vmnet") && !strings.Contains(nameLower, "vbox") && !strings.Contains(nameLower, "virtual") {
				candidates = append(candidates, ipStr)
			}
		}
	}
	if len(candidates) > 0 {
		return candidates[0]
	}
	return ""
}

// HandleHealth xử lý endpoint kiểm tra sức khỏe hệ thống /health và trả về IP LAN của máy chủ.
func HandleHealth(hub *Hub) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]interface{}{
			"status":        "UP",
			"service":       "teamshare-zero-knowledge-relay",
			"activeBridges": hub.CountActiveBridges(),
			"hostIp":        GetHostLanIP(),
			"timestamp":     time.Now().Unix(),
		})
	}
}

