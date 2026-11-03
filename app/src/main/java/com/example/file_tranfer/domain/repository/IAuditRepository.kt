package com.example.file_tranfer.domain.repository

import com.example.file_tranfer.domain.model.AuditLog
import kotlinx.coroutines.flow.Flow

/**
 * Hợp đồng dữ liệu lưu vết nhật ký an ninh và kiểm toán phiên.
 */
interface IAuditRepository {
    suspend fun recordAudit(log: AuditLog)
    suspend fun getAuditLogsBySession(sessionId: String): List<AuditLog>
    fun getAllAuditLogsFlow(): Flow<List<AuditLog>>
}

