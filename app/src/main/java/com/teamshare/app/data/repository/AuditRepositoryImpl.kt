package com.teamshare.app.data.repository

import com.teamshare.app.data.local.db.dao.AuditLogDao
import com.teamshare.app.data.local.db.mapper.toDomain
import com.teamshare.app.data.local.db.mapper.toEntity
import com.teamshare.app.domain.model.SecurityAuditLog
import com.teamshare.app.domain.repository.IAuditRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Hiện thực hóa IAuditRepository lưu trữ và tra cứu Nhật ký kiểm toán an ninh bất biến.
 */
class AuditRepositoryImpl(
    private val auditDao: AuditLogDao
) : IAuditRepository {

    override suspend fun logEvent(log: SecurityAuditLog): Unit = withContext(Dispatchers.IO) {
        auditDao.insertLog(log.toEntity())
    }

    override suspend fun recordLog(log: SecurityAuditLog): Unit = logEvent(log)

    override fun getRecentLogs(limit: Int): Flow<List<SecurityAuditLog>> {
        return auditDao.getRecentLogs(limit).map { list -> list.map { it.toDomain() } }
    }

    override fun getLogsBySession(sessionId: String): Flow<List<SecurityAuditLog>> {
        return auditDao.getLogsBySession(sessionId).map { list -> list.map { it.toDomain() } }
    }

    override fun getLogsByTeam(teamId: String): Flow<List<SecurityAuditLog>> {
        return auditDao.getLogsByTeam(teamId).map { list -> list.map { it.toDomain() } }
    }

    override suspend fun getLogsSince(teamId: String, sinceTimestamp: Long): List<SecurityAuditLog> = withContext(Dispatchers.IO) {
        auditDao.getLogsSince(teamId, sinceTimestamp).map { it.toDomain() }
    }

    override suspend fun saveLogs(logs: List<SecurityAuditLog>): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            auditDao.insertLogs(logs.map { it.toEntity() })
            Unit
        }
    }
}
