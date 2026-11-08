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
	_ = sc.Conn.SetWriteDeadline(time.Now().Add(4 * time.Second))
	return sc.Conn.WriteMessage(messageType, data)
}

type StreamPayload struct {
	MsgType int
	Data    []byte
}

// ChannelBridge đại diện cho một kênh chuyển tiếp hai chiều ghép cặp giữa Sender và Receiver trên RAM.
// CAM KẾT ZERO-KNOWLEDGE:
// - Toàn bộ dữ liệu chuyển tiếp trực tiếp trong RAM thông qua goroutine pipe.
// - Tuyệt đối không lưu trữ dữ liệu tệp tin trên bất kỳ đĩa cứng hay cơ sở dữ liệu nào.
type ChannelBridge struct {
	StreamId      string
	SenderConn    *SafeConn
	ReceiverConn  *SafeConn
	fromSender    chan StreamPayload
	fromReceiver  chan StreamPayload
	senderReady   chan struct{}
	receiverReady chan struct{}
	senderOnce    sync.Once
	receiverOnce  sync.Once
	done          chan struct{}
	doneOnce      sync.Once
	CreatedAt     time.Time
	mu            sync.Mutex
	closeOnce     sync.Once
	isClosed      bool
	onClosed      func(streamId string)
}

// NewChannelBridge khởi tạo một cầu nối chuyển tiếp mới cho streamId.
func NewChannelBridge(streamId string, onClosed func(streamId string)) *ChannelBridge {
	return &ChannelBridge{
		StreamId:      streamId,
		fromSender:    make(chan StreamPayload, 80),
		fromReceiver:  make(chan StreamPayload, 80),
		senderReady:   make(chan struct{}),
		receiverReady: make(chan struct{}),
		done:          make(chan struct{}),
		CreatedAt:     time.Now(),
		onClosed:      onClosed,
	}
}

// AttachSender gắn kết nối của Sender vào cầu nối và kích hoạt vòng đọc RAM tức thì.
func (b *ChannelBridge) AttachSender(conn *websocket.Conn) bool {
	b.mu.Lock()
	defer b.mu.Unlock()

	if b.isClosed {
		return false
	}

	if b.SenderConn != nil {
		log.Printf("[Bridge %s] Thay thế kết nối SENDER cũ bằng kết nối mới (Client Retry)", b.StreamId)
		oldConn := b.SenderConn
		b.SenderConn = NewSafeConn(conn)
		_ = oldConn.Close()
	} else {
		b.SenderConn = NewSafeConn(conn)
	}

	b.senderOnce.Do(func() {
		close(b.senderReady)
	})

	// Bắt đầu đọc từ Sender ngay để xử lý Ping/Pong và nhận dữ liệu vào RAM
	go b.readLoop(b.SenderConn, b.fromSender, "Sender")
	return true
}

// AttachReceiver gắn kết nối của Receiver vào cầu nối và kích hoạt vòng đọc RAM tức thì.
func (b *ChannelBridge) AttachReceiver(conn *websocket.Conn) bool {
	b.mu.Lock()
	defer b.mu.Unlock()

	if b.isClosed {
		return false
	}

	if b.ReceiverConn != nil {
		log.Printf("[Bridge %s] Thay thế kết nối RECEIVER cũ bằng kết nối mới (Client Retry)", b.StreamId)
		oldConn := b.ReceiverConn
		b.ReceiverConn = NewSafeConn(conn)
		_ = oldConn.Close()
	} else {
		b.ReceiverConn = NewSafeConn(conn)
	}

	b.receiverOnce.Do(func() {
		close(b.receiverReady)
	})

	// Bắt đầu đọc từ Receiver ngay để xử lý Ping/Pong và nhận dữ liệu vào RAM
	go b.readLoop(b.ReceiverConn, b.fromReceiver, "Receiver")
	return true
}

