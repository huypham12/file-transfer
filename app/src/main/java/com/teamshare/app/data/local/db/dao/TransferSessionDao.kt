package com.teamshare.app.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.teamshare.app.data.local.db.entity.TransferSessionEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object cho bảng 'transfer_sessions' theo SDD Chương 5.
 */
@Dao
interface TransferSessionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: TransferSessionEntity): Long

    @Query("SELECT * FROM transfer_sessions WHERE session_id = :sessionId LIMIT 1")
    fun observeSessionById(sessionId: String): Flow<TransferSessionEntity?>

    @Query("SELECT * FROM transfer_sessions WHERE session_id = :sessionId LIMIT 1")
    suspend fun getSessionById(sessionId: String): TransferSessionEntity?

    @Query("SELECT * FROM transfer_sessions WHERE session_token = :token LIMIT 1")
    suspend fun findByToken(token: String): TransferSessionEntity?

    @Query("UPDATE transfer_sessions SET confirmed_offset = :offset WHERE session_id = :sessionId")
    suspend fun updateCheckpoint(sessionId: String, offset: Long): Int

    @Query("UPDATE transfer_sessions SET approval_mode = :mode WHERE session_id = :sessionId")
    suspend fun updateApprovalMode(sessionId: String, mode: String): Int

    @Query("UPDATE transfer_sessions SET peer_device_model = :peerModel, peer_fingerprint = :peerFingerprint WHERE session_id = :sessionId")
    suspend fun updatePeerInfo(sessionId: String, peerModel: String, peerFingerprint: String): Int

    @Query("UPDATE transfer_sessions SET temp_file_path = :filePath WHERE session_id = :sessionId")
    suspend fun updateSessionFile(sessionId: String, filePath: String): Int

    @Query("UPDATE transfer_sessions SET status = :status, completed_at = :completedAt, error_code = :errorCode WHERE session_id = :sessionId")
    suspend fun updateSessionStatus(
        sessionId: String,
        status: String,
        completedAt: Long? = null,
        errorCode: String? = null
    ): Int

    @Query("SELECT * FROM transfer_sessions WHERE status IN ('TRANSFERRING', 'HANDSHAKING', 'PAUSED', 'PENDING') ORDER BY started_at DESC")
    fun getActiveSessions(): Flow<List<TransferSessionEntity>>

    @Query("SELECT * FROM transfer_sessions ORDER BY started_at DESC")
    fun getAllSessions(): Flow<List<TransferSessionEntity>>

    @Query("DELETE FROM transfer_sessions WHERE session_id = :sessionId")
    suspend fun deleteSession(sessionId: String): Int
}
