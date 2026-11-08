package com.teamshare.app.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.teamshare.app.data.local.db.entity.SecurityAuditLogEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object cho bảng 'security_audit_logs' theo SDD Chương 5.
 * Cơ chế Append-only: Chỉ cho phép ghi thêm, không sửa đổi các bản ghi kiểm toán cũ.
 */
@Dao
interface AuditLogDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertLog(log: SecurityAuditLogEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLogs(logs: List<SecurityAuditLogEntity>): List<Long>

    @Query("SELECT * FROM security_audit_logs ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentLogs(limit: Int = 100): Flow<List<SecurityAuditLogEntity>>

    @Query("SELECT * FROM security_audit_logs WHERE session_id = :sessionId ORDER BY timestamp DESC")
    fun getLogsBySession(sessionId: String): Flow<List<SecurityAuditLogEntity>>

    @Query("SELECT * FROM security_audit_logs WHERE timestamp > :sinceTimestamp ORDER BY timestamp ASC")
    suspend fun getLogsSince(sinceTimestamp: Long): List<SecurityAuditLogEntity>

    @Query("SELECT * FROM security_audit_logs WHERE timestamp > :sinceTimestamp AND (:teamId = '' OR :teamId IS NOT NULL) ORDER BY timestamp ASC")
    suspend fun getLogsSince(teamId: String, sinceTimestamp: Long): List<SecurityAuditLogEntity>

    @Query("SELECT * FROM security_audit_logs WHERE (:teamId = '' OR :teamId IS NOT NULL) ORDER BY timestamp DESC")
    fun getLogsByTeam(teamId: String): Flow<List<SecurityAuditLogEntity>>

    @Query("DELETE FROM security_audit_logs")
    suspend fun clearLogs(): Int
}

/**
 * Typealias đảm bảo tương thích ngược với các thành phần cũ trong quá trình refactor.
 */
typealias AuditDao = AuditLogDao
