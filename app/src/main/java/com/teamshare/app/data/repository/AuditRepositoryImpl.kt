package com.teamshare.app.data.repository

import com.teamshare.app.data.local.db.dao.AuditDao
import com.teamshare.app.data.local.db.mapper.toDomain
import com.teamshare.app.data.local.db.mapper.toEntity
import com.teamshare.app.domain.model.AuditLog
import com.teamshare.app.domain.repository.IAuditRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Hiện thực hóa IAuditRepository lưu trữ và tra cứu Nhật ký kiểm toán an ninh bất biến.
 */
class AuditRepositoryImpl(
    private val auditDao: AuditDao
) : IAuditRepository {

    override suspend fun logEvent(log: AuditLog): Unit = withContext(Dispatchers.IO) {
        auditDao.insertLog(log.toEntity())
    }

    override fun getLogsByTeam(teamId: String): Flow<List<AuditLog>> {
        return auditDao.getLogsByTeam(teamId).map { list -> list.map { it.toDomain() } }
    }

    override fun getRecentLogs(limit: Int): Flow<List<AuditLog>> {
        return auditDao.getRecentLogs(limit).map { list -> list.map { it.toDomain() } }
    }
}

