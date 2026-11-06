package relay

import (
	"encoding/json"
	"log"
	"net/http"
	"sync"
)

// SyncHub quản lý danh sách các phòng đồng bộ trạng thái nhóm (Team Sync Rooms) trên RAM.
// CAM KẾT ZERO-KNOWLEDGE:
// - Chỉ chuyển tiếp bản tin điều khiển và danh mục tài liệu trực tiếp trong bộ nhớ RAM.
// - Tuyệt đối không lưu trữ đĩa hay cơ sở dữ liệu.
type SyncHub struct {
	rooms sync.Map // map[string]*SyncRoom
}

type SyncRoom struct {
	teamId  string
	clients sync.Map // map[*SafeConn]bool
}

func (r *SyncRoom) ClientCount() int {
	count := 0
	r.clients.Range(func(_, _ interface{}) bool {
		count++
		return true
	})
	return count
}

func NewSyncHub() *SyncHub {
	return &SyncHub{}
}

func (sh *SyncHub) GetOrCreateRoom(teamId string) *SyncRoom {
	val, loaded := sh.rooms.Load(teamId)
	if loaded {
		return val.(*SyncRoom)
	}
	room := &SyncRoom{teamId: teamId}
	actual, _ := sh.rooms.LoadOrStore(teamId, room)
	return actual.(*SyncRoom)
}

// HandleSync xử lý kết nối WebSocket vào phòng đồng bộ nhóm /v1/sync.
func HandleSync(syncHub *SyncHub) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		teamId := r.URL.Query().Get("teamId")
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
		room.clients.Store(safeConn, true)
		log.Printf("[Sync Room %s] + Thiết bị mới kết nối từ %s. (Hiện có %d thiết bị trong phòng)", teamId, r.RemoteAddr, room.ClientCount())

		defer func() {
			room.clients.Delete(safeConn)
			_ = safeConn.Close()
			log.Printf("[Sync Room %s] - Thiết bị %s đã ngắt kết nối. (Còn lại %d thiết bị)", teamId, r.RemoteAddr, room.ClientCount())
		}()

		for {
			msgType, data, err := safeConn.ReadMessage()
			if err != nil {
				break
			}

			var baseMsg struct {
				Action string `json:"action"`
			}
			_ = json.Unmarshal(data, &baseMsg)
			action := baseMsg.Action
			if action == "" {
				action = "BINARY/RAW"
			}

			// Chuyển tiếp ngay lập tức tới tất cả các thiết bị khác trong cùng nhóm
			forwarded := 0
			room.clients.Range(func(key, value interface{}) bool {
				client := key.(*SafeConn)
				if client != safeConn {
					writeErr := client.WriteMessage(msgType, data)
					if writeErr != nil {
						log.Printf("[Sync Room %s] ! Lỗi gửi tới client: %v, gỡ kết nối chết", teamId, writeErr)
						room.clients.Delete(client)
						_ = client.Close()
					} else {
						forwarded++
					}
				}
				return true
			})
			log.Printf("[Sync Room %s] <=> Bản tin [%s] (%d bytes) từ %s -> đã chuyển tiếp tới %d thiết bị khác",
				teamId, action, len(data), r.RemoteAddr, forwarded)
		}
	}
}

