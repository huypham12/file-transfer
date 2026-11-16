package com.filetransfer.app.domain.repository

import com.filetransfer.app.domain.model.SecurityAuditLog
import kotlinx.coroutines.flow.Flow

/**
 * Hợp đồng dữ liệu lưu trữ và tra cứu Nhật ký kiểm toán an ninh bất biến (SDD Chương 4 & 5).
 */
interface IAuditRepository {
    suspend fun logEvent(log: SecurityAuditLog)
    fun getRecentLogs(limit: Int = 100): Flow<List<SecurityAuditLog>>
    fun getLogsBySession(sessionId: String): Flow<List<SecurityAuditLog>>
}
