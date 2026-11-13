# Giai đoạn 1: Biên dịch nhị phân siêu tối ưu
FROM golang:1.22-alpine AS builder

WORKDIR /app

# Tải trước các phụ thuộc để tận dụng Docker cache layer
COPY go.mod go.sum ./
RUN go mod download

# Sao chép toàn bộ mã nguồn và biên dịch nhị phân tĩnh
COPY . .
RUN CGO_ENABLED=0 GOOS=linux go build -ldflags="-w -s" -o filetransfer-relay ./cmd/relay

# Giai đoạn 2: Image siêu nhẹ triển khai thực tế
FROM alpine:3.19

RUN apk --no-cache add ca-certificates tzdata

WORKDIR /root/

COPY --from=builder /app/filetransfer-relay .

EXPOSE 8080

# Chạy dưới quyền người dùng không có đặc quyền root (Non-root user)
USER 10001:10001

CMD ["./filetransfer-relay"]

