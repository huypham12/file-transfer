package com.example.file_tranfer.domain.model

enum class AuditSeverity {
    INFO,
    WARNING,
    CRITICAL
}

/**
 * Bản ghi nhật ký an ninh và kiểm toán phiên truyền P2P.
 */
data class AuditLog(
    val id: Long = 0L,
    val timestamp: Long = System.currentTimeMillis(),
    val eventType: String,
    val severity: AuditSeverity = AuditSeverity.INFO,
    val sourceIp: String? = null,
    val sessionId: String? = null,
    val details: String
)

