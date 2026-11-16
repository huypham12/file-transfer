package com.filetransfer.app.domain.model

/**
 * Hướng truyền tải dữ liệu của thiết bị trong phiên Secure Drop (SDD Chương 4).
 */
enum class TransferDirection {
    SENDER,
    RECEIVER;

    val isSender: Boolean get() = this == SENDER
    val isReceiver: Boolean get() = this == RECEIVER
}

/**
 * Chế độ phê duyệt khi máy nhận kết nối tới phiên phát mã QR (FR-SND-04).
 */
enum class ApprovalMode {
    MANUAL_APPROVAL,
    AUTO_ACCEPT
}

/**
 * Phân loại định dạng nội dung truyền tải (SDD Chương 5 & 6).
 */
enum class TransferType {
    SINGLE_FILE,
    BATCH_FILES
}

/**
 * Loại kênh truyền tải mạng kết nối hai thiết bị (Direct LAN TLS 1.3 hoặc Relay E2EE).
 */
enum class TransferChannel {
    DIRECT_LAN,
    RELAY_E2EE;

    val isLan: Boolean get() = this == DIRECT_LAN
    val isRelay: Boolean get() = this == RELAY_E2EE
}

/**
 * Trạng thái tổng thể của phiên truyền dữ liệu (TransferSession) theo SDD Chương 5.
 */
enum class SessionStatus {
    PENDING,
    IDLE,
    HANDSHAKING,
    TRANSFERRING,
    PAUSED,
    VERIFYING,
    COMPLETED,
    CANCELLED,
    FAILED
}

/**
 * Mức độ nghiêm trọng của sự kiện kiểm toán an ninh.
 */
enum class AuditSeverity {
    INFO,
    WARNING,
    SECURITY_ALERT
}

/**
 * Các loại sự kiện được ghi vết trong nhật ký kiểm toán an ninh bất biến (SDD Chương 5).
 */
enum class AuditEventType {
    SESSION_CREATED,
    SESSION_APPROVED,
    SESSION_REJECTED,
    DEVICE_CONNECTED,
    TRANSFER_STARTED,
    TRANSFER_COMPLETED,
    TRANSFER_PAUSED,
    TRANSFER_RESUMED,
    TRANSFER_CANCELLED,
    CHECKSUM_VERIFIED,
    CHECKSUM_FAILED,
    SECURITY_ALERT
}

/**
 * Bảng mã lỗi chuẩn hóa hệ thống định nghĩa theo SRS và SDD.
 */
object ErrorCode {
    const val ERR_INVALID_CREDENTIAL = "0x101"
    const val ERR_DEVICE_REJECTED = "0x102"
    const val ERR_SESSION_EXPIRED = "0x103"
    const val ERR_INVALID_ROLE = "0x104"
    const val ERR_SESSION_FULL = "0x105"
    const val ERR_RELAY_UNAVAILABLE = "0x201"
    const val ERR_SERVER_TIMEOUT = "0x202"
    const val ERR_SERVER_BIND_ERROR = "0x203"
    const val ERR_CHECKSUM_MISMATCH = "0x301"
    const val ERR_INSUFFICIENT_STORAGE = "0x302"
    const val ERR_RELAY_CHANNEL_BUSY = "0x303"
    const val ERR_BIOMETRIC_FAILED = "0x401"
    const val ERR_SYSTEM_EXCEPTION = "0x500"
}

/**
 * Lệnh điều khiển phiên truyền tải dữ liệu P2P (UC-15 / FR-TRF-05).
 */
enum class SessionCmd {
    PAUSE,
    RESUME,
    CANCEL,
    REVOKE
}

/**
 * Hành động điều khiển Foreground Service nền tảng (UC-18 / FR-MOB-03).
 */
enum class ServiceAction {
    START,
    PAUSE,
    RESUME,
    CANCEL,
    STOP
}

/**
 * Trạng thái mạng kèm kênh truyền tải khuyến nghị (UC-19 / FR-MOB-04).
 */
data class NetworkAdaptiveState(
    val isOnline: Boolean,
    val currentNetwork: String,
    val recommendedChannel: TransferChannel,
    val message: String
)
