package com.teamshare.app.domain.model

/**
 * Hướng truyền tải dữ liệu của thiết bị trong phiên Secure Drop (SDD Chương 4).
 */
enum class TransferDirection {
    SENDER,
    RECEIVER,
    INCOMING, // Backward compatibility alias
    OUTGOING; // Backward compatibility alias

    val isSender: Boolean get() = this == SENDER || this == OUTGOING
    val isReceiver: Boolean get() = this == RECEIVER || this == INCOMING
}

typealias SessionDirection = TransferDirection

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
    BATCH_FILES,
    @Deprecated("Chỉ giữ lại để duy trì tương thích ngược dữ liệu Room DB cũ")
    FOLDER_ZIP
}

/**
 * Loại kênh truyền tải mạng kết nối hai thiết bị (Direct LAN TLS 1.3 hoặc Relay E2EE).
 */
enum class TransferChannel {
    DIRECT_LAN,
    RELAY_E2EE,
    ZERO_KNOWLEDGE_RELAY; // Backward compatibility alias

    val isLan: Boolean get() = this == DIRECT_LAN
    val isRelay: Boolean get() = this == RELAY_E2EE || this == ZERO_KNOWLEDGE_RELAY
}

typealias ChannelType = TransferChannel

/**
 * Trạng thái của Không gian Nhóm cộng tác (Legacy).
 */
enum class TeamStatus {
    ACTIVE,
    DISBANDED
}

/**
 * Vai trò của thành viên trong nhóm cộng tác (RBAC Legacy).
 */
enum class MemberRole {
    OWNER,
    MEMBER
}

/**
 * Trạng thái tệp tin trên máy cục bộ so với không gian nhóm (Legacy).
 */
enum class DownloadStatus {
    NOT_DOWNLOADED,
    DOWNLOADING,
    COMPLETED
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
    // 1-N Drop Session Events
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
    SECURITY_ALERT,

    // Legacy Events (Backward compatibility)
    @Deprecated("Legacy team event") TEAM_CREATED,
    @Deprecated("Legacy team event") TEAM_DISBANDED,
    @Deprecated("Legacy team event") INVITATION_GENERATED,
    @Deprecated("Legacy team event") MEMBER_JOINED,
    @Deprecated("Legacy team event") MEMBER_REVOKED,
    @Deprecated("Legacy team event") DOCUMENT_PUBLISHED
}

/**
 * Mã loại khung tin nhị phân (Binary Frame MsgType) theo SDD Chương 6.
 */
object FrameType {
    const val HANDSHAKE_AUTH: Byte = 0x01
    const val TRANSFER_MANIFEST: Byte = 0x02
    const val MANIFEST_RESPONSE: Byte = 0x03
    const val DATA_CHUNK: Byte = 0x04
    const val CHUNK_ACK: Byte = 0x05
    const val CONTROL_SIGNAL: Byte = 0x06
    const val REVOKE_SIGNAL: Byte = 0x07
    const val VERIFY_RESULT: Byte = 0x08
    const val ERROR_ALERT: Byte = 0x08
    const val CONNECT_PENDING: Byte = 0x09
}

/**
 * Bảng mã lỗi chuẩn hóa hệ thống định nghĩa theo SRS và SDD.
 */
object ErrorCode {
    const val ERR_INVALID_CREDENTIAL = "0x101"
    const val ERR_DEVICE_REJECTED = "0x102"
    const val ERR_MEMBER_REVOKED = "0x102" // Alias tương thích ngược
    const val ERR_SESSION_EXPIRED = "0x103"
    const val ERR_INVITATION_EXPIRED = "0x103" // Alias tương thích ngược
    const val ERR_INVALID_ROLE = "0x104"
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
    CANCEL
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
