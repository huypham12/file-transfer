package relay

import (
	"testing"
	"time"
)

func TestSyncHub_PendingMessages_QueueAndCleanup(t *testing.T) {
	sh := NewSyncHub()
	room := sh.GetOrCreateRoom("team_test_123")

	// 1. Kiểm tra ban đầu phòng chưa có tin chờ
	room.mu.RLock()
	if len(room.pendingMessages) != 0 {
		t.Fatalf("Mong đợi pendingMessages rỗng, nhưng có %d", len(room.pendingMessages))
	}
	room.mu.RUnlock()

	// 2. Thêm tin chờ cho peer "peer_bob" với thời gian hết hạn trong tương lai
	room.mu.Lock()
	room.pendingMessages["peer_bob"] = []QueuedMessage{
		{
			MessageType:  1,
			Data:         []byte(`{"action":"FILE_REQUEST"}`),
			TargetPeerId: "peer_bob",
			ExpireAt:     time.Now().Add(10 * time.Minute),
		},
		{
			MessageType:  1,
			Data:         []byte(`{"action":"EXPIRED_MSG"}`),
			TargetPeerId: "peer_bob",
			ExpireAt:     time.Now().Add(-1 * time.Minute), // Đã hết hạn
		},
	}
	room.mu.Unlock()

	// 3. Chạy hàm dọn dẹp
	sh.cleanupPendingMessages()

	// 4. Kiểm tra tin hết hạn đã bị loại bỏ, tin còn hạn được giữ lại
	room.mu.RLock()
	bobMsgs := room.pendingMessages["peer_bob"]
	if len(bobMsgs) != 1 {
		t.Fatalf("Mong đợi còn 1 tin nhắn chưa hết hạn, nhưng có %d", len(bobMsgs))
	}
	if string(bobMsgs[0].Data) != `{"action":"FILE_REQUEST"}` {
		t.Fatalf("Nội dung tin nhắn không khớp: %s", string(bobMsgs[0].Data))
	}
	room.mu.RUnlock()

	// 5. Cho tin còn lại hết hạn và dọn dẹp tiếp
	room.mu.Lock()
	room.pendingMessages["peer_bob"][0].ExpireAt = time.Now().Add(-1 * time.Second)
	room.mu.Unlock()

	sh.cleanupPendingMessages()

	room.mu.RLock()
	if _, exists := room.pendingMessages["peer_bob"]; exists {
		t.Fatalf("Mong đợi peer_bob bị xóa hoàn toàn khỏi pendingMessages sau khi toàn bộ tin hết hạn")
	}
	room.mu.RUnlock()
}

func TestHub_MaxActiveBridgesLimit(t *testing.T) {
	hub := NewHub()
	for i := 0; i < DefaultMaxActiveBridges; i++ {
		streamId := "stream_" + string(rune('a'+i%26)) + string(rune('0'+i%10)) + "_" + time.Now().Format("150405.000000000") + "_" + string(rune(i))
		bridge, created := hub.GetOrCreateBridge(streamId)
		if bridge == nil || !created {
			t.Fatalf("Mong đợi tạo bridge thành công cho index %d, nhưng thất bại", i)
		}
	}

	if hub.CountActiveBridges() != DefaultMaxActiveBridges {
		t.Fatalf("Mong đợi số active bridges là %d, thực tế: %d", DefaultMaxActiveBridges, hub.CountActiveBridges())
	}

	// Tạo thêm 1 bridge nữa vượt ngưỡng trần -> phải bị từ chối trả về nil
	overLimitBridge, created := hub.GetOrCreateBridge("stream_over_limit")
	if overLimitBridge != nil || created {
		t.Fatalf("Mong đợi từ chối tạo bridge khi chạm trần, nhưng nhận được bridge: %+v", overLimitBridge)
	}

	// Đóng và dọn dẹp
	hub.CloseAll()
	if hub.CountActiveBridges() != 0 {
		t.Fatalf("Mong đợi 0 active bridges sau khi CloseAll, thực tế: %d", hub.CountActiveBridges())
	}
}

func TestChannelBridge_DoneChannelCloses(t *testing.T) {
	bridge := NewChannelBridge("stream_test_done", nil)
	select {
	case <-bridge.done:
		t.Fatalf("done channel không được đóng trước khi gọi Close()")
	default:
		// Hợp lệ: chưa đóng
	}

	bridge.Close()

	select {
	case <-bridge.done:
		// Hợp lệ: done channel đã đóng
	default:
		t.Fatalf("Mong đợi done channel được đóng sau khi gọi Close()")
	}
}

