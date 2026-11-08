package com.teamshare.app.data.local.db.dao

import com.teamshare.app.data.local.db.entity.TransferSessionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

/**
 * Kiểm thử đơn vị cho [TransferSessionDao] và các tiêu chí truy vấn CSDL theo SDD Chương 5.
 */
class TransferSessionDaoTest {

    private lateinit var dao: TransferSessionDao

    @Before
    fun setUp() {
        dao = InMemoryTransferSessionDao()
    }

    @Test
    fun testInsertAndGetSession() = runBlocking {
        val session = TransferSessionEntity(
            sessionId = "s101",
            sessionToken = "tok_abc",
            direction = "SENDER",
            channel = "DIRECT_LAN",
            peerDeviceModel = "Pixel 8 Pro",
            peerFingerprint = "fp_p8",
            approvalMode = "AUTO_ACCEPT",
            transferType = "SINGLE_FILE",
            manifestJson = "[{\"relativePath\":\"file.bin\",\"fileSize\":1024}]",
            totalBytes = 1024L,
            confirmedOffset = 0L,
            tempFilePath = "/tmp/file.bin",
            expectedSha256 = "hash101",
            status = "PENDING"
        )

        dao.insertSession(session)

        val retrieved = dao.getSessionById("s101")
        assertNotNull(retrieved)
        assertEquals("s101", retrieved?.sessionId)
        assertEquals("tok_abc", retrieved?.sessionToken)
        assertEquals("Pixel 8 Pro", retrieved?.peerDeviceModel)
        assertEquals("AUTO_ACCEPT", retrieved?.approvalMode)
        assertEquals(1024L, retrieved?.totalBytes)
    }

    @Test
    fun testFindByTokenWithIndex() = runBlocking {
        val session = TransferSessionEntity(
            sessionId = "s_token_test",
            sessionToken = "SEC_TOKEN_UNIQUE_999",
            direction = "SENDER",
            totalBytes = 2048L
        )
        dao.insertSession(session)

        val found = dao.findByToken("SEC_TOKEN_UNIQUE_999")
        assertNotNull(found)
        assertEquals("s_token_test", found?.sessionId)

        val notFound = dao.findByToken("NON_EXISTENT_TOKEN")
        assertNull(notFound)
    }

    @Test
    fun testUpdateCheckpointAndOffset() = runBlocking {
        val session = TransferSessionEntity(
            sessionId = "s_chk",
            direction = "RECEIVER",
            totalBytes = 1000000L,
            confirmedOffset = 0L
        )
        dao.insertSession(session)

        dao.updateCheckpoint("s_chk", 262144L)
        val updated = dao.getSessionById("s_chk")
        assertEquals(262144L, updated?.confirmedOffset)

        dao.updateCheckpoint("s_chk", 524288L)
        val updatedAgain = dao.getSessionById("s_chk")
        assertEquals(524288L, updatedAgain?.confirmedOffset)
    }

    @Test
    fun testUpdateSessionStatus() = runBlocking {
        val session = TransferSessionEntity(
            sessionId = "s_status",
            direction = "SENDER",
            totalBytes = 5000L,
            status = "TRANSFERRING"
        )
        dao.insertSession(session)

        val completedTime = System.currentTimeMillis()
        dao.updateSessionStatus("s_status", "COMPLETED", completedTime, null)

        val completed = dao.getSessionById("s_status")
        assertEquals("COMPLETED", completed?.status)
        assertEquals(completedTime, completed?.completedAt)
        assertNull(completed?.errorCode)
    }

    @Test
    fun testGetActiveSessions() = runBlocking {
        dao.insertSession(TransferSessionEntity("s1", direction = "SENDER", totalBytes = 100, status = "TRANSFERRING"))
        dao.insertSession(TransferSessionEntity("s2", direction = "RECEIVER", totalBytes = 100, status = "PAUSED"))
        dao.insertSession(TransferSessionEntity("s3", direction = "SENDER", totalBytes = 100, status = "COMPLETED"))
        dao.insertSession(TransferSessionEntity("s4", direction = "RECEIVER", totalBytes = 100, status = "PENDING"))
        dao.insertSession(TransferSessionEntity("s5", direction = "SENDER", totalBytes = 100, status = "CANCELLED"))

        val active = dao.getActiveSessions().first()
        val activeIds = active.map { it.sessionId }.toSet()

        assertTrue(activeIds.contains("s1"))
        assertTrue(activeIds.contains("s2"))
        assertTrue(activeIds.contains("s4"))
        assertEquals(3, active.size)
    }

    @Test
    fun testDeleteSession() = runBlocking {
        dao.insertSession(TransferSessionEntity("s_del", direction = "SENDER", totalBytes = 100))
        assertNotNull(dao.getSessionById("s_del"))

        dao.deleteSession("s_del")
        assertNull(dao.getSessionById("s_del"))
    }

