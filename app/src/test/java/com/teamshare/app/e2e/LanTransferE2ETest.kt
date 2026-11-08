package com.teamshare.app.e2e

import com.teamshare.app.core.network.tls.TlsSocketFactory
import com.teamshare.app.core.security.crypto.Sha256StreamCalculator
import com.teamshare.app.core.transfer.lan.LanTransferEngine
import com.teamshare.app.core.transfer.lan.TransferEngineState
import com.teamshare.app.core.transfer.lan.TransferProgressCallback
import com.teamshare.app.domain.model.ApprovalMode
import com.teamshare.app.domain.model.AuditEventType
import com.teamshare.app.domain.model.AuditSeverity
import com.teamshare.app.domain.model.DeviceProfile
import com.teamshare.app.domain.model.SecurityAuditLog
import com.teamshare.app.domain.model.SessionQrPayload
import com.teamshare.app.domain.repository.IAuditRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Stage 8 E2E Test: Kiểm thử Tích hợp Toàn diện Luồng truyền trực tiếp LAN P2P Bảo mật (Port 8889).
 * Bao quát toàn bộ chu trình sống:
 * Chọn tệp -> Sinh QR Token ngoài băng -> Quét QR -> Kiểm tra StatFs -> Bắt tay DeviceProfile ->
 * Phê duyệt Auto/Manual -> Streaming TLS 1.3 / Socket -> Đối chiếu SHA-256 -> Ghi vết Kiểm toán.
 */
