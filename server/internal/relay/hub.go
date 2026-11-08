package relay

import (
	"log"
	"sync"
)

// Hub quản lý danh sách các ChannelBridge đang hoạt động trong bộ nhớ RAM của máy chủ.
type Hub struct {
	bridges sync.Map // map[string]*ChannelBridge
	config  *ServerConfig
}

// NewHub khởi tạo Hub quản lý chuyển tiếp với cấu hình tập trung.
func NewHub(cfg ...*ServerConfig) *Hub {
	var c *ServerConfig
	if len(cfg) > 0 && cfg[0] != nil {
		c = cfg[0]
	} else {
		c = DefaultConfig()
	}
	return &Hub{config: c}
}

// Config trả về cấu hình hiện tại của Hub.
func (h *Hub) Config() *ServerConfig {
	if h.config == nil {
		h.config = DefaultConfig()
	}
	return h.config
}

// GetOrCreateBridge tìm kiếm hoặc tạo mới một ChannelBridge cho streamId.
func (h *Hub) GetOrCreateBridge(streamId string) (*ChannelBridge, bool) {
	val, loaded := h.bridges.Load(streamId)
	if loaded {
		return val.(*ChannelBridge), false
	}

	maxBridges := h.Config().MaxActiveBridges
	if h.CountActiveBridges() >= maxBridges {
		log.Printf("[Hub] Từ chối tạo bridge mới: Đã chạm trần %d phiên hoạt động", maxBridges)
		return nil, false
	}

	newBridge := NewChannelBridge(streamId, h.Config(), func(id string) {
		h.RemoveBridge(id)
	})

	actual, loaded := h.bridges.LoadOrStore(streamId, newBridge)
	if loaded {
		return actual.(*ChannelBridge), false
	}

	log.Printf("[Hub] Đã tạo mới ChannelBridge cho StreamId: %s", streamId)
	return newBridge, true
}

// RemoveBridge loại bỏ ChannelBridge khỏi danh sách khi phiên kết thúc.
func (h *Hub) RemoveBridge(streamId string) {
	if _, loaded := h.bridges.LoadAndDelete(streamId); loaded {
		log.Printf("[Hub] Đã dọn dẹp ChannelBridge khỏi RAM cho StreamId: %s", streamId)
	}
}

// CountActiveBridges đếm số lượng kênh chuyển tiếp đang hoạt động.
func (h *Hub) CountActiveBridges() int {
	count := 0
	h.bridges.Range(func(key, value interface{}) bool {
		count++
		return true
	})
	return count
}

// CloseAll giải phóng toàn bộ các kênh khi máy chủ shutdown.
func (h *Hub) CloseAll() {
	h.bridges.Range(func(key, value interface{}) bool {
		bridge := value.(*ChannelBridge)
		bridge.Close()
		return true
	})
}

