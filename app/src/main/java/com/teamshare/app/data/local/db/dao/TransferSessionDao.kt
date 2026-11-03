package com.teamshare.app.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.teamshare.app.data.local.db.entity.SessionEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object cho bảng 'transfer_sessions'.
 */
@Dao
interface TransferSessionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: SessionEntity): Long

    @Query("UPDATE transfer_sessions SET confirmed_offset = :offset WHERE session_id = :sessionId")
    suspend fun updateCheckpoint(sessionId: String, offset: Long): Int

    @Query("UPDATE transfer_sessions SET status = :status, completed_at = :completedAt, error_code = :errorCode WHERE session_id = :sessionId")
    suspend fun updateSessionStatus(
        sessionId: String,
        status: String,
        completedAt: Long?,
        errorCode: String?
    ): Int

    @Query("SELECT * FROM transfer_sessions WHERE session_id = :sessionId LIMIT 1")
    suspend fun getSessionById(sessionId: String): SessionEntity?

    @Query("SELECT * FROM transfer_sessions WHERE status IN ('TRANSFERRING', 'HANDSHAKING', 'PAUSED') ORDER BY started_at DESC")
    fun getActiveSessions(): Flow<List<SessionEntity>>

    @Query("DELETE FROM transfer_sessions WHERE session_id = :sessionId")
    suspend fun deleteSession(sessionId: String): Int
}

