package com.teamshare.app.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.teamshare.app.data.local.db.entity.AuditEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object cho bảng 'security_audit_logs'.
 * Chỉ cho phép thao tác ghi thêm (Append-only), không cho phép sửa đổi các bản ghi cũ.
 */
@Dao
interface AuditDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: AuditEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLogs(logs: List<AuditEntity>): List<Long>

    @Query("SELECT * FROM security_audit_logs WHERE team_id = :teamId ORDER BY timestamp DESC")
    fun getLogsByTeam(teamId: String): Flow<List<AuditEntity>>

    @Query("SELECT * FROM security_audit_logs WHERE team_id = :teamId AND timestamp > :sinceTimestamp ORDER BY timestamp ASC")
    suspend fun getLogsSince(teamId: String, sinceTimestamp: Long): List<AuditEntity>

    @Query("SELECT * FROM security_audit_logs ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentLogs(limit: Int): Flow<List<AuditEntity>>
}

