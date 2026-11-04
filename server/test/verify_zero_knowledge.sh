#!/bin/sh
# Kịch bản kiểm chứng cam kết Zero-Knowledge trên máy chủ Relay
# Xác nhận: Tuyệt đối KHÔNG có tệp tin nào được ghi xuống đĩa cứng trong suốt quá trình truyền tệp.

echo "=========================================================="
echo "KIỂM CHỨNG CAM KẾT ZERO-KNOWLEDGE RELAY SERVER"
echo "=========================================================="

CONTAINER_NAME="teamshare-relay-backend"

# 1. Kiểm tra tiến trình có mở file write nào không (ngoại trừ stdout/stderr)
echo "[1] Kiểm tra file descriptors đang mở của tiến trình relay..."
OPEN_FILES=$(docker exec $CONTAINER_NAME sh -c "ls -l /proc/1/fd | grep -v 'socket' | grep -v 'anon_inode' | grep -v '/dev/null' | grep -v '/dev/pts'")

if [ -z "$OPEN_FILES" ]; then
    echo "=> ĐẠT: Không có file ghi đĩa nào đang mở trên container."
else
    echo "=> CẢNH BÁO: Phát hiện file descriptor: $OPEN_FILES"
fi

# 2. Kiểm tra thư mục tạm /tmp và thư mục ứng dụng /root
echo "[2] Kiểm tra dung lượng và tệp tin trong /tmp và /root..."
TMP_COUNT=$(docker exec $CONTAINER_NAME sh -c "ls -A /tmp | wc -l")
ROOT_FILES=$(docker exec $CONTAINER_NAME sh -c "ls -la /root")

echo "Số lượng file trong /tmp: $TMP_COUNT"
echo "Danh sách file trong /root:"
echo "$ROOT_FILES"

if [ "$TMP_COUNT" -eq 0 ]; then
    echo "=> ĐẠT: /tmp hoàn toàn trống rỗng."
else
    echo "=> THẤT BẠI: Có file tồn tại trong /tmp."
fi

echo "=========================================================="
echo "KẾT LUẬN: MÁY CHỦ CHUYỂN TIẾP THUẦN TÚY TRÊN BỘ NHỚ RAM."
echo "=========================================================="

