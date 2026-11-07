package relay

import (
	"encoding/json"
	"log"
	"net/http"
	"sync"
	"time"
)

// QueuedMessage lưu trữ tạm thời các bản tin tín hiệu chuyển tiếp có đích danh (targetPeerId)
// khi client đích tạm thời mất kết nối (reconnecting).
// CAM KẾT ZERO-KNOWLEDGE: Chỉ lưu đệm trên RAM tối đa TTL (60s), tự hủy sau khi giao xong hoặc quá hạn.
type QueuedMessage struct {
	MessageType  int
	Data         []byte
	TargetPeerId string
	ExpireAt     time.Time
}

// SyncRoom quản lý kết nối và hàng đợi tin nhắn của một nhóm chia sẻ.
type SyncRoom struct {
	teamId          string
	mu              sync.RWMutex
	clients         map[*SafeConn]string       // safeConn -> peerId
	clientsByPeerId map[string]*SafeConn       // peerId -> safeConn
	pendingMessages map[string][]QueuedMessage // targetPeerId -> danh sách tin chờ
}

func (r *SyncRoom) ClientCount() int {
	r.mu.RLock()
	defer r.mu.RUnlock()
	return len(r.clients)
}

// SyncHub quản lý danh sách các phòng đồng bộ trạng thái nhóm (Team Sync Rooms) trên RAM.
type SyncHub struct {
	rooms sync.Map // map[string]*SyncRoom
}

func NewSyncHub() *SyncHub {
	sh := &SyncHub{}
	// Goroutine định kỳ dọn dẹp các tin nhắn đệm quá hạn TTL 60s
	go func() {
		ticker := time.NewTicker(20 * time.Second)
		for range ticker.C {
			sh.cleanupPendingMessages()
		}
	}()
	return sh
}

func (sh *SyncHub) cleanupPendingMessages() {
	now := time.Now()
	sh.rooms.Range(func(_, val interface{}) bool {
		room := val.(*SyncRoom)
		room.mu.Lock()
		for peerId, msgs := range room.pendingMessages {
			var valid []QueuedMessage
			for _, m := range msgs {
				if now.Before(m.ExpireAt) {
					valid = append(valid, m)
				}
			}
			if len(valid) == 0 {
				delete(room.pendingMessages, peerId)
			} else {
				room.pendingMessages[peerId] = valid
			}
		}
		room.mu.Unlock()
		return true
	})
}

func (sh *SyncHub) GetOrCreateRoom(teamId string) *SyncRoom {
	val, loaded := sh.rooms.Load(teamId)
	if loaded {
		return val.(*SyncRoom)
	}
	room := &SyncRoom{
		teamId:          teamId,
		clients:         make(map[*SafeConn]string),
		clientsByPeerId: make(map[string]*SafeConn),
		pendingMessages: make(map[string][]QueuedMessage),
	}
	actual, _ := sh.rooms.LoadOrStore(teamId, room)
	return actual.(*SyncRoom)
}

