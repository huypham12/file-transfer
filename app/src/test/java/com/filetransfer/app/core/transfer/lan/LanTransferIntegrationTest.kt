package com.filetransfer.app.core.transfer.lan

import com.filetransfer.app.core.network.tls.TlsSocketFactory
import com.filetransfer.app.core.security.crypto.Sha256StreamCalculator
import com.filetransfer.app.domain.model.ApprovalMode
import com.filetransfer.app.domain.model.DeviceProfile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream

/**
 * Kiểm thử tích hợp tự động cho Động cơ Truyền tải LAN (LanTransferEngine) 1-N theo SDD Chương 8.
 */
class LanTransferIntegrationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun createDummyFile(sizeBytes: Int, name: String = "sample.dat"): File {
        val file = tempFolder.newFile(name)
        FileOutputStream(file).use { fos ->
            val buf = ByteArray(1024) { (it % 256).toByte() }
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
    fun testHappyPathLoopbackTransfer() = runBlocking {
        val targetDir = tempFolder.newFolder("received_files")
        val sourceFile = createDummyFile(sizeBytes = 150 * 1024, name = "tai_lieu_mat.pdf") // 150KB
        val sourceSha256 = Sha256StreamCalculator.calculateFileSha256(sourceFile)

        val port = TlsSocketFactory.findAvailablePort()
        val sessionToken = "session-token-happy-path"
        val sessionId = "session-id-happy-path"

        val receiverEngine = LanTransferEngine()
        val senderEngine = LanTransferEngine()

        val serverReady = CompletableDeferred<Unit>()

        val senderJob = async(Dispatchers.IO) {
            serverReady.complete(Unit)
            senderEngine.startSenderServer(
                port = port,
                sessionToken = sessionToken,
                file = sourceFile,
                sessionId = sessionId,
                expectedSha256 = sourceSha256,
                approvalMode = ApprovalMode.AUTO_ACCEPT,
                useTls = false
            )
        }

        serverReady.await()
        kotlinx.coroutines.delay(100)

        val receiverResult = receiverEngine.connectReceiver(
            host = "127.0.0.1",
            port = port,
            sessionToken = sessionToken,
            sessionId = sessionId,
            targetDirectory = targetDir,
            deviceModel = "Pixel 8 Pro",
            deviceManufacturer = "Google",
            deviceFingerprint = "fp-google-pixel-8",
            useTls = false
        )

        assertTrue("Phía nhận phải hoàn tất thành công", receiverResult.isSuccess)
        val receivedFile = receiverResult.getOrNull()!!
        assertTrue("Tệp đã nhận phải tồn tại trên đĩa", receivedFile.exists())
        assertEquals("tai_lieu_mat.pdf", receivedFile.name)
        assertEquals(sourceFile.length(), receivedFile.length())
        assertEquals(sourceSha256, Sha256StreamCalculator.calculateFileSha256(receivedFile))

        val senderResult = senderJob.await()
        assertTrue("Phía gửi phải hoàn tất thành công", senderResult.isSuccess)
    }

    @Test
    fun testResumeInterruptedTransferFromCheckpoint() = runBlocking {
        val targetDir = tempFolder.newFolder("resume_files")
        val totalSize = 200 * 1024 // 200KB
        val sourceFile = createDummyFile(sizeBytes = totalSize, name = "du_lieu_lon.bin")
        val sourceSha256 = Sha256StreamCalculator.calculateFileSha256(sourceFile)

        // Giả lập máy nhận đã có sẵn tệp tạm .tmp 80KB từ phiên truyền trước
        val partialSize = 80 * 1024
        val tempTargetFile = File(targetDir, "du_lieu_lon.bin.tmp")
        FileOutputStream(tempTargetFile).use { fos ->
            val srcBytes = sourceFile.readBytes()
            fos.write(srcBytes, 0, partialSize)
        }
        assertEquals(partialSize.toLong(), tempTargetFile.length())

        val port = TlsSocketFactory.findAvailablePort()
        val sessionToken = "session-token-resume"
        val sessionId = "session-id-resume"

        val receiverEngine = LanTransferEngine()
        val senderEngine = LanTransferEngine()

        val serverReady = CompletableDeferred<Unit>()

        val senderJob = async(Dispatchers.IO) {
            serverReady.complete(Unit)
            senderEngine.startSenderServer(
                port = port,
                sessionToken = sessionToken,
                file = sourceFile,
                sessionId = sessionId,
                expectedSha256 = sourceSha256,
                approvalMode = ApprovalMode.AUTO_ACCEPT,
                useTls = false
            )
        }

        serverReady.await()
        kotlinx.coroutines.delay(100)

        val receiverResult = receiverEngine.connectReceiver(
            host = "127.0.0.1",
            port = port,
            sessionToken = sessionToken,
            sessionId = sessionId,
            targetDirectory = targetDir,
            deviceModel = "Galaxy S24",
            deviceManufacturer = "Samsung",
            deviceFingerprint = "fp-samsung-s24",
            useTls = false
        )

        assertTrue("Resume phải thành công", receiverResult.isSuccess)
        val finalFile = receiverResult.getOrNull()!!
        assertEquals(totalSize.toLong(), finalFile.length())
        assertEquals(sourceSha256, Sha256StreamCalculator.calculateFileSha256(finalFile))

        val senderResult = senderJob.await()
        assertTrue("Sender hoàn tất resume", senderResult.isSuccess)
    }

    @Test
    fun testChecksumMismatchWipesTempFile() = runBlocking {
        val targetDir = tempFolder.newFolder("corrupted_dir")
        val sourceFile = createDummyFile(sizeBytes = 50 * 1024, name = "file_tamper.txt")

        val port = TlsSocketFactory.findAvailablePort()
        val sessionToken = "token-tamper-test"
        val sessionId = "session-tamper-test"
        val fakeSha256 = "0000000000000000000000000000000000000000000000000000000000000000"

        val receiverEngine = LanTransferEngine()
        val senderEngine = LanTransferEngine()

        val serverReady = CompletableDeferred<Unit>()

        // Phía Sender thông báo expectedSha256 giả mạo
        val senderJob = async(Dispatchers.IO) {
            serverReady.complete(Unit)
            senderEngine.startSenderServer(
                port = port,
                sessionToken = sessionToken,
                file = sourceFile,
                sessionId = sessionId,
                expectedSha256 = fakeSha256,
                approvalMode = ApprovalMode.AUTO_ACCEPT,
                useTls = false
            )
        }

        serverReady.await()
        kotlinx.coroutines.delay(100)

        val receiverResult = receiverEngine.connectReceiver(
            host = "127.0.0.1",
            port = port,
            sessionToken = sessionToken,
            sessionId = sessionId,
            targetDirectory = targetDir,
            useTls = false
        )

        assertTrue("Receiver phải phát hiện checksum mismatch", receiverResult.isFailure)

        // Kiểm tra tệp tạm phải bị tiêu hủy hoàn toàn bởi SafeFileDeleter
        val tempFile = File(targetDir, "file_tamper.txt.tmp")
        val finalFile = File(targetDir, "file_tamper.txt")
        assertFalse("Tệp tạm phải bị tiêu hủy sạch sẽ (Anti-Forensics Wipe)", tempFile.exists())
        assertFalse("Tệp đích không được phép xuất hiện khi băm sai", finalFile.exists())

        senderJob.cancel()
    }

    @Test
    fun testSenderHandlesReceiverSuddenCloseWithoutVerifyResult() = runBlocking {
        val sourceFile = createDummyFile(sizeBytes = 20 * 1024, name = "sudden_close.txt")
        val port = TlsSocketFactory.findAvailablePort()
        val sessionToken = "token-sudden-close"
        val sessionId = "session-sudden-close"

        val senderEngine = LanTransferEngine()
        val serverReady = CompletableDeferred<Unit>()

        val senderJob = async(Dispatchers.IO) {
            serverReady.complete(Unit)
            senderEngine.startSenderServer(
                port = port,
                sessionToken = sessionToken,
                file = sourceFile,
                sessionId = sessionId,
                approvalMode = ApprovalMode.AUTO_ACCEPT,
                useTls = false
            )
        }

        serverReady.await()
        kotlinx.coroutines.delay(100)

        // Giả lập client kết nối, bắt tay, rồi ngắt kết nối đột ngột
        val socket = java.net.Socket("127.0.0.1", port)
        val serializer = com.filetransfer.app.core.protocol.FrameSerializer()
        val parser = com.filetransfer.app.core.protocol.BinaryFrameParser()
        val os = socket.getOutputStream()
        val is_ = socket.getInputStream()

        // 1. Gửi CONNECT_REQUEST
        val req = com.filetransfer.app.core.protocol.ConnectRequestPayload(
            sessionToken = sessionToken,
            deviceModel = "Test Device",
            deviceManufacturer = "Test",
            deviceFingerprint = "fp-test",
            clientNonce = "nonce"
        )
        serializer.serializeTo(serializer.buildConnectRequestFrame(1, req), os)

        // 2. Nhận CONNECT_ACCEPTED
        val resp = parser.parseNextFrame(is_)
        assertNotNull(resp)

        // Đột ngột đóng socket mà không nhận stream
        socket.close()

        val senderResult = senderJob.await()
        assertTrue("Sender phải nhận biết lỗi ngắt kết nối và trả về failure", senderResult.isFailure)
    }

    @Test
    fun testSenderServerAcceptTimeout() = runBlocking {
        val sourceFile = createDummyFile(sizeBytes = 10 * 1024, name = "timeout.dat")
        val senderEngine = LanTransferEngine()
        val port = TlsSocketFactory.findAvailablePort()

        val start = System.currentTimeMillis()
        val result = senderEngine.startSenderServer(
            port = port,
            sessionToken = "token-timeout",
            file = sourceFile,
            sessionId = "session-timeout",
            serverTimeoutMs = 300, // Timeout ngắn 300ms
            useTls = false
        )
        val elapsed = System.currentTimeMillis() - start

        assertTrue("startSenderServer phải thất bại do timeout khi không có máy nhận kết nối", result.isFailure)
        assertTrue("Ngoại lệ phải là SocketTimeoutException", result.exceptionOrNull() is java.net.SocketTimeoutException)
        assertTrue("Thời gian trôi qua phải xấp xỉ 300ms (>=250ms)", elapsed >= 250)
    }

    @Test
    fun testBufferPoolNoLeakOnReadException() = runBlocking {
        val pool = com.filetransfer.app.core.transfer.buffer.BufferPoolManager(bufferSize = 1024, maxBuffers = 4)
        val initialPermits = pool.availablePermits()
        assertEquals(4, initialPermits)

        val receiverEngine = LanTransferEngine(bufferPool = pool)

        // Thử kết nối tới cổng không tồn tại -> fail ngay ở connect
        val result = receiverEngine.connectReceiver(
            host = "127.0.0.1",
            port = 49999,
            sessionToken = "dummy-token",
            sessionId = "dummy-session",
            targetDirectory = tempFolder.root,
            useTls = false
        )

        assertTrue(result.isFailure)
        assertEquals("Số permits Semaphore không được suy giảm sau lỗi kết nối", 4, pool.availablePermits())
    }

    @Test
    fun testTlsSocketFactoryThrowsWhenSslContextNull() {
        val factory = TlsSocketFactory(sslContext = null)

        // Phải ném IllegalStateException khi useTls = true mà sslContext = null
        try {
            factory.createServerSocket(useTls = true)
            fail("Phải ném IllegalStateException khi createServerSocket với useTls=true mà không có SSLContext")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("SSLContext chưa được cấu hình"))
        }

        try {
            factory.createSocket("127.0.0.1", 1234, useTls = true)
            fail("Phải ném IllegalStateException khi createSocket với useTls=true mà không có SSLContext")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("SSLContext chưa được cấu hình"))
        }
    }

    @Test
    fun testSdd81SenderServerModeWithReceiverClientMode() = runBlocking {
        val targetDir = tempFolder.newFolder("sdd81_received")
        val sourceFile = createDummyFile(sizeBytes = 120 * 1024, name = "sdd81_report.pdf")
        val sourceSha256 = Sha256StreamCalculator.calculateFileSha256(sourceFile)

        val port = TlsSocketFactory.findAvailablePort()
        val sessionToken = "session-token-secret-8889-xyz"
        val sessionId = "session-id-sdd81-test"

        val senderEngine = LanTransferEngine()
        val receiverEngine = LanTransferEngine()

        val serverReady = CompletableDeferred<Unit>()

        // 1. Phía gửi mở ServerSocket (SDD 8.1)
        val senderJob = async(Dispatchers.IO) {
            serverReady.complete(Unit)
            senderEngine.startSenderServer(
                port = port,
                sessionToken = sessionToken,
                file = sourceFile,
                sessionId = sessionId,
                expectedSha256 = sourceSha256,
                approvalMode = ApprovalMode.AUTO_ACCEPT,
                useTls = false
            )
        }

        serverReady.await()
        kotlinx.coroutines.delay(100) // Đợi socket lắng nghe

        // 2. Phía nhận kết nối Client Socket (SDD 8.1)
        val receiverResult = receiverEngine.connectReceiver(
            host = "127.0.0.1",
            port = port,
            sessionToken = sessionToken,
            sessionId = sessionId,
            targetDirectory = targetDir,
            deviceModel = "Xiaomi Redmi Turbo 4 Pro",
            deviceManufacturer = "Xiaomi",
            deviceFingerprint = "fingerprint-redmi-turbo-4",
            useTls = false
        )

        assertTrue("Phía nhận kết nối và tải tệp phải thành công", receiverResult.isSuccess)
        val receivedFile = receiverResult.getOrNull()!!
        assertTrue(receivedFile.exists())
        assertEquals(sourceFile.length(), receivedFile.length())
        val receivedSha = Sha256StreamCalculator.calculateFileSha256(receivedFile)
        assertEquals(sourceSha256, receivedSha)

        val senderResult = senderJob.await()
        assertTrue("Phía gửi phải hoàn tất thành công", senderResult.isSuccess)
        assertEquals(sourceSha256, senderResult.getOrNull())
    }

    @Test
    fun testSdd81SenderServerRejectsInvalidToken() = runBlocking {
        val targetDir = tempFolder.newFolder("sdd81_reject")
        val sourceFile = createDummyFile(sizeBytes = 10 * 1024, name = "reject_test.bin")

        val port = TlsSocketFactory.findAvailablePort()
        val validToken = "valid-session-token-123"
        val invalidToken = "attacker-forged-token-999"

        val senderEngine = LanTransferEngine()
        val receiverEngine = LanTransferEngine()

        val serverReady = CompletableDeferred<Unit>()

        val senderJob = async(Dispatchers.IO) {
            serverReady.complete(Unit)
            senderEngine.startSenderServer(
                port = port,
                sessionToken = validToken,
                file = sourceFile,
                sessionId = "session-reject-test",
                approvalMode = ApprovalMode.AUTO_ACCEPT,
                useTls = false
            )
        }

        serverReady.await()
        kotlinx.coroutines.delay(100)

        // Phía nhận kết nối với token giả mạo
        val receiverResult = receiverEngine.connectReceiver(
            host = "127.0.0.1",
            port = port,
            sessionToken = invalidToken,
            sessionId = "session-reject-test",
            targetDirectory = targetDir,
            useTls = false
        )

        assertTrue("Phía nhận với token sai phải bị từ chối", receiverResult.isFailure)
        val senderResult = senderJob.await()
        assertTrue("Phía gửi phải phát hiện và từ chối token sai", senderResult.isFailure)
    }

    @Test
    fun testSdd81SenderServerManualApprovalFlow() = runBlocking {
        val targetDir = tempFolder.newFolder("sdd81_manual")
        val sourceFile = createDummyFile(sizeBytes = 20 * 1024, name = "manual_approval.txt")

        val port = TlsSocketFactory.findAvailablePort()
        val token = "session-manual-token-777"

        val senderEngine = LanTransferEngine()
        val receiverEngine = LanTransferEngine()

        val serverReady = CompletableDeferred<Unit>()

        // Trường hợp người gửi bấm TỪ CHỐI trên Dialog
        val senderJob = async(Dispatchers.IO) {
            serverReady.complete(Unit)
            senderEngine.startSenderServer(
                port = port,
                sessionToken = token,
                file = sourceFile,
                sessionId = "session-manual-test",
                approvalMode = ApprovalMode.MANUAL_APPROVAL,
                onDeviceApproval = { _ ->
                    // Giả lập từ chối thiết bị lạ
                    false
                },
                useTls = false
            )
        }

        serverReady.await()
        kotlinx.coroutines.delay(100)

        val receiverResult = receiverEngine.connectReceiver(
            host = "127.0.0.1",
            port = port,
            sessionToken = token,
            sessionId = "session-manual-test",
            targetDirectory = targetDir,
            deviceModel = "Suspicious Device",
            useTls = false
        )

        assertTrue("Kết nối phải bị từ chối do người dùng không phê duyệt", receiverResult.isFailure)
        val senderResult = senderJob.await()
        assertTrue("Sender phải ghi nhận từ chối kết nối", senderResult.isFailure)
    }

    @Test
    fun testFolderTransferOverLanSocket() = runBlocking {
        val targetDir = tempFolder.newFolder("lan_folder_received")
        val sourceDir = tempFolder.newFolder("lan_source_folder")

        // Tạo cấu trúc thư mục con và tệp
        val sub1 = File(sourceDir, "docs").apply { mkdirs() }
        val sub2 = File(sourceDir, "src/main").apply { mkdirs() }

        val f1 = File(sub1, "spec.md").apply { writeText("Security Specification v4") }
        val f2 = File(sub2, "App.kt").apply { writeText("class App { fun run() = true }") }
        val f3 = File(sourceDir, "root.txt").apply { writeText("Root file content") }

        val port = TlsSocketFactory.findAvailablePort()
        val token = "folder-token-999"

        val senderEngine = LanTransferEngine()
        val receiverEngine = LanTransferEngine()

        val serverReady = CompletableDeferred<Unit>()

        val senderJob = async(Dispatchers.IO) {
            serverReady.complete(Unit)
            senderEngine.startSenderServer(
                port = port,
                sessionToken = token,
                file = sourceDir,
                sessionId = "session-folder-transfer",
                approvalMode = ApprovalMode.AUTO_ACCEPT,
                useTls = false
            )
        }

        serverReady.await()
        kotlinx.coroutines.delay(100)

        val receiverResult = receiverEngine.connectReceiver(
            host = "127.0.0.1",
            port = port,
            sessionToken = token,
            sessionId = "session-folder-transfer",
            targetDirectory = targetDir,
            useTls = false
        )

        assertTrue("Quá trình nhận thư mục phải thành công", receiverResult.isSuccess)
        val senderResult = senderJob.await()
        assertTrue("Quá trình gửi thư mục phải thành công", senderResult.isSuccess)

        val extractedDir = receiverResult.getOrThrow()
        assertTrue("Đích đến phải là thư mục", extractedDir.isDirectory)

        // Kiểm tra tính toàn vẹn của các tệp đã giải nén
        val extF1 = File(extractedDir, "docs/spec.md")
        val extF2 = File(extractedDir, "src/main/App.kt")
        val extF3 = File(extractedDir, "root.txt")

        assertTrue(extF1.exists())
        assertEquals("Security Specification v4", extF1.readText())

        assertTrue(extF2.exists())
        assertEquals("class App { fun run() = true }", extF2.readText())

        assertTrue(extF3.exists())
        assertEquals("Root file content", extF3.readText())
    }

    @Test
    fun testReceiverRejectsWhenStorageInsufficient() = runBlocking {
        val targetDir = tempFolder.newFolder("lan_insufficient_space")
        val sourceFile = createDummyFile(sizeBytes = 10 * 1024, name = "data.bin")

        val port = TlsSocketFactory.findAvailablePort()
        val token = "storage-token-302"

        val senderEngine = LanTransferEngine()

        // Receiver được cấu hình với storageAuditor giả lập đĩa đầy
        val mockAuditor = com.filetransfer.app.core.platform.storage.StatFsStorageAuditor()
        mockAuditor.simulateStorage(availableBytes = 1024L) // Chỉ có 1KB trống, nhỏ hơn 10KB + 50MB
        val receiverEngine = LanTransferEngine(storageAuditor = mockAuditor)

        val serverReady = CompletableDeferred<Unit>()

        val senderJob = async(Dispatchers.IO) {
            serverReady.complete(Unit)
            senderEngine.startSenderServer(
                port = port,
                sessionToken = token,
                file = sourceFile,
                sessionId = "session-storage-test",
                approvalMode = ApprovalMode.AUTO_ACCEPT,
                useTls = false
            )
        }

        serverReady.await()
        kotlinx.coroutines.delay(100)

        val receiverResult = receiverEngine.connectReceiver(
            host = "127.0.0.1",
            port = port,
            sessionToken = token,
            sessionId = "session-storage-test",
            targetDirectory = targetDir,
            useTls = false
        )

        assertTrue("Receiver phải từ chối khi bộ nhớ không đủ", receiverResult.isFailure)
        assertTrue(
            "Lỗi phải chứa mã 0x302",
            receiverResult.exceptionOrNull()?.message?.contains("0x302") == true
        )
        senderJob.cancel()
    }
}
