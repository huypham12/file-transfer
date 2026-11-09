package com.teamshare.app.e2e

import com.teamshare.app.core.security.crypto.Sha256StreamCalculator
import com.teamshare.app.core.transfer.buffer.BufferPoolManager
import com.teamshare.app.core.transfer.checkpoint.CheckpointTracker
import com.teamshare.app.domain.model.ApprovalMode
import com.teamshare.app.domain.model.AuditEventType
import com.teamshare.app.domain.model.SecurityAuditLog
import com.teamshare.app.domain.model.SessionStatus
import com.teamshare.app.domain.model.TransferDirection
import com.teamshare.app.domain.model.TransferSession
import com.teamshare.app.domain.repository.IAuditRepository
import com.teamshare.app.domain.repository.ITransferRepository
import com.teamshare.app.domain.usecase.transfer.ResumeTransferUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Stage 8 E2E Test: Kiểm thử Tích hợp Toàn diện Cơ chế Tái tục Phiên truyền từ Checkpoint (Resume Transfer).
 * Kiểm chứng:
 * 1. Đứt kết nối mạng đột ngột giữa chừng (Simulated Network Interruption).
 * 2. Lưu vết Checkpoint an toàn tại confirmedOffset.
 * 3. Tái thiết lập kết nối và gửi yêu cầu RESUME từ đúng vị trí offset đã xác nhận.
 * 4. Phía gửi Seek con trỏ đọc và truyền tiếp các byte còn lại.
 * 5. Phía nhận Append vào tệp tạm .part và kiểm tra toàn vẹn mã băm SHA-256 đạt 100%.
 * 6. Kiểm thử UseCase ResumeTransferUseCase cập nhật trạng thái TRANSFERRING và ghi vết kiểm toán an ninh.
 */
