package com.filetransfer.app.domain.usecase.audit

import com.filetransfer.app.domain.model.AuditLog
import com.filetransfer.app.domain.repository.IAuditRepository
import kotlinx.coroutines.flow.Flow

/**
 * UC-20: Ghi nhận & Tra cứu nhật ký hoạt động nhóm (SRS FR-AUD-01).
 * Truy vấn nhật ký kiểm toán an ninh bất biến theo không gian nhóm hoặc danh sách sự kiện gần nhất.
 */
class QuerySecurityAuditUseCase(
    private val auditRepository: IAuditRepository
) {
    operator fun invoke(teamId: String? = null, limit: Int = 100): Flow<List<AuditLog>> {
        return if (!teamId.isNullOrBlank()) {
            auditRepository.getLogsByTeam(teamId)
        } else {
            auditRepository.getRecentLogs(limit)
        }
    }
}

