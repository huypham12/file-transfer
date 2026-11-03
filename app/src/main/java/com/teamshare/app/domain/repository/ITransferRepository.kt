package com.teamshare.app.domain.repository

import com.teamshare.app.domain.model.SessionStatus
import com.teamshare.app.domain.model.TransferSession
import kotlinx.coroutines.flow.Flow

/**
 * Hợp đồng dữ liệu quản lý các Phiên truyền tải P2P và Checkpoint ghi đĩa.
 */
interface ITransferRepository {
    suspend fun createSession(session: TransferSession)
    suspend fun getSessionById(sessionId: String): TransferSession?
    fun getActiveSessions(): Flow<List<TransferSession>>
    suspend fun updateCheckpoint(sessionId: String, confirmedOffset: Long)
    suspend fun updateSessionStatus(sessionId: String, status: SessionStatus, errorCode: String? = null)
    suspend fun deleteSession(sessionId: String)
}

