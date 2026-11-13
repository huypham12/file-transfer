# filetransfer Zero-Knowledge Relay Server

Máy chủ chuyển tiếp Internet không lưu trữ (Stateless In-Memory WebSocket Relay) phục vụ ứng dụng **filetransfer**.

## 1. Nguyên tắc An ninh & Cam kết Cốt tử
- **Zero-Knowledge (Không giải mã):** Dữ liệu truyền qua máy chủ là các gói nhị phân đã được mã hóa đầu cuối E2EE (AES-256-GCM với Ephemeral ECDH Key). Relay Server không sở hữu khóa giải mã.
- **No-Disk Storage (Không lưu đĩa):** Dữ liệu dòng byte chuyển tiếp trực tiếp giữa hai socket thông qua goroutine pipe trong RAM. Tuyệt đối không tạo, ghi hoặc lưu trữ tệp trên đĩa cứng máy chủ.
- **Tự động giải phóng:** Khi một trong hai bên ngắt kết nối hoặc hết hạn chờ (60s), cầu nối (`ChannelBridge`) trong RAM lập tức bị hủy bỏ.

## 2. Giao thức Kết nối
- Endpoint: `wss://relay.filetransfer.internal/v1/stream`
- Bản tin bắt tay khởi tạo (`ATTACH_STREAM`):
  ```json
  {
    "action": "ATTACH_STREAM",
    "streamId": "uuid-cua-phien-truyen",
    "role": "SENDER" // hoặc "RECEIVER"
  }
  ```
- Endpoint kiểm tra sức khỏe: `GET /health`

## 3. Khởi chạy bằng Docker Compose
```bash
docker compose up -d --build
```
Kiểm tra log:
```bash
docker compose logs -f relay_backend
```

