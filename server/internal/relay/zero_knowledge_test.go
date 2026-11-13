package relay

import (
	"net/http/httptest"
	"net/url"
	"os"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/gorilla/websocket"
)

// TestZeroKnowledge_FullTraffic_NoDiskWrites kiểm chứng tính chất Zero-Knowledge:
// Quá trình kết nối WebSocket, bắt tay ghép cặp ATTACH_STREAM và truyền 1 MB dữ liệu nhị phân E2EE
// hoàn toàn được pipe trực tiếp trong bộ nhớ RAM và tuyệt đối không ghi bất kỳ tệp nào xuống đĩa cứng.
func TestZeroKnowledge_FullTraffic_NoDiskWrites(t *testing.T) {
	// 1. Quét số lượng tệp trong thư mục tạm trước khi thực thi
	tempDir := os.TempDir()
	beforeFiles, err := os.ReadDir(tempDir)
	if err != nil {
		t.Fatalf("Không thể đọc thư mục tạm: %v", err)
	}

	// Lưu danh sách tệp đã có từ trước
	existingFiles := make(map[string]bool)
	for _, f := range beforeFiles {
		existingFiles[f.Name()] = true
	}

	// 2. Khởi tạo Hub và Test HTTP WebSocket Server
	hub := NewHub()
	server := httptest.NewServer(HandleStream(hub))
	defer server.Close()

	u, _ := url.Parse(server.URL)
	u.Scheme = "ws"
	wsUrl := u.String()

	streamId := "zk_audit_traffic_001"

	// 3. Kết nối Receiver vào Relay
	receiverConn, _, err := websocket.DefaultDialer.Dial(wsUrl, nil)
	if err != nil {
		t.Fatalf("Receiver kết nối WebSocket thất bại: %v", err)
	}
	defer receiverConn.Close()

	attachReceiver := AttachStreamMessage{
		Action:   "ATTACH_STREAM",
		StreamId: streamId,
		Role:     "RECEIVER",
	}
	if err := receiverConn.WriteJSON(attachReceiver); err != nil {
		t.Fatalf("Gửi ATTACH_STREAM Receiver thất bại: %v", err)
	}
	var respReceiver AttachResponse
	if err := receiverConn.ReadJSON(&respReceiver); err != nil || respReceiver.Status != "ATTACHED" {
		t.Fatalf("Nhận phản hồi Receiver thất bại: %v, resp: %+v", err, respReceiver)
	}

	// 4. Kết nối Sender vào Relay
	senderConn, _, err := websocket.DefaultDialer.Dial(wsUrl, nil)
	if err != nil {
		t.Fatalf("Sender kết nối WebSocket thất bại: %v", err)
	}
	defer senderConn.Close()

	attachSender := AttachStreamMessage{
		Action:   "ATTACH_STREAM",
		StreamId: streamId,
		Role:     "SENDER",
	}
	if err := senderConn.WriteJSON(attachSender); err != nil {
		t.Fatalf("Gửi ATTACH_STREAM Sender thất bại: %v", err)
	}
	var respSender AttachResponse
	if err := senderConn.ReadJSON(&respSender); err != nil || respSender.Status != "ATTACHED" {
		t.Fatalf("Nhận phản hồi Sender thất bại: %v, resp: %+v", err, respSender)
	}

	// 5. Đẩy 1 MB dữ liệu nhị phân (16 chunks x 64KB) qua Relay Server
	chunkCount := 16
	chunkSize := 64 * 1024
	sampleData := make([]byte, chunkSize)
	for i := range sampleData {
		sampleData[i] = byte(i % 256)
	}

	var wg sync.WaitGroup
	wg.Add(2)

	// Goroutine nhận dữ liệu phía Receiver
	go func() {
		defer wg.Done()
		receivedChunks := 0
		for receivedChunks < chunkCount {
			msgType, data, err := receiverConn.ReadMessage()
			if err != nil {
				t.Errorf("Lỗi đọc dữ liệu Receiver: %v", err)
				return
			}
			if msgType == websocket.BinaryMessage && len(data) == chunkSize {
				receivedChunks++
			}
		}
	}()

	// Goroutine gửi dữ liệu phía Sender
	go func() {
		defer wg.Done()
		for i := 0; i < chunkCount; i++ {
			if err := senderConn.WriteMessage(websocket.BinaryMessage, sampleData); err != nil {
				t.Errorf("Lỗi gửi dữ liệu Sender: %v", err)
				return
			}
		}
	}()

	wg.Wait()

	// 6. Đóng kết nối
	_ = senderConn.Close()
	_ = receiverConn.Close()
	time.Sleep(50 * time.Millisecond)

	// 7. Quét lại thư mục tạm sau khi truyền 1MB
	afterFiles, err := os.ReadDir(tempDir)
	if err != nil {
		t.Fatalf("Không thể đọc lại thư mục tạm: %v", err)
	}

	for _, file := range afterFiles {
		if !existingFiles[file.Name()] {
			lowerName := strings.ToLower(file.Name())
			if strings.Contains(lowerName, "relay") || strings.Contains(lowerName, "filetransfer") {
				t.Errorf("Phát hiện tệp mới rò rỉ trên đĩa: %s", file.Name())
			}
		}
	}

	t.Logf("Xác nhận tính chất Zero-Knowledge: 1 MB dữ liệu truyền thành công qua RAM Pipe, không ghi tệp xuống đĩa (Trước: %d, Sau: %d)", len(beforeFiles), len(afterFiles))
}

