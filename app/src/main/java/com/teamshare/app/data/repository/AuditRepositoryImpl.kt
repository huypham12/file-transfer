package com.teamshare.app.data.repository

import android.database.sqlite.SQLiteConstraintException
import com.teamshare.app.core.security.sanitizer.SafeLogger
import com.teamshare.app.data.local.db.dao.AuditLogDao
import com.teamshare.app.data.local.db.dao.TransferSessionDao
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
 * Tự động phòng vệ lỗi ràng buộc khóa ngoại (Foreign Key Constraint) khi ghi log cho các phiên
 * chưa tồn tại trong bảng transfer_sessions (ví dụ: quét QR từ máy đối tác, cảnh báo bộ nhớ trước khi tạo phiên).
 */
class AuditRepositoryImpl(
    private val auditDao: AuditLogDao,
    private val sessionDao: TransferSessionDao? = null
) : IAuditRepository {

    override suspend fun logEvent(log: SecurityAuditLog): Unit = withContext(Dispatchers.IO) {
        try {
            val entity = log.toEntity()
            // Kiểm tra tính hợp lệ của session_id đối với bảng transfer_sessions
            val validSessionId = if (!log.sessionId.isNullOrBlank() && sessionDao != null) {
                if (sessionDao.getSessionById(log.sessionId) != null) log.sessionId else null
            } else {
                entity.sessionId
            }

            try {
                auditDao.insertLog(entity.copy(sessionId = validSessionId))
            } catch (e: SQLiteConstraintException) {
                // Phòng vệ khi SQLite báo lỗi Foreign Key: ghi log với sessionId = null và bảo toàn mã phiên trong details
                val safeDetails = if (!log.sessionId.isNullOrBlank()) {
                    "[Phiên: ${log.sessionId}] ${log.details}"
                } else {
                    log.details
                }
                auditDao.insertLog(entity.copy(sessionId = null, details = safeDetails))
            }
        } catch (e: Throwable) {
            SafeLogger.e("AuditRepository", "Không thể ghi nhật ký kiểm toán: ${e.message}")
        }
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
