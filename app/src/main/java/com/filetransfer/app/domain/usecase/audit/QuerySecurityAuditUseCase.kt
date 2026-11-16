package com.filetransfer.app.domain.usecase.audit

import com.filetransfer.app.domain.model.SecurityAuditLog
import com.filetransfer.app.domain.repository.IAuditRepository
import kotlinx.coroutines.flow.Flow

/**
 * UC-20: Ghi nhận & Tra cứu nhật ký kiểm toán an ninh (SRS FR-AUD-01).
 * Truy vấn nhật ký kiểm toán an ninh bất biến theo phiên hoặc danh sách sự kiện gần nhất.
 */
class QuerySecurityAuditUseCase(
    private val auditRepository: IAuditRepository
) {
    operator fun invoke(sessionId: String? = null, limit: Int = 100): Flow<List<SecurityAuditLog>> {
        return if (!sessionId.isNullOrBlank()) {
            auditRepository.getLogsBySession(sessionId)
        } else {
            auditRepository.getRecentLogs(limit)
        }
    }
}
