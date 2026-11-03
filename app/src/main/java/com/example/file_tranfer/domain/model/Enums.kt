package com.example.file_tranfer.domain.model

/**
 * Hướng truyền tải dữ liệu của thiết bị trong phiên P2P.
 */
enum class TransferDirection {
    SEND,
    RECEIVE
}

/**
 * Trạng thái tổng thể của phiên truyền dữ liệu (TransferSession).
 */
enum class SessionStatus {
    PENDING,
    ACTIVE,
    PAUSED,
    COMPLETED,
    CANCELLED,
    REVOKED,
    FAILED
}

/**
 * Trạng thái truyền tải của từng tệp tin riêng lẻ trong phiên.
 */
enum class FileTransferStatus {
    PENDING,
    TRANSFERRING,
    VERIFYING,
    COMPLETED,
    FAILED
}

/**
 * Mã loại khung tin nhị phân (Binary Frame MsgType) trên Data Plane và Control Plane.
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
 * Mã lỗi chuẩn hóa hệ thống định nghĩa theo SRS và SDD.
 */
enum class ErrorCode(val code: Int, val defaultMessage: String) {
    ERR_INSUFFICIENT_STORAGE(513, "Bộ nhớ máy nhận không đủ dung lượng khả dụng"),
    ERR_EXPIRED_POLICY(410, "Phiên chia sẻ đã hết thời hạn hiệu lực"),
    ERR_PASSCODE_MISMATCH(401, "Mã PIN xác thực không chính xác"),
    ERR_MAX_DOWNLOAD_REACHED(429, "Đã đạt giới hạn số lượt tải tối đa cho phép"),
    ERR_INTEGRITY_MISMATCH(422, "Mã băm SHA-256 không trùng khớp, tệp bị lỗi"),
    ERR_SOCKET_TIMEOUT(504, "Hết thời gian chờ phản hồi mạng"),
    ERR_SECURITY_VIOLATION(403, "Vi phạm chính sách an ninh hoặc phát hiện tấn công");

    companion object {
        fun fromCode(code: Int): ErrorCode? = entries.firstOrNull { it.code == code }
    }
}