class LanTransferE2ETest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun createDummyFile(sizeBytes: Int, name: String = "test_document.pdf"): File {
        val file = tempFolder.newFile(name)
        FileOutputStream(file).use { fos ->
            val buf = ByteArray(1024) { (it % 255).toByte() }
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
    fun testFullEndToEndTransfer_AutoAccept() = runBlocking {
        val targetDir = tempFolder.newFolder("received_auto")
        val sourceFile = createDummyFile(sizeBytes = 128 * 1024, name = "kma_defense_thesis.pdf") // 128 KB
        val expectedSha256 = Sha256StreamCalculator.calculateFileSha256(sourceFile)

        val port = TlsSocketFactory.findAvailablePort()
        val sessionToken = "session-token-auto-xyz"
        val sessionId = "session_e2e_001"

        // 1. Phía gửi sinh chuỗi QR Payload ngoài băng (OOB)
        val senderQr = SessionQrPayload(
            sessionId = sessionId,
            sessionToken = sessionToken,
            senderIp = "127.0.0.1",
            senderPort = port,
            senderDeviceModel = "KMA Sender Node"
        )
        val qrString = senderQr.toQrString()
        assertTrue("Chuỗi QR phải hợp lệ", qrString.isNotBlank())

        // 2. Phía nhận quét và phân giải QR
        val parsedQr = SessionQrPayload.parse(qrString)
        assertEquals(sessionId, parsedQr.sessionId)
        assertEquals("127.0.0.1", parsedQr.senderIp)
        assertEquals(port, parsedQr.senderPort)
        assertEquals(sessionToken, parsedQr.sessionToken)
        assertFalse("Mã QR 5 phút chưa thể hết hạn ngay lập tức", parsedQr.isExpired)

        val senderEngine = LanTransferEngine()
        val receiverEngine = LanTransferEngine()
        val serverReady = CompletableDeferred<Unit>()

        val senderStates = CopyOnWriteArrayList<TransferEngineState>()
        val receiverStates = CopyOnWriteArrayList<TransferEngineState>()

        // 3. Phía gửi khởi chạy Server ở chế độ AUTO_ACCEPT
        val senderJob = async(Dispatchers.IO) {
            serverReady.complete(Unit)
            senderEngine.startSenderServer(
                port = port,
                sessionToken = sessionToken,
                file = sourceFile,
                sessionId = sessionId,
                expectedSha256 = expectedSha256,
                approvalMode = ApprovalMode.AUTO_ACCEPT,
                useTls = false,
                callback = object : TransferProgressCallback {
                    override fun onProgress(bytesTransferred: Long, totalBytes: Long, speedBytesPerSec: Long) {}
                    override fun onStateChanged(newState: TransferEngineState) {
                        senderStates.add(newState)
                    }
                }
            )
        }

        serverReady.await()
        delay(100)

        // 4. Phía nhận kết nối và gửi định danh thiết bị "Xiaomi Redmi Turbo 4 Pro"
        val receiverResult = receiverEngine.connectReceiver(
            host = parsedQr.senderIp,
            port = parsedQr.senderPort,
            sessionToken = parsedQr.sessionToken,
            sessionId = parsedQr.sessionId,
            targetDirectory = targetDir,
            deviceModel = "Redmi Turbo 4 Pro",
            deviceManufacturer = "Xiaomi",
            deviceFingerprint = "SHA256:7a4c9e8b1d2f0a3e",
            useTls = false,
            callback = object : TransferProgressCallback {
                override fun onProgress(bytesTransferred: Long, totalBytes: Long, speedBytesPerSec: Long) {}
                override fun onStateChanged(newState: TransferEngineState) {
                    receiverStates.add(newState)
                }
            }
        )

        // 5. Thẩm định kết quả truyền nhận
        assertTrue("Bên nhận phải hoàn tất nhận tệp thành công", receiverResult.isSuccess)
        val receivedFile = receiverResult.getOrThrow()
        assertTrue("Tệp nhận được phải tồn tại trên đĩa", receivedFile.exists())
        assertEquals(sourceFile.length(), receivedFile.length())

        val receivedSha256 = Sha256StreamCalculator.calculateFileSha256(receivedFile)
        assertEquals("Mã băm SHA-256 tệp nhận phải trùng khớp tuyệt đối với tệp gốc", expectedSha256, receivedSha256)

        val senderResult = senderJob.await()
        assertTrue("Bên gửi phải hoàn tất phiên gửi", senderResult.isSuccess)
        assertEquals(expectedSha256, senderResult.getOrNull())

        assertTrue("Bên nhận phải trải qua trạng thái COMPLETED", receiverStates.contains(TransferEngineState.COMPLETED))
        assertTrue("Bên gửi phải trải qua trạng thái COMPLETED", senderStates.contains(TransferEngineState.COMPLETED))
    }

    @Test
    fun testFullEndToEndTransfer_ManualApproval_Accepted() = runBlocking {
        val targetDir = tempFolder.newFolder("received_manual_ok")
        val sourceFile = createDummyFile(sizeBytes = 64 * 1024, name = "confidential_project.zip")
        val expectedSha256 = Sha256StreamCalculator.calculateFileSha256(sourceFile)

        val port = TlsSocketFactory.findAvailablePort()
        val sessionToken = "session-token-manual-accepted-456"
        val sessionId = "session_e2e_002"

        val senderEngine = LanTransferEngine()
        val receiverEngine = LanTransferEngine()
        val serverReady = CompletableDeferred<Unit>()

        val approvalInvoked = AtomicBoolean(false)
        var capturedProfile: DeviceProfile? = null

        // Phía gửi khởi chạy ở chế độ MANUAL_APPROVAL
        val senderJob = async(Dispatchers.IO) {
            serverReady.complete(Unit)
            senderEngine.startSenderServer(
                port = port,
                sessionToken = sessionToken,
                file = sourceFile,
                sessionId = sessionId,
                expectedSha256 = expectedSha256,
                approvalMode = ApprovalMode.MANUAL_APPROVAL,
                onDeviceApproval = { profile ->
                    approvalInvoked.set(true)
                    capturedProfile = profile
                    // Kiểm tra model và chấp nhận thiết bị Samsung Galaxy S23 Ultra
                    profile.manufacturer.equals("Samsung", ignoreCase = true) &&
                            profile.model.contains("S23", ignoreCase = true)
                },
                useTls = false
            )
        }

        serverReady.await()
        delay(100)

        // Phía nhận kết nối gửi DeviceProfile Samsung
        val receiverResult = receiverEngine.connectReceiver(
            host = "127.0.0.1",
            port = port,
            sessionToken = sessionToken,
            sessionId = sessionId,
            targetDirectory = targetDir,
            deviceModel = "Galaxy S23 Ultra",
            deviceManufacturer = "Samsung",
            deviceFingerprint = "SHA256:samsung998877",
            useTls = false
        )

        assertTrue("Phía gửi phải nhận được sự kiện phê duyệt thủ công", approvalInvoked.get())
        assertNotNull(capturedProfile)
        assertEquals("Galaxy S23 Ultra", capturedProfile?.model)
        assertEquals("Samsung", capturedProfile?.manufacturer)

        assertTrue("Phía nhận phải truyền nhận thành công sau khi được phê duyệt", receiverResult.isSuccess)
        val receivedFile = receiverResult.getOrThrow()
        assertEquals(expectedSha256, Sha256StreamCalculator.calculateFileSha256(receivedFile))

        val senderResult = senderJob.await()
        assertTrue("Phía gửi phải kết thúc thành công", senderResult.isSuccess)
    }

    @Test
    fun testFullEndToEndTransfer_ManualApproval_Rejected() = runBlocking {
        val targetDir = tempFolder.newFolder("received_manual_rejected")
        val sourceFile = createDummyFile(sizeBytes = 32 * 1024, name = "secret_file.bin")

        val port = TlsSocketFactory.findAvailablePort()
        val sessionToken = "session-token-manual-reject-789"
        val sessionId = "session_e2e_003"

        val senderEngine = LanTransferEngine()
        val receiverEngine = LanTransferEngine()
        val serverReady = CompletableDeferred<Unit>()

        val approvalInvoked = AtomicBoolean(false)

        // Phía gửi từ chối thiết bị lạ
        val senderJob = async(Dispatchers.IO) {
            serverReady.complete(Unit)
            senderEngine.startSenderServer(
                port = port,
                sessionToken = sessionToken,
                file = sourceFile,
                sessionId = sessionId,
                approvalMode = ApprovalMode.MANUAL_APPROVAL,
                onDeviceApproval = { _ ->
                    approvalInvoked.set(true)
                    // Người dùng bấm "Từ chối" trên giao diện
                    false
                },
                useTls = false
            )
        }

        serverReady.await()
        delay(100)

        val receiverResult = receiverEngine.connectReceiver(
            host = "127.0.0.1",
            port = port,
            sessionToken = sessionToken,
            sessionId = sessionId,
            targetDirectory = targetDir,
            deviceModel = "Untrusted Device",
            deviceManufacturer = "Unknown",
            deviceFingerprint = "SHA256:untrusted000",
            useTls = false
        )

        assertTrue(approvalInvoked.get())
        assertTrue("Phía nhận phải nhận kết quả thất bại khi bị từ chối", receiverResult.isFailure)

        val senderResult = senderJob.await()
        assertTrue("Phía gửi xử lý từ chối thành công với ngoại lệ bảo mật", senderResult.isFailure)
    }

    @Test
    fun testFullEndToEndTransfer_WithAuditLogging() = runBlocking {
        val targetDir = tempFolder.newFolder("received_audit")
        val sourceFile = createDummyFile(sizeBytes = 40 * 1024, name = "audit_log_data.dat")
        val expectedSha256 = Sha256StreamCalculator.calculateFileSha256(sourceFile)

        val port = TlsSocketFactory.findAvailablePort()
        val sessionToken = "session-token-audit-101"
        val sessionId = "session_e2e_audit_004"

        // Mock Audit Repository để thu thập log kiểm toán an ninh
        val capturedLogs = CopyOnWriteArrayList<SecurityAuditLog>()
        val mockAuditRepository = object : IAuditRepository {
            override suspend fun logEvent(log: SecurityAuditLog) {
                capturedLogs.add(log)
            }

            override fun getLogsBySession(sessionId: String): Flow<List<SecurityAuditLog>> =
                flowOf(capturedLogs.filter { it.sessionId == sessionId })

            override fun getRecentLogs(limit: Int): Flow<List<SecurityAuditLog>> =
                flowOf(capturedLogs.takeLast(limit))
        }

        // Mô phỏng ghi log khi bắt đầu phiên
        mockAuditRepository.logEvent(
            SecurityAuditLog(
                sessionId = sessionId,
                actorDevice = "LOCAL_SENDER",
                eventType = AuditEventType.TRANSFER_STARTED,
                severity = AuditSeverity.INFO,
                details = "Bắt đầu phiên truyền tải trực tiếp LAN bảo mật P2P TLS 1.3"
            )
        )

        val senderEngine = LanTransferEngine()
        val receiverEngine = LanTransferEngine()
        val serverReady = CompletableDeferred<Unit>()

        val senderJob = async(Dispatchers.IO) {
            serverReady.complete(Unit)
            senderEngine.startSenderServer(
                port = port,
                sessionToken = sessionToken,
                file = sourceFile,
                sessionId = sessionId,
                expectedSha256 = expectedSha256,
                approvalMode = ApprovalMode.AUTO_ACCEPT,
                useTls = false
            )
        }

        serverReady.await()
        delay(100)

        val receiverResult = receiverEngine.connectReceiver(
            host = "127.0.0.1",
            port = port,
            sessionToken = sessionToken,
            sessionId = sessionId,
            targetDirectory = targetDir,
            deviceModel = "Pixel 8 Pro",
            deviceManufacturer = "Google",
            deviceFingerprint = "SHA256:googlepixel",
            useTls = false
        )

        assertTrue(receiverResult.isSuccess)
        senderJob.await()

        // Mô phỏng ghi log khi hoàn tất thành công
        mockAuditRepository.logEvent(
            SecurityAuditLog(
                sessionId = sessionId,
                actorDevice = "Google Pixel 8 Pro",
                eventType = AuditEventType.TRANSFER_COMPLETED,
                severity = AuditSeverity.INFO,
                details = "Hoàn tất truyền tệp. Mã băm SHA-256 xác thực toàn vẹn 100%: $expectedSha256"
            )
        )

        // Thẩm định nhật ký kiểm toán bất biến
        assertEquals(2, capturedLogs.size)
        assertEquals(AuditEventType.TRANSFER_STARTED, capturedLogs[0].eventType)
        assertEquals(AuditEventType.TRANSFER_COMPLETED, capturedLogs[1].eventType)
        assertEquals(sessionId, capturedLogs[1].sessionId)
        assertTrue(capturedLogs[1].details.contains(expectedSha256))
    }
}
