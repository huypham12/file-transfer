package com.teamshare.app.core.constant

/**
 * Trung tâm Khai báo Hằng số Kỹ thuật Toàn diện của Hệ thống (Single Source of Truth).
 * Tập trung toàn bộ các con số, kích thước bộ đệm, ngưỡng fsync, timeouts và đường dẫn sandbox.
 */
object AppConstants {

    object Network {
        const val DEFAULT_LAN_PORT = 8889
        const val DEFAULT_RELAY_PORT = 8080
        const val DEFAULT_SYNC_PORT = 8988
        const val DEFAULT_LOOPBACK_HOST = "10.0.2.2"
        const val DEFAULT_FALLBACK_IP = "127.0.0.1"
    }

    object Buffer {
        /** Kích thước khối dữ liệu truyền tải nhị phân chuẩn (64 KB) */
        const val CHUNK_SIZE_BYTES = 64 * 1024

        /** Sức chứa tối đa của BufferPoolManager (64 buffers = tối đa 4 MB RAM) */
        const val MAX_POOL_CAPACITY = 64

        /** Kích thước bộ đệm gửi và nhận Socket TCP tầng OS (512 KB) */
        const val SOCKET_BUFFER_BYTES = 512 * 1024

        /** Kích thước ống dẫn PipedInputStream cho nén Zip Stream (256 KB) */
        const val STREAM_PIPE_BYTES = 256 * 1024

        /** Kích thước bộ đệm luồng BufferedInputStream đọc socket (128 KB) */
        const val STREAM_INPUT_BUFFER_BYTES = 128 * 1024
    }

    object FlowControl {
        /** Ngưỡng tích lũy byte để fsync đĩa và gửi CHUNK_ACK qua kênh Relay (2 MB) */
        const val CHECKPOINT_THRESHOLD_RELAY = 2 * 1024 * 1024L

        /** Ngưỡng tích lũy byte để fsync đĩa và gửi CHUNK_ACK qua mạng LAN tốc độ cao (8 MB) */
        const val CHECKPOINT_THRESHOLD_LAN = 8 * 1024 * 1024L

        /** Dung lượng tối đa dữ liệu bay trước chờ ACK (Sliding Window Flow Control: 4 MB) */
        const val MAX_INFLIGHT_BYTES = 4 * 1024 * 1024L

        /** Dung lượng bộ nhớ trống an toàn tối thiểu trên đĩa cần kiểm tra trước khi nhận (+50 MB) */
        const val DISK_SAFETY_MARGIN_BYTES = 50 * 1024 * 1024L

        /** Số lượng tối đa các phiên truyền tải tệp gửi ra đồng thời (Outgoing Semaphore) */
        const val MAX_CONCURRENT_OUTGOING_TRANSFERS = 3
    }

    object Timeout {
        /** Thời gian tối đa thăm dò kết nối LAN (6 giây) trước khi tự động chuyển tiếp sang Relay */
        const val LAN_FAST_PROBE_MS = 6000L

        /** Thời gian chờ kết nối Socket tiêu chuẩn (15 giây) */
        const val SOCKET_CONNECT_TIMEOUT_MS = 15000

        /** Thời gian chờ đọc dữ liệu Socket tiêu chuẩn (15 giây) */
        const val SOCKET_READ_TIMEOUT_MS = 15000

        /** Chu kỳ gửi Heartbeat Ping qua kết nối WebSocket WSS để duy trì NAT mapping (15 giây) */
        const val OKHTTP_PING_INTERVAL_SEC = 15L

        /** Thời gian timeout đọc/ghi luồng WebSocket WSS (60 giây) */
        const val OKHTTP_TIMEOUT_SEC = 60L

        /** Thời hạn hiệu lực của mã QR phiên chia sẻ (5 phút tính theo milliseconds) */
        const val QR_TTL_MS = 300_000L

        /** Thời hạn hiệu lực của mã QR tính theo giây (300 giây) */
        const val QR_TTL_SECONDS = 300L

        /** Thời gian tối đa lắng nghe kết nối ServerSocket (5 phút) */
        const val SERVER_SOCKET_ACCEPT_TIMEOUT_MS = 300_000

        /** Thời gian tối đa chờ người dùng thao tác phê duyệt thủ công kết nối (60 giây) */
        const val APPROVAL_WAIT_TIMEOUT_MS = 60_000

        /** Tần suất điều tiết tối thiểu giữa các lần cập nhật thông báo Notification / UI (500 ms) */
        const val PROGRESS_THROTTLE_INTERVAL_MS = 500L

        /** Thời gian tối đa giữ WakeLock của hệ điều hành (2 giờ) */
        const val MAX_WAKELOCK_TIMEOUT_MS = 2 * 60 * 60 * 1000L
    }

    object Retry {
        /** Số lần tối đa thử lại kết nối chuyển tiếp qua Relay Server */
        const val MAX_RELAY_RETRIES = 3

        /** Danh sách độ trễ giãn cách Exponential Backoff (1s, 2s, 4s) */
        val BACKOFF_DELAYS_MS = listOf(1000L, 2000L, 4000L)
    }

    object StorageDirectory {
        const val TRANSFERS = "transfers"
        const val COMPLETED = "completed"
        const val LOGS = "logs"
        const val KEYSTORE_META = ".keystore_meta"

        const val PART_FILE_PREFIX = ".part_"
        const val BATCH_DIR_PREFIX = "batch_"
        const val SESSION_DIR_PREFIX = "session_"
        const val TEAM_DIR_PREFIX = "team_"
    }

    object ServiceAction {
        const val START = "com.teamshare.app.service.START_TRANSFER"
        const val PAUSE = "com.teamshare.app.service.PAUSE_TRANSFER"
        const val RESUME = "com.teamshare.app.service.RESUME_TRANSFER"
        const val CANCEL = "com.teamshare.app.service.CANCEL_TRANSFER"
        const val STOP = "com.teamshare.app.service.STOP_SERVICE"
    }

    object NotificationChannel {
        const val TRANSFER_ID = "teamshare_transfer_status"
        const val EVENTS_ID = "teamshare_transfer_events"
        const val ID_TRANSFER = 1001
        const val ID_COMPLETED = 1002
    }

    object Crypto {
        const val ALGORITHM_EC = "EC"
        const val ALGORITHM_ECDH = "ECDH"
        const val ALGORITHM_AES = "AES"
        const val CIPHER_AES_GCM = "AES/GCM/NoPadding"
        const val HASH_SHA256 = "SHA-256"
        const val SIGNATURE_ECDSA = "SHA256withECDSA"
        const val KEY_SIZE_BITS = 256
        const val GCM_IV_LENGTH_BYTES = 12
        const val GCM_TAG_LENGTH_BITS = 128
    }

    object Preferences {
        const val NETWORK_CONFIG = "teamshare_network_config"
        const val KEY_RELAY_HOST = "relay_host"
    }
}