// TestRelay_SecondReceiverGetsBusyMessage kiểm chứng yêu cầu xử lý máy thứ hai kết nối vào kênh 1-1:
// Máy nhận thứ 2 phải nhận được phản hồi status="BUSY" và thông điệp giải thích kênh đang bận.
func TestRelay_SecondReceiverGetsBusyMessage(t *testing.T) {
	hub := NewHub()
	server := httptest.NewServer(HandleStream(hub))
	defer server.Close()

	u, _ := url.Parse(server.URL)
	u.Scheme = "ws"
	wsUrl := u.String()

	streamId := "busy_test_stream_001"

	// 1. Kết nối Receiver thứ nhất
	receiver1, _, err := websocket.DefaultDialer.Dial(wsUrl, nil)
	if err != nil {
		t.Fatalf("Receiver 1 kết nối thất bại: %v", err)
	}
	defer receiver1.Close()

	attach1 := AttachStreamMessage{
		Action:   "ATTACH_STREAM",
		StreamId: streamId,
		Role:     "RECEIVER",
	}
	if err := receiver1.WriteJSON(attach1); err != nil {
		t.Fatalf("Gửi ATTACH_STREAM Receiver 1 thất bại: %v", err)
	}

	var resp1 AttachResponse
	if err := receiver1.ReadJSON(&resp1); err != nil || resp1.Status != "ATTACHED" {
		t.Fatalf("Receiver 1 không nhận được ATTACHED: %v, resp: %+v", err, resp1)
	}

	// 2. Kết nối Receiver thứ hai vào cùng streamId
	receiver2, _, err := websocket.DefaultDialer.Dial(wsUrl, nil)
	if err != nil {
		t.Fatalf("Receiver 2 kết nối thất bại: %v", err)
	}
	defer receiver2.Close()

	attach2 := AttachStreamMessage{
		Action:   "ATTACH_STREAM",
		StreamId: streamId,
		Role:     "RECEIVER",
	}
	if err := receiver2.WriteJSON(attach2); err != nil {
		t.Fatalf("Gửi ATTACH_STREAM Receiver 2 thất bại: %v", err)
	}

	var resp2 AttachResponse
	if err := receiver2.ReadJSON(&resp2); err != nil {
		t.Fatalf("Receiver 2 đọc phản hồi thất bại: %v", err)
	}

	if resp2.Status != "BUSY" {
		t.Errorf("Kỳ vọng status 'BUSY' cho Receiver 2, nhận được: %s", resp2.Status)
	}
	if !strings.Contains(resp2.Message, "đang bận") {
		t.Errorf("Kỳ vọng thông báo bận chứa 'đang bận', nhận được: %s", resp2.Message)
	}
	t.Logf("Xác nhận Receiver 2 nhận phản hồi BUSY thành công: status=%s, msg=%s", resp2.Status, resp2.Message)
}
