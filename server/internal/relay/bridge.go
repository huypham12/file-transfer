package relay

import (
	"log"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

// SafeConn bọc websocket.Conn với Mutex để bảo đảm an toàn khi ghi đồng thời.
type SafeConn struct {
	*websocket.Conn
	writeMu sync.Mutex
}

func NewSafeConn(conn *websocket.Conn) *SafeConn {
	return &SafeConn{Conn: conn}
}

func (sc *SafeConn) WriteMessage(messageType int, data []byte) error {
	sc.writeMu.Lock()
	defer sc.writeMu.Unlock()
	return sc.Conn.WriteMessage(messageType, data)
}

// ChannelBridge đại diện cho một kênh chuyển tiếp hai chiều ghép cặp giữa Sender và Receiver trên RAM.
// CAM KẾT ZERO-KNOWLEDGE:
// - Toàn bộ dữ liệu chuyển tiếp trực tiếp trong RAM thông qua goroutine pipe.
// - Tuyệt đối không lưu trữ dữ liệu tệp tin trên bất kỳ đĩa cứng hay cơ sở dữ liệu nào.
type ChannelBridge struct {
	StreamId     string
	SenderConn   *SafeConn
	ReceiverConn *SafeConn
	CreatedAt    time.Time
	mu           sync.Mutex
	readyChan    chan struct{}
	closeOnce    sync.Once
	isClosed     bool
	onClosed     func(streamId string)
}

// NewChannelBridge khởi tạo một cầu nối chuyển tiếp mới cho streamId.
func NewChannelBridge(streamId string, onClosed func(streamId string)) *ChannelBridge {
	return &ChannelBridge{
		StreamId:  streamId,
		CreatedAt: time.Now(),
		readyChan: make(chan struct{}),
		onClosed:  onClosed,
	}
}

// AttachSender gắn kết nối của Sender vào cầu nối.
func (b *ChannelBridge) AttachSender(conn *websocket.Conn) bool {
	b.mu.Lock()
	defer b.mu.Unlock()

	if b.isClosed {
		return false
	}

	// Nếu đã có SenderConn nhưng chưa có ReceiverConn (chưa ghép cặp hoàn tất),
	// cho phép client thử lại thay thế kết nối cũ đã bị ngắt mạng
	if b.SenderConn != nil {
		if b.ReceiverConn == nil {
			log.Printf("[Bridge %s] Thay thế kết nối SENDER cũ bằng kết nối mới (Client Retry)", b.StreamId)
			_ = b.SenderConn.Close()
			b.SenderConn = NewSafeConn(conn)
			return true
		}
		return false
	}

	b.SenderConn = NewSafeConn(conn)
	if b.ReceiverConn != nil {
		close(b.readyChan)
	}
	return true
}

// AttachReceiver gắn kết nối của Receiver vào cầu nối.
func (b *ChannelBridge) AttachReceiver(conn *websocket.Conn) bool {
	b.mu.Lock()
	defer b.mu.Unlock()

	if b.isClosed {
		return false
	}

	// Nếu đã có ReceiverConn nhưng chưa có SenderConn (chưa ghép cặp hoàn tất),
	// cho phép client thử lại thay thế kết nối cũ đã bị ngắt mạng
	if b.ReceiverConn != nil {
		if b.SenderConn == nil {
			log.Printf("[Bridge %s] Thay thế kết nối RECEIVER cũ bằng kết nối mới (Client Retry)", b.StreamId)
			_ = b.ReceiverConn.Close()
			b.ReceiverConn = NewSafeConn(conn)
			return true
		}
		return false
	}

	b.ReceiverConn = NewSafeConn(conn)
	if b.SenderConn != nil {
		close(b.readyChan)
	}
	return true
}

// StartForwarding bắt đầu vòng lặp chuyển tiếp dữ liệu nhị phân hai chiều.
func (b *ChannelBridge) StartForwarding() {
	// Đợi cho đến khi cả 2 bên Sender và Receiver đều đã gắn kết nối
	select {
	case <-b.readyChan:
		log.Printf("[Bridge %s] Cả hai bên Sender và Receiver đã sẵn sàng. Bắt đầu chuyển tiếp E2EE...", b.StreamId)
	case <-time.After(60 * time.Second):
		log.Printf("[Bridge %s] Quá thời gian chờ ghép cặp (60s). Hủy cầu nối.", b.StreamId)
		b.Close()
		return
	}

	var wg sync.WaitGroup
	wg.Add(2)

	// Goroutine 1: Chuyển tiếp từ Sender sang Receiver (Data Chunks, Handshake, Manifest)
	go func() {
		defer wg.Done()
		b.pipeStream(b.SenderConn, b.ReceiverConn, "Sender -> Receiver")
		b.Close()
	}()

	// Goroutine 2: Chuyển tiếp từ Receiver sang Sender (Chunk ACKs, Manifest Response, Verify Result)
	go func() {
		defer wg.Done()
		b.pipeStream(b.ReceiverConn, b.SenderConn, "Receiver -> Sender")
		b.Close()
	}()

	wg.Wait()
	log.Printf("[Bridge %s] Phiên chuyển tiếp hoàn tất và đã đóng an toàn.", b.StreamId)
}

// pipeStream đọc bản tin từ nguồn và ghi trực tiếp sang đích.
// Dữ liệu chỉ tồn tại thoáng qua trong biến payload trên RAM và giải phóng tức thì.
func (b *ChannelBridge) pipeStream(src *SafeConn, dst *SafeConn, direction string) {
	if src == nil || dst == nil {
		return
	}

	for {
		messageType, payload, err := src.ReadMessage()
		if err != nil {
			log.Printf("[Bridge %s] Kết nối %s bị ngắt: %v", b.StreamId, direction, err)
			return
		}

		if err := dst.WriteMessage(messageType, payload); err != nil {
			log.Printf("[Bridge %s] Lỗi gửi dữ liệu %s: %v", b.StreamId, direction, err)
			return
		}
	}
}

// Close dọn dẹp và đóng cả hai kết nối an toàn.
func (b *ChannelBridge) Close() {
	b.closeOnce.Do(func() {
		b.mu.Lock()
		b.isClosed = true
		b.mu.Unlock()

		if b.SenderConn != nil {
			_ = b.SenderConn.Close()
		}
		if b.ReceiverConn != nil {
			_ = b.ReceiverConn.Close()
		}

		if b.onClosed != nil {
			b.onClosed(b.StreamId)
		}
	})
}

