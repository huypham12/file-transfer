package com.teamshare.app.domain.repository

import com.teamshare.app.domain.model.ApprovalMode
import com.teamshare.app.domain.model.SessionStatus
import com.teamshare.app.domain.model.TransferSession
import kotlinx.coroutines.flow.Flow

/**
 * Hợp đồng dữ liệu quản lý các Phiên truyền tải Secure Drop và Checkpoint ghi đĩa (SDD Chương 4 & 5).
 */
interface ITransferRepository {
    suspend fun createSession(session: TransferSession)
    suspend fun getSessionById(sessionId: String): TransferSession?
    fun observeSessionById(sessionId: String): Flow<TransferSession?>
    suspend fun findByToken(token: String): TransferSession?
    fun getActiveSessions(): Flow<List<TransferSession>>
    fun getAllSessions(): Flow<List<TransferSession>>
    suspend fun updateCheckpoint(sessionId: String, confirmedOffset: Long)
    suspend fun updateApprovalMode(sessionId: String, mode: ApprovalMode)
    suspend fun updatePeerInfo(sessionId: String, peerModel: String, peerFingerprint: String)
    suspend fun updateSessionStatus(sessionId: String, status: SessionStatus, errorCode: String? = null)
    suspend fun deleteSession(sessionId: String)
}