func (b *ChannelBridge) readLoop(conn *SafeConn, out chan<- StreamPayload, role string) {
	defer func() {
		b.mu.Lock()
		isCurrent := false
		if role == "Sender" && b.SenderConn == conn {
			isCurrent = true
		} else if role == "Receiver" && b.ReceiverConn == conn {
			isCurrent = true
		}
		b.mu.Unlock()

		if isCurrent {
			b.Close()
		}
	}()

	for {
		msgType, data, err := conn.ReadMessage()
		if err != nil {
			if !b.isClosed {
				log.Printf("[Bridge %s] Kết nối %s bị ngắt: %v", b.StreamId, role, err)
			}
			return
		}

		select {
		case <-b.done:
			return
		case out <- StreamPayload{MsgType: msgType, Data: data}:
		case <-time.After(30 * time.Second):
			log.Printf("[Bridge %s] Bộ đệm RAM chuyển tiếp từ %s đầy quá 30s. Hủy phiên.", b.StreamId, role)
			return
		}
	}
}

// StartForwarding bắt đầu vòng lặp chuyển tiếp dữ liệu nhị phân hai chiều giữa Sender và Receiver.
func (b *ChannelBridge) StartForwarding() {
	// Chờ cả 2 bên Sender và Receiver gắn kết nối trong tối đa 60 giây
	select {
	case <-b.done:
		return
	case <-b.senderReady:
	case <-time.After(60 * time.Second):
		log.Printf("[Bridge %s] Quá thời gian chờ Sender ghép cặp (60s). Hủy cầu nối.", b.StreamId)
		b.Close()
		return
	}

	select {
	case <-b.done:
		return
	case <-b.receiverReady:
	case <-time.After(60 * time.Second):
		log.Printf("[Bridge %s] Quá thời gian chờ Receiver ghép cặp (60s). Hủy cầu nối.", b.StreamId)
		b.Close()
		return
	}

	log.Printf("[Bridge %s] Cả hai bên Sender và Receiver đã sẵn sàng. Bắt đầu chuyển tiếp E2EE...", b.StreamId)

	var wg sync.WaitGroup
	wg.Add(2)

	// Goroutine 1: Chuyển tiếp từ Sender sang Receiver (Data Chunks, Handshake, Manifest)
	go func() {
		defer wg.Done()
		for {
			select {
			case <-b.done:
				return
			case msg, ok := <-b.fromSender:
				if !ok {
					return
				}
				b.mu.Lock()
				rc := b.ReceiverConn
				b.mu.Unlock()
				if rc == nil {
					return
				}
				if err := rc.WriteMessage(msg.MsgType, msg.Data); err != nil {
					log.Printf("[Bridge %s] Lỗi gửi dữ liệu Sender -> Receiver: %v", b.StreamId, err)
					b.Close()
					return
				}
			}
		}
	}()

	// Goroutine 2: Chuyển tiếp từ Receiver sang Sender (Chunk ACKs, Manifest Response, Verify Result)
	go func() {
		defer wg.Done()
		for {
			select {
			case <-b.done:
				return
			case msg, ok := <-b.fromReceiver:
				if !ok {
					return
				}
				b.mu.Lock()
				sc := b.SenderConn
				b.mu.Unlock()
				if sc == nil {
					return
				}
				if err := sc.WriteMessage(msg.MsgType, msg.Data); err != nil {
					log.Printf("[Bridge %s] Lỗi gửi dữ liệu Receiver -> Sender: %v", b.StreamId, err)
					b.Close()
					return
				}
			}
		}
	}()

	wg.Wait()
	log.Printf("[Bridge %s] Phiên chuyển tiếp hoàn tất và đã đóng an toàn.", b.StreamId)
}

// Close dọn dẹp và đóng cả hai kết nối an toàn.
func (b *ChannelBridge) Close() {
	b.closeOnce.Do(func() {
		b.mu.Lock()
		b.isClosed = true
		sc := b.SenderConn
		rc := b.ReceiverConn
		b.mu.Unlock()

		b.doneOnce.Do(func() {
			close(b.done)
		})

		if sc != nil {
			_ = sc.Close()
		}
		if rc != nil {
			_ = rc.Close()
		}

		if b.onClosed != nil {
			b.onClosed(b.StreamId)
		}
	})
}