// HandleSync xử lý kết nối WebSocket vào phòng đồng bộ nhóm /v1/sync.
// Hỗ trợ tham số query: teamId (bắt buộc) và peerId (tùy chọn, dùng cho định tuyến tin cậy).
func HandleSync(syncHub *SyncHub) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		teamId := r.URL.Query().Get("teamId")
		peerId := r.URL.Query().Get("peerId")
		if teamId == "" {
			http.Error(w, "Thiếu tham số teamId trong URL", http.StatusBadRequest)
			return
		}

		conn, err := upgrader.Upgrade(w, r, nil)
		if err != nil {
			log.Printf("[Sync] Lỗi nâng cấp WebSocket: %v", err)
			return
		}

		safeConn := NewSafeConn(conn)
		room := syncHub.GetOrCreateRoom(teamId)

		// Đăng ký client vào phòng
		room.mu.Lock()
		room.clients[safeConn] = peerId
		if peerId != "" {
			// Nếu đã có kết nối cũ với cùng peerId (ví dụ client reconnect trước khi kết nối cũ kịp timeout)
			if oldConn, exists := room.clientsByPeerId[peerId]; exists && oldConn != safeConn {
				delete(room.clients, oldConn)
				_ = oldConn.Close()
			}
			room.clientsByPeerId[peerId] = safeConn

			// Kiểm tra và xả (flush) ngay các tin nhắn đang chờ cho peer này
			if pending, hasPending := room.pendingMessages[peerId]; hasPending && len(pending) > 0 {
				now := time.Now()
				flushedCount := 0
				for _, msg := range pending {
					if now.Before(msg.ExpireAt) {
						if err := safeConn.WriteMessage(msg.MessageType, msg.Data); err == nil {
							flushedCount++
						}
					}
				}
				delete(room.pendingMessages, peerId)
				if flushedCount > 0 {
					log.Printf("[Sync Room %s] => Đã xả (flush) %d bản tin đang chờ tới peer [%s]", teamId, flushedCount, peerId)
				}
			}
		}
		clientCount := len(room.clients)
		room.mu.Unlock()

		log.Printf("[Sync Room %s] + Thiết bị [%s] kết nối từ %s. (Hiện có %d thiết bị trong phòng)",
			teamId, peerId, r.RemoteAddr, clientCount)

		defer func() {
			room.mu.Lock()
			currentPeerId := room.clients[safeConn]
			delete(room.clients, safeConn)
			if currentPeerId != "" && room.clientsByPeerId[currentPeerId] == safeConn {
				delete(room.clientsByPeerId, currentPeerId)
			}
			remainingCount := len(room.clients)
			room.mu.Unlock()

			_ = safeConn.Close()
			log.Printf("[Sync Room %s] - Thiết bị [%s] (%s) đã ngắt kết nối. (Còn lại %d thiết bị)",
				teamId, currentPeerId, r.RemoteAddr, remainingCount)
		}()

		for {
			msgType, data, err := safeConn.ReadMessage()
			if err != nil {
				break
			}

			var baseMsg struct {
				Action       string `json:"action"`
				TargetPeerId string `json:"targetPeerId"`
				RecipientId  string `json:"recipientId"`
				MemberId     string `json:"memberId"`
				RequesterId  string `json:"requesterId"`
				PublisherId  string `json:"publisherId"`
				SenderId     string `json:"senderId"`
			}
			_ = json.Unmarshal(data, &baseMsg)
			action := baseMsg.Action
			if action == "" {
				action = "BINARY/RAW"
			}

			// Tự động nhận diện và liên kết danh tính client (Dynamic Peer Binding)
			senderId := baseMsg.MemberId
			if senderId == "" {
				senderId = baseMsg.RequesterId
			}
			if senderId == "" {
				senderId = baseMsg.PublisherId
			}
			if senderId == "" {
				senderId = baseMsg.SenderId
			}

			if senderId != "" {
				room.mu.Lock()
				currentBound := room.clients[safeConn]
				if currentBound != senderId {
					room.clients[safeConn] = senderId
					room.clientsByPeerId[senderId] = safeConn
					log.Printf("[Sync Room %s] (*) Đã nhận diện & liên kết thiết bị [%s] từ kết nối %s",
						teamId, senderId, r.RemoteAddr)

					// Xả ngay các bản tin đang chờ tới thiết bị này (Flush Pending Queue)
					if pending, hasPending := room.pendingMessages[senderId]; hasPending && len(pending) > 0 {
						now := time.Now()
						flushedCount := 0
						for _, msg := range pending {
							if now.Before(msg.ExpireAt) {
								if writeErr := safeConn.WriteMessage(msg.MessageType, msg.Data); writeErr == nil {
									flushedCount++
								}
							}
						}
						delete(room.pendingMessages, senderId)
						if flushedCount > 0 {
							log.Printf("[Sync Room %s] => Đã xả (flush) %d bản tin đang chờ tới peer [%s]",
								teamId, flushedCount, senderId)
						}
					}
				}
				room.mu.Unlock()
			}

			targetPeerId := baseMsg.TargetPeerId
			if targetPeerId == "" {
				targetPeerId = baseMsg.RecipientId
			}

			if targetPeerId != "" {
				// ĐỊNH TUYẾN ĐÍCH DANH (Unicast Signaling)
				room.mu.Lock()
				targetConn, isOnline := room.clientsByPeerId[targetPeerId]
				delivered := false
				if isOnline && targetConn != nil {
					writeErr := targetConn.WriteMessage(msgType, data)
					if writeErr == nil {
						delivered = true
					} else {
						log.Printf("[Sync Room %s] ! Lỗi gửi tới target [%s]: %v. Gỡ kết nối lỗi", teamId, targetPeerId, writeErr)
						delete(room.clients, targetConn)
						delete(room.clientsByPeerId, targetPeerId)
						_ = targetConn.Close()
					}
				}

				if !delivered {
					// Peer đích tạm thời chưa đăng ký hoặc vắng mặt -> Đệm vào hàng đợi RAM (TTL 60s)
					queued := QueuedMessage{
						MessageType:  msgType,
						Data:         data,
						TargetPeerId: targetPeerId,
						ExpireAt:     time.Now().Add(60 * time.Second),
					}
					list := room.pendingMessages[targetPeerId]
					if len(list) >= 50 {
						list = list[1:] // Giới hạn tối đa 50 tin đệm/peer để bảo vệ RAM
					}
					room.pendingMessages[targetPeerId] = append(list, queued)
					log.Printf("[Sync Room %s] [!] Target [%s] chưa định tuyến trực tiếp. Bản tin [%s] đã lưu đệm RAM (TTL 60s) & phát dự phòng trong phòng",
						teamId, targetPeerId, action)

					// Phát dự phòng tới các thành viên khác trong phòng
					for client := range room.clients {
						if client != safeConn {
							_ = client.WriteMessage(msgType, data)
						}
					}
				} else {
					log.Printf("[Sync Room %s] => Bản tin [%s] chuyển tiếp thành công tới đích [%s]", teamId, action, targetPeerId)
				}
				room.mu.Unlock()
			} else {
				// BROADCAST CHO TOÀN PHÒNG (trừ người gửi)
				forwarded := 0
				room.mu.RLock()
				for client := range room.clients {
					if client != safeConn {
						if writeErr := client.WriteMessage(msgType, data); writeErr == nil {
							forwarded++
						}
					}
				}
				currentSenderId := room.clients[safeConn]
				room.mu.RUnlock()

				log.Printf("[Sync Room %s] <=> Bản tin [%s] (%d bytes) từ [%s] -> đã broadcast tới %d thiết bị khác",
					teamId, action, len(data), currentSenderId, forwarded)
			}
		}
	}
}
