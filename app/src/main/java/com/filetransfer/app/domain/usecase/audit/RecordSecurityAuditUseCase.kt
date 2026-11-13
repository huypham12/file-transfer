package com.filetransfer.app.domain.usecase.audit

import com.filetransfer.app.domain.model.AuditEventType
import com.filetransfer.app.domain.model.AuditLog
import com.filetransfer.app.domain.model.AuditSeverity
import com.filetransfer.app.domain.repository.IAuditRepository

/**
 * SRS FR-AUD-01 & SDD Chương 5: Ghi vết sự kiện an ninh bảo mật vào nhật ký kiểm toán bất biến.
 */
class RecordSecurityAuditUseCase(
    private val auditRepository: IAuditRepository
) {
    suspend fun execute(
        eventType: AuditEventType,
        details: String,
        sessionId: String? = null,
        actorDevice: String = "LOCAL_DEVICE",
        channel: String = "",
        severity: AuditSeverity = AuditSeverity.INFO
    ): Result<Unit> {
        return try {
            val log = AuditLog(
                sessionId = sessionId,
                eventType = eventType,
                actorDevice = actorDevice,
                channel = channel,
                severity = severity,
                details = details
            )
            auditRepository.logEvent(log)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend operator fun invoke(
        eventType: AuditEventType,
        details: String,
        sessionId: String? = null,
        actorDevice: String = "LOCAL_DEVICE",
        channel: String = "",
        severity: AuditSeverity = AuditSeverity.INFO
    ): Result<Unit> = execute(eventType, details, sessionId, actorDevice, channel, severity)

    suspend operator fun invoke(log: AuditLog): Result<Unit> {
        return try {
            auditRepository.logEvent(log)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

