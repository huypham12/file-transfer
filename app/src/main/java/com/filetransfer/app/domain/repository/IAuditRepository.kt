package com.filetransfer.app.domain.repository

import com.filetransfer.app.domain.model.SecurityAuditLog
import kotlinx.coroutines.flow.Flow

/**
 * Hợp đồng dữ liệu lưu trữ và tra cứu Nhật ký kiểm toán an ninh bất biến (SDD Chương 4 & 5).
 */
interface IAuditRepository {
    suspend fun logEvent(log: SecurityAuditLog)
    suspend fun recordLog(log: SecurityAuditLog) = logEvent(log)
    fun getRecentLogs(limit: Int = 100): Flow<List<SecurityAuditLog>>
    fun getLogsBySession(sessionId: String): Flow<List<SecurityAuditLog>>
    fun getLogsByTeam(teamId: String): Flow<List<SecurityAuditLog>> = getRecentLogs(100)
    suspend fun getLogsSince(teamId: String, sinceTimestamp: Long): List<SecurityAuditLog> = emptyList()
    suspend fun saveLogs(logs: List<SecurityAuditLog>): Result<Unit> = Result.success(Unit)
}
