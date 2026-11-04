package com.teamshare.app.domain.model

/**
 * Hướng truyền tải dữ liệu của thiết bị trong phiên P2P.
 */
enum class TransferDirection {
    INCOMING,
    OUTGOING
}

/**
 * Loại kênh truyền tải mạng kết nối hai thiết bị.
 */
enum class TransferChannel {
    DIRECT_LAN,
    ZERO_KNOWLEDGE_RELAY
}

/**
 * Trạng thái của Không gian Nhóm cộng tác.
 */
enum class TeamStatus {
    ACTIVE,
    DISBANDED
}

/**
 * Vai trò của thành viên trong nhóm cộng tác (RBAC).
 */
enum class MemberRole {
    OWNER,
    MEMBER
}

/**
 * Trạng thái tệp tin trên máy cục bộ so với không gian nhóm.
 */
enum class DownloadStatus {
    NOT_DOWNLOADED,
    DOWNLOADING,
    COMPLETED
}

/**
 * Trạng thái tổng thể của phiên truyền dữ liệu (TransferSession).
 */
enum class SessionStatus {
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
 * Các loại sự kiện được ghi vết trong nhật ký hoạt động nhóm.
 */
enum class AuditEventType {
    TEAM_CREATED,
    TEAM_DISBANDED,
    INVITATION_GENERATED,
    MEMBER_JOINED,
    MEMBER_REVOKED,
    DOCUMENT_PUBLISHED,
    TRANSFER_STARTED,
    TRANSFER_COMPLETED,
    TRANSFER_PAUSED,
    CHECKSUM_FAILED,
    SECURITY_ALERT
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
}

/**
 * Bảng mã lỗi chuẩn hóa hệ thống định nghĩa theo SRS và SDD.
 */
object ErrorCode {
    const val ERR_INVALID_CREDENTIAL = "0x101"
    const val ERR_MEMBER_REVOKED = "0x102"
    const val ERR_INVITATION_EXPIRED = "0x103"
    const val ERR_INVALID_ROLE = "0x104"
    const val ERR_RELAY_UNAVAILABLE = "0x201"
    const val ERR_CHECKSUM_MISMATCH = "0x301"
    const val ERR_INSUFFICIENT_STORAGE = "0x302"
    const val ERR_BIOMETRIC_FAILED = "0x401"
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

