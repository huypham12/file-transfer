package com.filetransfer.app.core.transfer.lan

import com.filetransfer.app.core.network.tls.TlsSocketFactory
import com.filetransfer.app.core.security.crypto.Sha256StreamCalculator
import com.filetransfer.app.domain.model.ApprovalMode
import com.filetransfer.app.domain.model.DeviceProfile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SDD 7.4 & 8.1: Kiểm thử tích hợp Luồng phê duyệt thiết bị 1-N Secure Drop qua Socket LAN.
 * Kiểm chứng thiết bị đối tác "Xiaomi Redmi Turbo 4 Pro" với cả hai chế độ AUTO_ACCEPT và MANUAL_APPROVAL.
 */
class SessionApprovalFlowIntegrationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val targetModel = "Redmi Turbo 4 Pro"
    private val targetManufacturer = "Xiaomi"
    private val targetFingerprint = "SHA256:7a4c9e8b1d2f0a3e"

    private fun createDummyFile(sizeBytes: Int, name: String = "payload.bin"): File {
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
    fun testAutoAcceptApprovalFlow() = runBlocking {
        val targetDir = tempFolder.newFolder("recv_auto_accept")
        val sourceFile = createDummyFile(sizeBytes = 64 * 1024, name = "auto_accept_data.bin")
        val sourceSha256 = Sha256StreamCalculator.calculateFileSha256(sourceFile)

        val port = TlsSocketFactory.findAvailablePort()
        val token = "session-token-auto-123"
        val sessionId = "sess_auto_001"

        val senderEngine = LanTransferEngine()
        val receiverEngine = LanTransferEngine()
        val serverReady = CompletableDeferred<Unit>()

        // Phía gửi khởi động ở chế độ AUTO_ACCEPT
        val senderJob = async(Dispatchers.IO) {
            serverReady.complete(Unit)
            senderEngine.startSenderServer(
                port = port,
                sessionToken = token,
                file = sourceFile,
                sessionId = sessionId,
                expectedSha256 = sourceSha256,
                approvalMode = ApprovalMode.AUTO_ACCEPT,
                useTls = false
            )
        }

        serverReady.await()
        delay(100)

        // Phía nhận kết nối gửi định danh Xiaomi Redmi Turbo 4 Pro
        val receiverResult = receiverEngine.connectReceiver(
            host = "127.0.0.1",
            port = port,
            sessionToken = token,
            sessionId = sessionId,
            targetDirectory = targetDir,
            deviceModel = targetModel,
            deviceManufacturer = targetManufacturer,
            deviceFingerprint = targetFingerprint,
            useTls = false
        )

        assertTrue("Phía nhận phải truyền nhận thành công ở chế độ AUTO_ACCEPT", receiverResult.isSuccess)
        val receivedFile = receiverResult.getOrThrow()
        assertTrue(receivedFile.exists())
        assertEquals(sourceFile.length(), receivedFile.length())
        assertEquals(sourceSha256, Sha256StreamCalculator.calculateFileSha256(receivedFile))

        val senderResult = senderJob.await()
        assertTrue("Phía gửi phải hoàn tất thành công", senderResult.isSuccess)
    }

    @Test
    fun testManualApprovalAcceptedFlow() = runBlocking {
        val targetDir = tempFolder.newFolder("recv_manual_approved")
        val sourceFile = createDummyFile(sizeBytes = 48 * 1024, name = "manual_approved_data.bin")
        val sourceSha256 = Sha256StreamCalculator.calculateFileSha256(sourceFile)

        val port = TlsSocketFactory.findAvailablePort()
        val token = "session-token-manual-ok-456"
        val sessionId = "sess_manual_ok_002"

        val senderEngine = LanTransferEngine()
        val receiverEngine = LanTransferEngine()
        val serverReady = CompletableDeferred<Unit>()

        val callbackInvoked = AtomicBoolean(false)
        var capturedPeerProfile: DeviceProfile? = null

        // Phía gửi khởi động ở chế độ MANUAL_APPROVAL
        val senderJob = async(Dispatchers.IO) {
            serverReady.complete(Unit)
            senderEngine.startSenderServer(
                port = port,
                sessionToken = token,
                file = sourceFile,
                sessionId = sessionId,
                expectedSha256 = sourceSha256,
                approvalMode = ApprovalMode.MANUAL_APPROVAL,
                onDeviceApproval = { profile ->
                    callbackInvoked.set(true)
                    capturedPeerProfile = profile
                    // Kiểm tra model thiết bị và đồng ý phê duyệt
                    profile.model == targetModel && profile.manufacturer == targetManufacturer
                },
                useTls = false
            )
        }

        serverReady.await()
        delay(100)

        val receiverResult = receiverEngine.connectReceiver(
            host = "127.0.0.1",
            port = port,
            sessionToken = token,
            sessionId = sessionId,
            targetDirectory = targetDir,
            deviceModel = targetModel,
            deviceManufacturer = targetManufacturer,
            deviceFingerprint = targetFingerprint,
            useTls = false
        )

        assertTrue(callbackInvoked.get())
        assertNotNull(capturedPeerProfile)
        assertEquals(targetModel, capturedPeerProfile?.model)
        assertEquals(targetManufacturer, capturedPeerProfile?.manufacturer)
        assertEquals(targetFingerprint, capturedPeerProfile?.fingerprint)

        assertTrue("Phía nhận phải truyền nhận thành công khi được phê duyệt", receiverResult.isSuccess)
        val receivedFile = receiverResult.getOrThrow()
        assertEquals(sourceSha256, Sha256StreamCalculator.calculateFileSha256(receivedFile))

        val senderResult = senderJob.await()
        assertTrue("Phía gửi phải hoàn tất thành công", senderResult.isSuccess)
    }

    @Test
    fun testManualApprovalRejectedFlow() = runBlocking {
        val targetDir = tempFolder.newFolder("recv_manual_rejected")
        val sourceFile = createDummyFile(sizeBytes = 32 * 1024, name = "manual_rejected_data.bin")

        val port = TlsSocketFactory.findAvailablePort()
        val token = "session-token-manual-reject-789"
        val sessionId = "sess_manual_rej_003"

        val senderEngine = LanTransferEngine()
        val receiverEngine = LanTransferEngine()
        val serverReady = CompletableDeferred<Unit>()

        val callbackInvoked = AtomicBoolean(false)

        // Phía gửi từ chối kết nối
        val senderJob = async(Dispatchers.IO) {
            serverReady.complete(Unit)
            senderEngine.startSenderServer(
                port = port,
                sessionToken = token,
                file = sourceFile,
                sessionId = sessionId,
                approvalMode = ApprovalMode.MANUAL_APPROVAL,
                onDeviceApproval = { _ ->
                    callbackInvoked.set(true)
                    // Người dùng bấm "Từ chối"
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
            sessionToken = token,
            sessionId = sessionId,
            targetDirectory = targetDir,
            deviceModel = targetModel,
            deviceManufacturer = targetManufacturer,
            deviceFingerprint = targetFingerprint,
            useTls = false
        )

        assertTrue(callbackInvoked.get())
        assertTrue("Phía nhận phải nhận kết quả thất bại do bị từ chối", receiverResult.isFailure)

        val senderResult = senderJob.await()
        assertTrue("Phía gửi phải kết thúc với ngoại lệ từ chối thiết bị", senderResult.isFailure)
    }

    @Test
    fun testTokenMismatchRejectionFlow() = runBlocking {
        val targetDir = tempFolder.newFolder("recv_token_mismatch")
        val sourceFile = createDummyFile(sizeBytes = 16 * 1024, name = "token_mismatch.bin")

        val port = TlsSocketFactory.findAvailablePort()
        val correctToken = "session-token-correct-001"
        val wrongToken = "session-token-wrong-999"
        val sessionId = "sess_mismatch_004"

        val senderEngine = LanTransferEngine()
        val receiverEngine = LanTransferEngine()
        val serverReady = CompletableDeferred<Unit>()

        val senderJob = async(Dispatchers.IO) {
            serverReady.complete(Unit)
            senderEngine.startSenderServer(
                port = port,
                sessionToken = correctToken,
                file = sourceFile,
                sessionId = sessionId,
                approvalMode = ApprovalMode.AUTO_ACCEPT,
                useTls = false
            )
        }

        serverReady.await()
        delay(100)

        val receiverResult = receiverEngine.connectReceiver(
            host = "127.0.0.1",
            port = port,
            sessionToken = wrongToken,
            sessionId = sessionId,
            targetDirectory = targetDir,
            deviceModel = targetModel,
            useTls = false
        )

        assertTrue("Phía nhận với sai token phải nhận kết quả thất bại (0x101)", receiverResult.isFailure)
        val senderResult = senderJob.await()
        assertTrue("Phía gửi phải báo lỗi session token không khớp", senderResult.isFailure)
    }
}

