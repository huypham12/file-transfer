package com.teamshare.app.data.repository

import com.teamshare.app.data.local.db.dao.InMemoryAuditLogDao
import com.teamshare.app.data.local.db.dao.InMemoryTransferSessionDao
import com.teamshare.app.domain.model.ApprovalMode
import com.teamshare.app.domain.model.AuditLog
import com.teamshare.app.domain.model.AuditSeverity
import com.teamshare.app.domain.model.AuditEventType
import com.teamshare.app.domain.model.SecurityAuditLog
import com.teamshare.app.domain.model.SessionStatus
import com.teamshare.app.domain.model.TransferChannel
import com.teamshare.app.domain.model.TransferDirection
import com.teamshare.app.domain.model.TransferSession
import com.teamshare.app.domain.model.TransferType
import com.teamshare.app.domain.repository.IAuditRepository
import com.teamshare.app.domain.repository.ITransferRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Kiểm thử tích hợp giữa Repositories và DAOs theo SDD Chương 4 & 5.
 */
class TransferAndAuditRepositoryTest {

    private lateinit var sessionDao: InMemoryTransferSessionDao
    private lateinit var auditDao: InMemoryAuditLogDao
    private lateinit var transferRepository: ITransferRepository
    private lateinit var auditRepository: IAuditRepository

    @Before
    fun setUp() {
        sessionDao = InMemoryTransferSessionDao()
        auditDao = InMemoryAuditLogDao()
        transferRepository = TransferRepositoryImpl(sessionDao)
        auditRepository = AuditRepositoryImpl(auditDao)
    }

    @Test
    fun testTransferRepositoryLifecycle() = runBlocking {
        val session = TransferSession(
            sessionId = "sess_repo_001",
            sessionToken = "token_qr_xyz",
            direction = TransferDirection.SENDER,
            channel = TransferChannel.DIRECT_LAN,
            peerDeviceModel = "Xiaomi 14",
            peerFingerprint = "fp_x14",
            approvalMode = ApprovalMode.MANUAL_APPROVAL,
            transferType = TransferType.SINGLE_FILE,
            totalBytes = 1048576L,
            confirmedOffset = 0L,
            status = SessionStatus.PENDING
        )

        // 1. Tạo phiên
        transferRepository.createSession(session)

        // 2. Tra cứu theo ID
        val retrieved = transferRepository.getSessionById("sess_repo_001")
        assertNotNull(retrieved)
        assertEquals("token_qr_xyz", retrieved?.sessionToken)
        assertEquals("Xiaomi 14", retrieved?.peerDeviceModel)

        // 3. Tra cứu bằng Token O(1)
        val byToken = transferRepository.findByToken("token_qr_xyz")
        assertNotNull(byToken)
        assertEquals("sess_repo_001", byToken?.sessionId)

        // 4. Quan sát luồng Flow
        val flowSession = transferRepository.observeSessionById("sess_repo_001").first()
        assertNotNull(flowSession)
        assertEquals(SessionStatus.PENDING, flowSession?.status)

        // 5. Cập nhật Checkpoint
        transferRepository.updateCheckpoint("sess_repo_001", 524288L)
        val afterCheckpoint = transferRepository.getSessionById("sess_repo_001")
        assertEquals(524288L, afterCheckpoint?.confirmedOffset)

        // 6. Cập nhật trạng thái
        transferRepository.updateSessionStatus("sess_repo_001", SessionStatus.COMPLETED)
        val completed = transferRepository.getSessionById("sess_repo_001")
        assertEquals(SessionStatus.COMPLETED, completed?.status)
        assertNotNull(completed?.completedAt)

        // 7. Xóa phiên
        transferRepository.deleteSession("sess_repo_001")
        assertNull(transferRepository.getSessionById("sess_repo_001"))
    }

    @Test
    fun testAuditRepositoryLoggingAndFlow() = runBlocking {
        val log1 = SecurityAuditLog(
            sessionId = "sess_aud_1",
            timestamp = 1000L,
            eventType = AuditEventType.SESSION_CREATED,
            actorDevice = "LOCAL_DEVICE",
            channel = "DIRECT_LAN",
            severity = AuditSeverity.INFO,
            details = "Khởi tạo phiên Drop"
        )
        val log2 = SecurityAuditLog(
            sessionId = "sess_aud_1",
            timestamp = 2000L,
            eventType = AuditEventType.CHECKSUM_VERIFIED,
            actorDevice = "Xiaomi 14",
            channel = "DIRECT_LAN",
            severity = AuditSeverity.INFO,
            details = "Mã băm SHA-256 khớp 100%"
        )

        auditRepository.logEvent(log1)
        auditRepository.recordLog(log2)

        val recentLogs = auditRepository.getRecentLogs(10).first()
        assertEquals(2, recentLogs.size)
        // Mới nhất lên đầu
        assertEquals(AuditEventType.CHECKSUM_VERIFIED, recentLogs[0].eventType)
        assertEquals(AuditEventType.SESSION_CREATED, recentLogs[1].eventType)

        val sessionLogs = auditRepository.getLogsBySession("sess_aud_1").first()
        assertEquals(2, sessionLogs.size)
    }

    @Test
    fun testGetActiveAndAllSessions() = runBlocking {
        transferRepository.createSession(
            TransferSession(sessionId = "s1", direction = TransferDirection.SENDER, totalBytes = 100, status = SessionStatus.TRANSFERRING)
        )
        transferRepository.createSession(
            TransferSession(sessionId = "s2", direction = TransferDirection.RECEIVER, totalBytes = 200, status = SessionStatus.PAUSED)
        )
        transferRepository.createSession(
            TransferSession(sessionId = "s3", direction = TransferDirection.SENDER, totalBytes = 300, status = SessionStatus.COMPLETED)
        )

        val active = transferRepository.getActiveSessions().first()
        assertEquals(2, active.size)
        val activeIds = active.map { it.sessionId }
        assertTrue(activeIds.contains("s1"))
        assertTrue(activeIds.contains("s2"))

        val all = transferRepository.getAllSessions().first()
        assertEquals(3, all.size)
    }
}

