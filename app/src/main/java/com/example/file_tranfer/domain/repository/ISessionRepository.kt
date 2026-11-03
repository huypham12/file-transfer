package com.example.file_tranfer.domain.repository

import com.example.file_tranfer.domain.model.FileTransferStatus
import com.example.file_tranfer.domain.model.SessionStatus
import com.example.file_tranfer.domain.model.TransferSession
import kotlinx.coroutines.flow.Flow

/**
 * Hợp đồng dữ liệu quản lý các phiên truyền tải tệp P2P.
 */
interface ISessionRepository {
    suspend fun createSession(session: TransferSession)
    suspend fun getSessionById(sessionId: String): TransferSession?
    fun getAllSessionsFlow(): Flow<List<TransferSession>>
    suspend fun updateCheckpointOffset(sessionId: String, fileId: String, offset: Long)
    suspend fun updateSessionStatus(sessionId: String, status: SessionStatus)
    suspend fun updateFileProgress(sessionId: String, fileId: String, bytesTransferred: Long, status: FileTransferStatus)
    suspend fun deleteSession(sessionId: String)
}

