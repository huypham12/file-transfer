package com.teamshare.app.domain.repository

import com.teamshare.app.domain.model.AuditLog
import kotlinx.coroutines.flow.Flow

/**
 * Hợp đồng dữ liệu lưu trữ và tra cứu Nhật ký kiểm toán an ninh bất biến.
 */
interface IAuditRepository {
    suspend fun logEvent(log: AuditLog)
    fun getLogsByTeam(teamId: String): Flow<List<AuditLog>>
    fun getRecentLogs(limit: Int = 100): Flow<List<AuditLog>>
    suspend fun getLogsSince(teamId: String, sinceTimestamp: Long): List<AuditLog> = emptyList()
    suspend fun saveLogs(logs: List<AuditLog>): Result<Unit> = Result.success(Unit)
}