class ResumeTransferE2ETest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun createDummyFile(sizeBytes: Int, name: String = "large_dataset.bin"): File {
        val file = tempFolder.newFile(name)
        FileOutputStream(file).use { fos ->
            val buf = ByteArray(1024) { (it * 13 % 256).toByte() }
            var written = 0
            while (written < sizeBytes) {
                val toWrite = minOf(buf.size, sizeBytes - written)
                fos.write(buf, 0, toWrite)
                written += toWrite
            }
        }
        return file
    }

    @Test
    fun testEndToEndResumeTransfer_AfterNetworkInterruption() = runBlocking {
        val totalSize = 256 * 1024 // 256 KB
        val sourceFile = createDummyFile(sizeBytes = totalSize, name = "big_archive.bin")
        val expectedSha256 = Sha256StreamCalculator.calculateFileSha256(sourceFile)

        val targetDir = tempFolder.newFolder("target_resume_dir")
        val partFile = File(targetDir, "big_archive.bin.part")

        // 1. Giả lập giai đoạn 1: Đã truyền được 100 KB thì mất mạng
        val interruptedOffset = 100 * 1024L // 100 KB
        FileOutputStream(partFile).use { fos ->
            FileInputStream(sourceFile).use { fis ->
                val buf = ByteArray(1024)
                var written = 0L
                while (written < interruptedOffset) {
                    val toRead = minOf(buf.size.toLong(), interruptedOffset - written).toInt()
                    val read = fis.read(buf, 0, toRead)
                    if (read == -1) break
                    fos.write(buf, 0, read)
                    written += read
                }
            }
        }
        assertEquals(interruptedOffset, partFile.length())

        // 2. Lưu Checkpoint vị trí byte an toàn
        val sessionId = "resume_session_e2e_001"
        val checkpointTracker = CheckpointTracker(sessionId = sessionId)
        checkpointTracker.initOffset(initialOffset = interruptedOffset)

        assertEquals("Vị trí Checkpoint đã khởi tạo phải khớp với vị trí đứt mạng", interruptedOffset, checkpointTracker.getLastConfirmedOffset())
        assertEquals(interruptedOffset, checkpointTracker.getCurrentOffset())

        // 3. Giả lập giai đoạn 2: Tái tục kết nối (Resume) từ offset 100KB
        // Phía gửi seek tới interruptedOffset và truyền nốt 156 KB còn lại
        val pool = BufferPoolManager(bufferSize = 64 * 1024, maxBuffers = 4)
        val resumeJob = async(Dispatchers.IO) {
            RandomAccessFile(sourceFile, "r").use { srcRaf ->
                srcRaf.seek(interruptedOffset) // Seek đến 100KB

                RandomAccessFile(partFile, "rw").use { dstRaf ->
                    dstRaf.seek(interruptedOffset) // Append tiếp từ 100KB

                    val buffer = pool.acquire()
                    try {
                        var bytesRead: Int
                        while (srcRaf.read(buffer).also { bytesRead = it } != -1) {
                            dstRaf.write(buffer, 0, bytesRead)
                        }
                    } finally {
                        pool.release(buffer)
                    }
                }
            }
        }

        resumeJob.await()

        // 4. Đổi tên tệp tạm .part thành tệp hoàn chỉnh
        val completedFile = File(targetDir, "big_archive.bin")
        assertTrue(partFile.renameTo(completedFile))

        // 5. Thẩm định kích thước và tính toàn vẹn 100% SHA-256 sau khi nối tệp
        assertEquals(totalSize.toLong(), completedFile.length())
        val actualSha256 = Sha256StreamCalculator.calculateFileSha256(completedFile)
        assertEquals("Mã băm SHA-256 sau khi Resume phải trùng khớp 100% với tệp gốc", expectedSha256, actualSha256)
    }

    @Test
    fun testResumeTransferUseCase_TransitionsStateAndLogs() = runBlocking {
        val sessionId = "session_resume_usecase_002"
        val totalBytes = 500 * 1024L
        val confirmedOffset = 200 * 1024L

        var currentStatus = SessionStatus.PAUSED
        val capturedLogs = CopyOnWriteArrayList<SecurityAuditLog>()

        val mockSession = TransferSession(
            sessionId = sessionId,
            sessionToken = "token_resume_999",
            direction = TransferDirection.RECEIVER,
            status = SessionStatus.PAUSED,
            totalBytes = totalBytes,
            confirmedOffset = confirmedOffset,
            expectedSha256 = "dummy_sha256_cad"
        )

        val mockTransferRepo = object : ITransferRepository {
            override suspend fun createSession(session: TransferSession) {}

            override suspend fun getSessionById(sessionId: String): TransferSession? {
                return if (sessionId == mockSession.sessionId) mockSession.copy(status = currentStatus) else null
            }

            override fun observeSessionById(sessionId: String): Flow<TransferSession?> = flowOf(mockSession)
            override suspend fun findByToken(token: String): TransferSession? = null
            override fun getActiveSessions(): Flow<List<TransferSession>> = flowOf(listOf(mockSession))
            override fun getAllSessions(): Flow<List<TransferSession>> = flowOf(listOf(mockSession))
            override suspend fun updateCheckpoint(sessionId: String, confirmedOffset: Long) {}
            override suspend fun updateApprovalMode(sessionId: String, mode: ApprovalMode) {}
            override suspend fun updatePeerInfo(sessionId: String, peerModel: String, peerFingerprint: String) {}
            override suspend fun updateSessionFile(sessionId: String, filePath: String) {}

            override suspend fun updateSessionStatus(sessionId: String, status: SessionStatus, errorCode: String?) {
                currentStatus = status
            }

            override suspend fun deleteSession(sessionId: String) {}
        }

        val mockAuditRepo = object : IAuditRepository {
            override suspend fun logEvent(log: SecurityAuditLog) {
                capturedLogs.add(log)
            }

            override fun getLogsBySession(sessionId: String): Flow<List<SecurityAuditLog>> =
                flowOf(capturedLogs.filter { it.sessionId == sessionId })

            override fun getRecentLogs(limit: Int): Flow<List<SecurityAuditLog>> =
                flowOf(capturedLogs.takeLast(limit))
        }

        val resumeUseCase = ResumeTransferUseCase(
            transferRepository = mockTransferRepo,
            auditRepository = mockAuditRepo
        )

        // Thực thi tái tục phiên
        val result = resumeUseCase.execute(sessionId)
        assertTrue("Resume phiên đang PAUSED phải thành công", result.isSuccess)
        assertEquals(SessionStatus.TRANSFERRING, currentStatus)

        // Kiểm tra audit log
        assertEquals(1, capturedLogs.size)
        assertEquals(AuditEventType.TRANSFER_RESUMED, capturedLogs[0].eventType)
        assertEquals(sessionId, capturedLogs[0].sessionId)
        assertTrue(capturedLogs[0].details.contains("204800"))

        // Thử resume phiên đã COMPLETED -> phải bị từ chối an toàn
        currentStatus = SessionStatus.COMPLETED
        val invalidResult = resumeUseCase.execute(sessionId)
        assertFalse("Không được phép resume phiên đã hoàn tất COMPLETED", invalidResult.isSuccess)
        assertTrue(invalidResult.exceptionOrNull() is IllegalStateException)
    }
}
