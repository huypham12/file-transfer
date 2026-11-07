package com.teamshare.app.domain.model

/**
 * Bản ghi nhật ký hoạt động nhóm và kiểm toán an ninh bất biến.
 */
data class AuditLog(
    val logId: Long = 0L,
    val teamId: String,
    val actorId: String,
    val timestamp: Long = System.currentTimeMillis(),
    val eventType: AuditEventType,
    val severity: AuditSeverity = AuditSeverity.INFO,
    val details: String,
    val actorName: String = "",
    val signature: String = ""
)

