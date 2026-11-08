package com.teamshare.app.domain.model

/**
 * Bản ghi nhật ký kiểm toán an ninh bất biến trên thiết bị theo SRS FR-AUD-01 & SDD Chương 4 & 5.
 *
 * @property logId Khóa chính tự tăng của bản ghi
 * @property sessionId Mã phiên truyền tải liên kết (Foreign Key, nullable ON DELETE SET NULL)
 * @property timestamp Dấu thời gian ghi nhận sự kiện (epoch millis)
 * @property eventType Loại sự kiện kiểm toán an ninh (AuditEventType)
 * @property actorDevice Tên định danh/model thiết bị thực hiện thao tác (vd: Redmi Turbo 4 Pro, LOCAL_DEVICE)
 * @property channel Kênh mạng xảy ra sự kiện (DIRECT_LAN, RELAY_E2EE, v.v.)
 * @property severity Mức độ nghiêm trọng của sự kiện (INFO, WARNING, SECURITY_ALERT)
 * @property details Thông tin chi tiết mô tả sự kiện kiểm toán
 */
data class SecurityAuditLog(
    val logId: Long = 0L,
    val sessionId: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val eventType: AuditEventType,
    val actorDevice: String = "",
    val channel: String = "",
    val severity: AuditSeverity = AuditSeverity.INFO,
    val details: String,

    // Deprecated fields with defaults for backward compatibility
    @Deprecated("Legacy teamId")
    val teamId: String = "",
    @Deprecated("Legacy actorId")
    val actorId: String = "",
    @Deprecated("Legacy actorName")
    val actorName: String = "",
    @Deprecated("Legacy signature")
    val signature: String = ""
)

/**
 * Typealias đảm bảo tương thích với các use case và ViewModel hiện có.
 */
typealias AuditLog = SecurityAuditLog
