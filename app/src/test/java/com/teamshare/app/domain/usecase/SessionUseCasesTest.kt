package com.teamshare.app.domain.usecase

import com.teamshare.app.core.platform.device.DeviceIdentityManager
import com.teamshare.app.data.local.db.dao.InMemoryAuditLogDao
import com.teamshare.app.data.local.db.dao.InMemoryTransferSessionDao
import com.teamshare.app.data.repository.AuditRepositoryImpl
import com.teamshare.app.data.repository.TransferRepositoryImpl
import com.teamshare.app.domain.model.ApprovalMode
import com.teamshare.app.domain.model.AuditEventType
import com.teamshare.app.domain.model.DeviceProfile
import com.teamshare.app.domain.model.SessionQrPayload
import com.teamshare.app.domain.model.SessionStatus
import com.teamshare.app.domain.model.TransferChannel
import com.teamshare.app.domain.model.TransferDirection
import com.teamshare.app.domain.model.TransferSession
import com.teamshare.app.domain.model.TransferType
import com.teamshare.app.domain.repository.IAuditRepository
import com.teamshare.app.domain.repository.ITransferRepository
import com.teamshare.app.domain.usecase.audit.RecordSecurityAuditUseCase
import com.teamshare.app.domain.usecase.device.GetDeviceIdentityUseCase
import com.teamshare.app.domain.usecase.transfer.CancelTransferSessionUseCase
import com.teamshare.app.domain.usecase.transfer.CreateSenderSessionUseCase
import com.teamshare.app.domain.usecase.transfer.ManageApprovalModeUseCase
import com.teamshare.app.domain.usecase.transfer.ResumeTransferUseCase
import com.teamshare.app.domain.usecase.transfer.ScanQrTokenUseCase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream

class SessionUseCasesTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var sessionDao: InMemoryTransferSessionDao
    private lateinit var auditDao: InMemoryAuditLogDao
    private lateinit var transferRepository: ITransferRepository
    private lateinit var auditRepository: IAuditRepository
    private lateinit var deviceIdentityManager: DeviceIdentityManager

    @Before
    fun setUp() {
        sessionDao = InMemoryTransferSessionDao()
        auditDao = InMemoryAuditLogDao()
        transferRepository = TransferRepositoryImpl(sessionDao)
        auditRepository = AuditRepositoryImpl(auditDao)
        deviceIdentityManager = DeviceIdentityManager()
        deviceIdentityManager.setOverrideProfile(
            DeviceProfile(
                manufacturer = "Xiaomi",
                model = "Redmi Turbo 4 Pro",
                fingerprint = "mock_fp_turbo4"
            )
        )
    }

    private fun createSampleFile(name: String, sizeBytes: Int): File {
        val file = tempFolder.newFile(name)
        FileOutputStream(file).use { fos ->
            val buf = ByteArray(1024) { 0x41 }
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
    fun testGetDeviceIdentityUseCase() {
        val useCase = GetDeviceIdentityUseCase(deviceIdentityManager)
        val profile = useCase()

        assertEquals("Xiaomi", profile.manufacturer)
        assertEquals("Redmi Turbo 4 Pro", profile.model)
        assertEquals("mock_fp_turbo4", profile.fingerprint)
    }

    @Test
    fun testCreateSenderSessionUseCase() = runBlocking {
        val file = createSampleFile("test_doc.pdf", 4096)
        val useCase = CreateSenderSessionUseCase(
            transferRepository = transferRepository,
            deviceIdentityManager = deviceIdentityManager,
            auditRepository = auditRepository
        )

        val result = useCase.execute(
            files = listOf(file),
            senderIp = "192.168.1.100",
            senderPort = 8888,
            approvalMode = ApprovalMode.AUTO_ACCEPT,
            channel = TransferChannel.DIRECT_LAN
        )

        assertTrue(result.isSuccess)
        val (session, qrPayload) = result.getOrThrow()

        // Kiểm tra TransferSession
        assertEquals(SessionStatus.PENDING, session.status)
        assertEquals(TransferDirection.SENDER, session.direction)
        assertEquals(4096L, session.totalBytes)
        assertEquals(ApprovalMode.AUTO_ACCEPT, session.approvalMode)
        assertTrue(session.manifestJson.contains("test_doc.pdf"))

        // Kiểm tra QR Payload
        assertEquals(session.sessionId, qrPayload.sessionId)
        assertEquals(session.sessionToken, qrPayload.sessionToken)
        assertEquals("192.168.1.100", qrPayload.senderIp)
        assertEquals(8888, qrPayload.senderPort)
        assertEquals("Redmi Turbo 4 Pro", qrPayload.senderDeviceModel)
        assertFalse(qrPayload.isExpired)

        // Kiểm tra đã lưu DB
        val fromDb = transferRepository.getSessionById(session.sessionId)
        assertNotNull(fromDb)
        assertEquals(session.sessionId, fromDb?.sessionId)

        // Kiểm tra audit log
        val logs = auditRepository.getRecentLogs().first()
        assertTrue(logs.any { it.eventType == AuditEventType.SESSION_CREATED && it.sessionId == session.sessionId })
    }

    @Test
    fun testScanQrTokenUseCaseValidAndExpired() = runBlocking {
        val scanUseCase = ScanQrTokenUseCase()

        // 1. Quét payload hợp lệ dạng JSON
        val validPayload = SessionQrPayload(
            sessionId = "sess_001",
            sessionToken = "token_abc123",
            senderIp = "192.168.1.50",
            senderPort = 8888,
            channel = TransferChannel.DIRECT_LAN,
            transferType = TransferType.SINGLE_FILE,
            totalBytes = 2048L,
            itemCount = 1,
            senderDeviceModel = "Xiaomi Redmi Turbo 4 Pro",
            expiresAt = System.currentTimeMillis() + 300_000L
        )

        val jsonResult = scanUseCase(validPayload.toJson())
        assertTrue(jsonResult.isSuccess)
        val parsedJson = jsonResult.getOrThrow()
        assertEquals("sess_001", parsedJson.sessionId)
        assertEquals("token_abc123", parsedJson.sessionToken)
        assertEquals("192.168.1.50", parsedJson.senderIp)

        // 2. Quét payload hợp lệ dạng URI teamshare://drop?payload=...
        val uriResult = scanUseCase(validPayload.toQrString())
        assertTrue(uriResult.isSuccess)
        val parsedUri = uriResult.getOrThrow()
        assertEquals("sess_001", parsedUri.sessionId)
        assertEquals("token_abc123", parsedUri.sessionToken)

        // 3. Quét mã QR đã hết hạn (TTL 5 phút) -> mã lỗi 0x103 (ERR_QR_EXPIRED)
        val expiredPayload = validPayload.copy(
            expiresAt = System.currentTimeMillis() - 1000L // Đã hết hạn
        )
        val expiredResult = scanUseCase(expiredPayload.toQrString())
        assertTrue(expiredResult.isFailure)
        val exception = expiredResult.exceptionOrNull()
        assertNotNull(exception)
        assertTrue(exception?.message?.contains("0x103") == true || exception?.message?.contains("ERR_QR_EXPIRED") == true)
    }

    @Test
    fun testManageApprovalModeUseCase() = runBlocking {
        val session = TransferSession(
            sessionId = "sess_appr_toggle",
            sessionToken = "tok_toggle",
            direction = TransferDirection.SENDER,
            totalBytes = 1024L,
            approvalMode = ApprovalMode.MANUAL_APPROVAL,
            status = SessionStatus.PENDING
        )
        transferRepository.createSession(session)

        val manageUseCase = ManageApprovalModeUseCase(transferRepository)
        manageUseCase("sess_appr_toggle", ApprovalMode.AUTO_ACCEPT)

        val updated = transferRepository.getSessionById("sess_appr_toggle")
        assertEquals(ApprovalMode.AUTO_ACCEPT, updated?.approvalMode)
    }

    @Test
    fun testCancelTransferSessionUseCaseWithWipe() = runBlocking {
        val tempFile = tempFolder.newFile("transfer_temp.part")
        FileOutputStream(tempFile).use { it.write(ByteArray(512) { 0xFF.toByte() }) }
        assertTrue(tempFile.exists())

        val session = TransferSession(
            sessionId = "sess_to_cancel",
            sessionToken = "tok_cancel",
            direction = TransferDirection.RECEIVER,
            totalBytes = 1024L,
            tempFilePath = tempFile.absolutePath,
            status = SessionStatus.TRANSFERRING
        )
        transferRepository.createSession(session)

        val cancelUseCase = CancelTransferSessionUseCase(
            transferRepository = transferRepository,
            transferCoordinator = null,
            auditRepository = auditRepository
        )

        val result = cancelUseCase.execute("sess_to_cancel", tempFile)
        assertTrue(result.isSuccess)

        // Cập nhật trạng thái CANCELLED
        val cancelledSession = transferRepository.getSessionById("sess_to_cancel")
        assertEquals(SessionStatus.CANCELLED, cancelledSession?.status)

        // Tệp tạm đã bị tiêu hủy sạch (không còn tồn tại)
        assertFalse(tempFile.exists())

        // Đã ghi log TRANSFER_CANCELLED
        val logs = auditRepository.getRecentLogs().first()
        assertTrue(logs.any { it.eventType == AuditEventType.TRANSFER_CANCELLED && it.sessionId == "sess_to_cancel" })
    }

    @Test
    fun testResumeTransferUseCase() = runBlocking {
        val session = TransferSession(
            sessionId = "sess_to_resume",
            sessionToken = "tok_resume",
            direction = TransferDirection.RECEIVER,
            totalBytes = 2048L,
            confirmedOffset = 1024L,
            status = SessionStatus.PAUSED
        )
        transferRepository.createSession(session)

        val resumeUseCase = ResumeTransferUseCase(
            transferRepository = transferRepository,
            transferCoordinator = null,
            auditRepository = auditRepository
        )

        // 1. Khôi phục phiên PAUSED thành công
        val result = resumeUseCase("sess_to_resume")
        assertTrue(result.isSuccess)
        val resumed = result.getOrThrow()
        assertEquals(SessionStatus.TRANSFERRING, resumed.status)

        val fromDb = transferRepository.getSessionById("sess_to_resume")
        assertEquals(SessionStatus.TRANSFERRING, fromDb?.status)

        // Đã ghi log TRANSFER_RESUMED
        val logs = auditRepository.getRecentLogs().first()
        assertTrue(logs.any { it.eventType == AuditEventType.TRANSFER_RESUMED && it.sessionId == "sess_to_resume" })

        // 2. Khôi phục phiên COMPLETED thất bại
        transferRepository.updateSessionStatus("sess_to_resume", SessionStatus.COMPLETED)
        val invalidResume = resumeUseCase("sess_to_resume")
        assertTrue(invalidResume.isFailure)
        assertTrue(invalidResume.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun testRecordSecurityAuditUseCase() = runBlocking {
        val recordUseCase = RecordSecurityAuditUseCase(auditRepository)
        val res = recordUseCase(
            eventType = AuditEventType.DEVICE_CONNECTED,
            details = "Thiết bị Xiaomi Redmi Turbo 4 Pro đã kết nối",
            sessionId = "sess_audit_test",
            actorDevice = "Xiaomi Redmi Turbo 4 Pro"
        )
        assertTrue(res.isSuccess)

        val logs = auditRepository.getRecentLogs().first()
        val match = logs.firstOrNull { it.sessionId == "sess_audit_test" }
        assertNotNull(match)
        assertEquals(AuditEventType.DEVICE_CONNECTED, match?.eventType)
        assertEquals("Xiaomi Redmi Turbo 4 Pro", match?.actorDevice)
    }

    @Test
    fun testCreateSenderSessionWithMultipleFiles() = runBlocking {
        val file1 = createSampleFile("photo1.jpg", 1024)
        val file2 = createSampleFile("photo2.png", 2048)

        val useCase = CreateSenderSessionUseCase(
            transferRepository = transferRepository,
            deviceIdentityManager = deviceIdentityManager,
            auditRepository = auditRepository
        )

        val result = useCase.execute(
            files = listOf(file1, file2),
            senderIp = "192.168.1.100",
            senderPort = 8888,
            approvalMode = ApprovalMode.AUTO_ACCEPT,
            channel = TransferChannel.DIRECT_LAN
        )

        assertTrue("Khởi tạo phiên chia sẻ nhiều tệp phải thành công", result.isSuccess)
        val (session, qrPayload) = result.getOrThrow()
        assertEquals(TransferType.BATCH_FILES, session.transferType)
        assertEquals(3072L, session.totalBytes)
        assertEquals(2, qrPayload.itemCount)
    }
}
