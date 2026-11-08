package com.teamshare.app.data.repository

import com.teamshare.app.data.local.db.dao.TransferSessionDao
import com.teamshare.app.data.local.db.mapper.toDomain
import com.teamshare.app.data.local.db.mapper.toEntity
import com.teamshare.app.domain.model.ApprovalMode
import com.teamshare.app.domain.model.SessionStatus
import com.teamshare.app.domain.model.TransferSession
import com.teamshare.app.domain.repository.ITransferRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Hiện thực hóa ITransferRepository quản lý các Phiên truyền tải Secure Drop và Checkpoint ghi đĩa.
 */
class TransferRepositoryImpl(
    private val sessionDao: TransferSessionDao
) : ITransferRepository {

    override suspend fun createSession(session: TransferSession): Unit = withContext(Dispatchers.IO) {
        sessionDao.insertSession(session.toEntity())
    }

    override suspend fun getSessionById(sessionId: String): TransferSession? = withContext(Dispatchers.IO) {
        sessionDao.getSessionById(sessionId)?.toDomain()
    }

    override fun observeSessionById(sessionId: String): Flow<TransferSession?> {
        return sessionDao.observeSessionById(sessionId).map { it?.toDomain() }
    }

    override suspend fun findByToken(token: String): TransferSession? = withContext(Dispatchers.IO) {
        sessionDao.findByToken(token)?.toDomain()
    }

    override fun getActiveSessions(): Flow<List<TransferSession>> {
        return sessionDao.getActiveSessions().map { list -> list.map { it.toDomain() } }
    }

    override fun getAllSessions(): Flow<List<TransferSession>> {
        return sessionDao.getAllSessions().map { list -> list.map { it.toDomain() } }
    }

    override suspend fun updateCheckpoint(sessionId: String, confirmedOffset: Long): Unit = withContext(Dispatchers.IO) {
        sessionDao.updateCheckpoint(sessionId, confirmedOffset)
    }

    override suspend fun updateApprovalMode(sessionId: String, mode: ApprovalMode): Unit = withContext(Dispatchers.IO) {
        sessionDao.updateApprovalMode(sessionId, mode.name)
    }

    override suspend fun updatePeerInfo(
        sessionId: String,
        peerModel: String,
        peerFingerprint: String
    ): Unit = withContext(Dispatchers.IO) {
        sessionDao.updatePeerInfo(sessionId, peerModel, peerFingerprint)
    }

    override suspend fun updateSessionStatus(
        sessionId: String,
        status: SessionStatus,
        errorCode: String?
    ): Unit = withContext(Dispatchers.IO) {
        val completedAt = if (status == SessionStatus.COMPLETED || status == SessionStatus.FAILED || status == SessionStatus.CANCELLED) {
            System.currentTimeMillis()
        } else {
            null
        }
        sessionDao.updateSessionStatus(sessionId, status.name, completedAt, errorCode)
    }

    override suspend fun deleteSession(sessionId: String): Unit = withContext(Dispatchers.IO) {
        sessionDao.deleteSession(sessionId)
    }
}