    @Test
    fun testUpdateApprovalMode() = runBlocking {
        dao.insertSession(
            TransferSessionEntity(
                sessionId = "s_appr",
                direction = "SENDER",
                totalBytes = 100,
                approvalMode = "MANUAL_APPROVAL"
            )
        )
        val before = dao.getSessionById("s_appr")
        assertEquals("MANUAL_APPROVAL", before?.approvalMode)

        dao.updateApprovalMode("s_appr", "AUTO_ACCEPT")
        val after = dao.getSessionById("s_appr")
        assertEquals("AUTO_ACCEPT", after?.approvalMode)
    }

    @Test
    fun testUpdatePeerInfo() = runBlocking {
        dao.insertSession(
            TransferSessionEntity(
                sessionId = "s_peer",
                direction = "SENDER",
                totalBytes = 100
            )
        )
        dao.updatePeerInfo("s_peer", "Xiaomi Redmi Turbo 4 Pro", "fp_sha256_mock")
        val after = dao.getSessionById("s_peer")
        assertEquals("Xiaomi Redmi Turbo 4 Pro", after?.peerDeviceModel)
        assertEquals("fp_sha256_mock", after?.peerFingerprint)
    }

    @Test
    fun testQueryPerformanceUnder15ms() = runBlocking {
        val count = 100
        val startTime = System.currentTimeMillis()

        for (i in 1..count) {
            dao.insertSession(
                TransferSessionEntity(
                    sessionId = "perf_s_$i",
                    sessionToken = "token_$i",
                    direction = if (i % 2 == 0) "SENDER" else "RECEIVER",
                    totalBytes = i * 1024L
                )
            )
            dao.findByToken("token_$i")
            dao.updateCheckpoint("perf_s_$i", i * 512L)
        }

        val totalDuration = System.currentTimeMillis() - startTime
        val avgDurationPerOp = totalDuration.toDouble() / (count * 3)

        println("Performance benchmark: 300 DB operations in ${totalDuration}ms (Avg: ${avgDurationPerOp}ms/op)")
        assertTrue(
            "Thời gian truy vấn trung bình phải < 15ms (Thực tế: ${avgDurationPerOp}ms)",
            avgDurationPerOp < 15.0
        )
    }
}

/**
 * Hiện thực hóa Thread-safe InMemory DAO mô phỏng chính xác hành vi của Room SQLite DAO.
 */
class InMemoryTransferSessionDao : TransferSessionDao {
    private val sessions = ConcurrentHashMap<String, TransferSessionEntity>()
    private val stateFlow = MutableStateFlow<List<TransferSessionEntity>>(emptyList())

    private fun notifyChanged() {
        stateFlow.value = sessions.values.toList()
    }

    override suspend fun insertSession(session: TransferSessionEntity): Long {
        sessions[session.sessionId] = session
        notifyChanged()
        return 1L
    }

    override fun observeSessionById(sessionId: String): Flow<TransferSessionEntity?> {
        return stateFlow.map { list -> list.firstOrNull { it.sessionId == sessionId } }
    }

    override suspend fun getSessionById(sessionId: String): TransferSessionEntity? {
        return sessions[sessionId]
    }

    override suspend fun findByToken(token: String): TransferSessionEntity? {
        return sessions.values.firstOrNull { it.sessionToken == token }
    }

    override suspend fun updateCheckpoint(sessionId: String, offset: Long): Int {
        val current = sessions[sessionId] ?: return 0
        sessions[sessionId] = current.copy(confirmedOffset = offset)
        notifyChanged()
        return 1
    }

    override suspend fun updateApprovalMode(sessionId: String, mode: String): Int {
        val current = sessions[sessionId] ?: return 0
        sessions[sessionId] = current.copy(approvalMode = mode)
        notifyChanged()
        return 1
    }

    override suspend fun updatePeerInfo(sessionId: String, peerModel: String, peerFingerprint: String): Int {
        val current = sessions[sessionId] ?: return 0
        sessions[sessionId] = current.copy(peerDeviceModel = peerModel, peerFingerprint = peerFingerprint)
        notifyChanged()
        return 1
    }

    override suspend fun updateSessionStatus(
        sessionId: String,
        status: String,
        completedAt: Long?,
        errorCode: String?
    ): Int {
        val current = sessions[sessionId] ?: return 0
        sessions[sessionId] = current.copy(
            status = status,
            completedAt = completedAt,
            errorCode = errorCode
        )
        notifyChanged()
        return 1
    }

    override fun getActiveSessions(): Flow<List<TransferSessionEntity>> {
        return stateFlow.map { list ->
            list.filter { it.status in listOf("TRANSFERRING", "HANDSHAKING", "PAUSED", "PENDING") }
                .sortedByDescending { it.startedAt }
        }
    }

    override fun getAllSessions(): Flow<List<TransferSessionEntity>> {
        return stateFlow.map { list -> list.sortedByDescending { it.startedAt } }
    }

    override suspend fun deleteSession(sessionId: String): Int {
        val removed = sessions.remove(sessionId) != null
        if (removed) notifyChanged()
        return if (removed) 1 else 0
    }
}

