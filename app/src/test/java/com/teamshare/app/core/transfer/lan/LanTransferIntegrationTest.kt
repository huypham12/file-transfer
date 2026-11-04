package com.teamshare.app.core.transfer.lan

import com.teamshare.app.core.network.tls.TlsSocketFactory
import com.teamshare.app.core.security.crypto.Sha256StreamCalculator
import com.teamshare.app.domain.model.Team
import com.teamshare.app.domain.model.TeamMember
import com.teamshare.app.domain.repository.ITeamRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

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
        val receiverEngine = LanTransferEngine()
        val senderEngine = LanTransferEngine()

        val receiverReady = CompletableDeferred<Unit>()
        val receiverCompleted = CompletableDeferred<File>()

        // 1. Chạy Receiver trên IO Coroutine
        val receiverJob = async(Dispatchers.IO) {
            receiverReady.complete(Unit)
            receiverEngine.receiveFile(
                port = port,
                targetDirectory = targetDir,
                expectedTeamId = "team-security-01",
                useTls = false
            )
        }

        receiverReady.await()
        kotlinx.coroutines.delay(100) // Đợi server socket lắng nghe sẵn sàng

        // 2. Chạy Sender
        val sendResult = senderEngine.sendFile(
            host = "127.0.0.1",
            port = port,
            file = sourceFile,
            docId = "doc-001",
            teamId = "team-security-01",
            memberId = "mem-alice",
            deviceFingerprint = "fingerprint-alice-123",
            role = "MEMBER",
            credentialData = "valid-credential",
            useTls = false
        )

        assertTrue("Gửi tệp phải thành công", sendResult.isSuccess)
        assertEquals(sourceSha256, sendResult.getOrNull())

        val receiveResult = receiverJob.await()
        assertTrue("Nhận tệp phải thành công", receiveResult.isSuccess)

        val receivedFile = receiveResult.getOrNull()!!
        assertTrue("Tệp đã nhận phải tồn tại trên đĩa", receivedFile.exists())
        assertEquals("tai_lieu_mat.pdf", receivedFile.name)
        assertEquals(sourceFile.length(), receivedFile.length())

        val receivedSha256 = Sha256StreamCalculator.calculateFileSha256(receivedFile)
        assertEquals("Mã băm SHA-256 tệp nhận phải trùng khớp tuyệt đối với tệp gốc", sourceSha256, receivedSha256)
    }

    @Test
    fun testResumeInterruptedTransferFromCheckpoint() = runBlocking {
        val targetDir = tempFolder.newFolder("resume_files")
        val totalSize = 200 * 1024 // 200KB
        val sourceFile = createDummyFile(sizeBytes = totalSize, name = "du_lieu_lon.bin")
        val sourceSha256 = Sha256StreamCalculator.calculateFileSha256(sourceFile)

        // Giả lập máy nhận đã có sẵn tệp tạm 80KB từ phiên truyền trước
        val partialSize = 80 * 1024
        val tempTargetFile = File(targetDir, "du_lieu_lon.bin.tmp")
        FileOutputStream(tempTargetFile).use { fos ->
            val srcBytes = sourceFile.readBytes()
            fos.write(srcBytes, 0, partialSize)
        }
        assertEquals(partialSize.toLong(), tempTargetFile.length())

        val port = TlsSocketFactory.findAvailablePort()
        val receiverEngine = LanTransferEngine()
        val senderEngine = LanTransferEngine()

        val receiverReady = CompletableDeferred<Unit>()

        val receiverJob = async(Dispatchers.IO) {
            receiverReady.complete(Unit)
            receiverEngine.receiveFile(
                port = port,
                targetDirectory = targetDir,
                expectedTeamId = "team-resume-01",
                useTls = false
            )
        }

        receiverReady.await()
        kotlinx.coroutines.delay(100)

        // Sender truyền tiếp
        val sendResult = senderEngine.sendFile(
            host = "127.0.0.1",
            port = port,
            file = sourceFile,
            docId = "doc-resume-002",
            teamId = "team-resume-01",
            memberId = "mem-bob",
            deviceFingerprint = "fingerprint-bob-456",
            role = "MEMBER",
            credentialData = "valid-credential",
            useTls = false
        )

        assertTrue(sendResult.isSuccess)
        val receiveResult = receiverJob.await()
        assertTrue(receiveResult.isSuccess)

        val finalFile = receiveResult.getOrNull()!!
        assertEquals(totalSize.toLong(), finalFile.length())
        assertEquals(sourceSha256, Sha256StreamCalculator.calculateFileSha256(finalFile))
    }

    @Test
    fun testRejectMismatchTeamId() = runBlocking {
        val targetDir = tempFolder.newFolder("security_team_dir")
        val sourceFile = createDummyFile(sizeBytes = 10 * 1024, name = "tai_lieu_nhom.txt")

        val port = TlsSocketFactory.findAvailablePort()
        val receiverEngine = LanTransferEngine()
        val senderEngine = LanTransferEngine()

        val receiverReady = CompletableDeferred<Unit>()

        val receiverJob = async(Dispatchers.IO) {
            receiverReady.complete(Unit)
            receiverEngine.receiveFile(
                port = port,
                targetDirectory = targetDir,
                expectedTeamId = "team-CORRECT", // Yêu cầu team-CORRECT
                useTls = false
            )
        }

        receiverReady.await()
        kotlinx.coroutines.delay(100)

        // Phía Sender gửi teamId khác
        val sendResult = senderEngine.sendFile(
            host = "127.0.0.1",
            port = port,
            file = sourceFile,
            docId = "doc-wrong-team",
            teamId = "team-ATTACKER", // Sai teamId
            memberId = "mem-eve",
            deviceFingerprint = "fp-eve",
            role = "MEMBER",
            credentialData = "fake-cred",
            useTls = false
        )

        val receiveResult = receiverJob.await()
        assertTrue("Receiver phải từ chối khi TeamId không khớp", receiveResult.isFailure)
    }

    @Test
    fun testRejectRevokedMember() = runBlocking {
        val targetDir = tempFolder.newFolder("revoked_member_dir")
        val sourceFile = createDummyFile(sizeBytes = 10 * 1024, name = "file_revoked.txt")

        // Mock ITeamRepository trả về thành viên đã bị thu hồi quyền
        val mockTeamRepo = object : ITeamRepository {
            override suspend fun createTeam(team: Team): Result<Team> = Result.success(team)
            override fun getTeamById(teamId: String): Flow<Team?> = flowOf(null)
            override fun getAllTeams(): Flow<List<Team>> = flowOf(emptyList())
            override suspend fun disbandTeam(teamId: String): Result<Unit> = Result.success(Unit)
            override suspend fun addMember(member: TeamMember): Result<Unit> = Result.success(Unit)
            override suspend fun revokeMember(teamId: String, memberId: String): Result<Unit> = Result.success(Unit)
            override fun getMembersByTeam(teamId: String): Flow<List<TeamMember>> = flowOf(emptyList())
            override suspend fun isMemberActive(teamId: String, memberId: String): Boolean = false // Bị thu hồi!
        }

        val port = TlsSocketFactory.findAvailablePort()
        val receiverEngine = LanTransferEngine(teamRepository = mockTeamRepo)
        val senderEngine = LanTransferEngine()

        val receiverReady = CompletableDeferred<Unit>()

        val receiverJob = async(Dispatchers.IO) {
            receiverReady.complete(Unit)
            receiverEngine.receiveFile(
                port = port,
                targetDirectory = targetDir,
                expectedTeamId = "team-test",
                useTls = false
            )
        }

        receiverReady.await()
        kotlinx.coroutines.delay(100)

        val sendResult = senderEngine.sendFile(
            host = "127.0.0.1",
            port = port,
            file = sourceFile,
            docId = "doc-revoked-001",
            teamId = "team-test",
            memberId = "mem-revoked",
            deviceFingerprint = "fp-revoked",
            role = "MEMBER",
            credentialData = "old-cred",
            useTls = false
        )

        val receiveResult = receiverJob.await()
        assertTrue("Receiver phải từ chối thành viên đã bị thu hồi quyền (CRL check)", receiveResult.isFailure)
    }

    @Test
    fun testChecksumMismatchWipesTempFile() = runBlocking {
        val targetDir = tempFolder.newFolder("corrupted_dir")
        val sourceFile = createDummyFile(sizeBytes = 50 * 1024, name = "file_tamper.txt")

        val port = TlsSocketFactory.findAvailablePort()
        // Tạo sender tùy biến gửi file nhưng manifest ghi sai hash
        val receiverEngine = LanTransferEngine()
        val receiverReady = CompletableDeferred<Unit>()

        val receiverJob = async(Dispatchers.IO) {
            receiverReady.complete(Unit)
            receiverEngine.receiveFile(
                port = port,
                targetDirectory = targetDir,
                expectedTeamId = "team-tamper",
                useTls = false
            )
        }

        receiverReady.await()
        kotlinx.coroutines.delay(100)

        // Kết nối socket thủ công và gửi manifest có hash giả mạo
        val socket = java.net.Socket("127.0.0.1", port)
        val os = socket.getOutputStream()
        val serializer = com.teamshare.app.core.protocol.FrameSerializer()

        // 1. HANDSHAKE_AUTH
        val authPayload = com.teamshare.app.core.protocol.HandshakeAuthPayload(
            teamId = "team-tamper",
            memberId = "mem-alice",
            deviceFingerprint = "fp-alice",
            role = "MEMBER",
            credentialData = "cred",
            clientNonce = "nonce"
        )
        serializer.serializeTo(serializer.buildHandshakeAuthFrame(1, authPayload), os)

        // 2. TRANSFER_MANIFEST với expectedSha256 GIẢ MẠO
        val fakeSha256 = "0000000000000000000000000000000000000000000000000000000000000000"
        val manifest = com.teamshare.app.core.protocol.TransferManifestPayload(
            docId = "doc-fake",
            fileName = "file_tamper.txt",
            fileSize = sourceFile.length(),
            mimeType = "text/plain",
            expectedSha256 = fakeSha256
        )
        serializer.serializeTo(serializer.buildTransferManifestFrame(1, manifest), os)

        val is_ = socket.getInputStream()
        val parser = com.teamshare.app.core.protocol.BinaryFrameParser()
        val resp = parser.parseNextFrame(is_)
        assertNotNull(resp) // MANIFEST_RESPONSE

        // 3. Gửi DATA_CHUNK
        val fileBytes = sourceFile.readBytes()
        val chunk = com.teamshare.app.core.protocol.DataChunkPayload(0, 0L, fileBytes)
        serializer.serializeTo(serializer.buildDataChunkFrame(1, chunk), os)

        val receiveResult = receiverJob.await()
        assertTrue("Receiver phải phát hiện checksum mismatch", receiveResult.isFailure)

        // Kiểm tra tệp tạm phải bị tiêu hủy hoàn toàn bởi SafeFileDeleter
        val tempFile = File(targetDir, "file_tamper.txt.tmp")
        val finalFile = File(targetDir, "file_tamper.txt")
        assertFalse("Tệp tạm phải bị tiêu hủy sạch sẽ (Anti-Forensics Wipe)", tempFile.exists())
        assertFalse("Tệp đích không được phép xuất hiện khi băm sai", finalFile.exists())

        socket.close()
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val hexChars = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            hexChars[i * 2] = Character.forDigit(v ushr 4, 16)
            hexChars[i * 2 + 1] = Character.forDigit(v and 0x0F, 16)
        }
        return String(hexChars)
    }

    @Test
    fun testRejectInvalidOwnerSignature() = runBlocking {
        val targetDir = tempFolder.newFolder("invalid_sig_dir")
        val sourceFile = createDummyFile(sizeBytes = 10 * 1024, name = "fake_sig.txt")

        val keyManager = com.teamshare.app.core.security.keystore.SoftwareKeyManager()
        val teamId = "team-crypto-01"
        val ownerPubKey = keyManager.generateOwnerMasterKey(teamId)

        val port = TlsSocketFactory.findAvailablePort()
        val receiverEngine = LanTransferEngine(keyManager = keyManager)
        val senderEngine = LanTransferEngine()

        val receiverReady = CompletableDeferred<Unit>()

        val receiverJob = async(Dispatchers.IO) {
            receiverReady.complete(Unit)
            receiverEngine.receiveFile(
                port = port,
                targetDirectory = targetDir,
                expectedTeamId = teamId,
                ownerPublicKey = ownerPubKey,
                useTls = false
            )
        }

        receiverReady.await()
        kotlinx.coroutines.delay(100)

        // Sender gửi chữ ký giả mạo
        val sendResult = senderEngine.sendFile(
            host = "127.0.0.1",
            port = port,
            file = sourceFile,
            docId = "doc-fake-sig",
            teamId = teamId,
            memberId = "mem-fake",
            deviceFingerprint = "fp-fake",
            role = "MEMBER",
            credentialData = "00112233445566778899aabbccddeeff", // Fake signature hex
            useTls = false
        )

        val receiveResult = receiverJob.await()
        assertTrue("Receiver phải từ chối khi chữ ký số Owner không hợp lệ", receiveResult.isFailure)
        assertTrue(receiveResult.exceptionOrNull() is SecurityException)
    }

    @Test
    fun testAcceptValidOwnerSignature() = runBlocking {
        val targetDir = tempFolder.newFolder("valid_sig_dir")
        val sourceFile = createDummyFile(sizeBytes = 10 * 1024, name = "valid_sig.txt")
        val expectedSha = Sha256StreamCalculator.calculateFileSha256(sourceFile)

        val keyManager = com.teamshare.app.core.security.keystore.SoftwareKeyManager()
        val teamId = "team-crypto-valid"
        val memberId = "mem-alice"
        val deviceFp = "fp-alice"
        val role = "MEMBER"

        val ownerPubKey = keyManager.generateOwnerMasterKey(teamId)
        val alias = com.teamshare.app.core.security.keystore.IKeyManager.MASTER_KEY_PREFIX + teamId

        // Ký hợp lệ thông tin thành viên
        val claim = "$teamId:$memberId:$deviceFp:$role".toByteArray(Charsets.UTF_8)
        val validSigBytes = keyManager.signData(alias, claim)
        val validSigHex = bytesToHex(validSigBytes)

        val port = TlsSocketFactory.findAvailablePort()
        val receiverEngine = LanTransferEngine(keyManager = keyManager)
        val senderEngine = LanTransferEngine()

        val receiverReady = CompletableDeferred<Unit>()

        val receiverJob = async(Dispatchers.IO) {
            receiverReady.complete(Unit)
            receiverEngine.receiveFile(
                port = port,
                targetDirectory = targetDir,
                expectedTeamId = teamId,
                ownerPublicKey = ownerPubKey,
                useTls = false
            )
        }

        receiverReady.await()
        kotlinx.coroutines.delay(100)

        val sendResult = senderEngine.sendFile(
            host = "127.0.0.1",
            port = port,
            file = sourceFile,
            docId = "doc-valid-sig",
            teamId = teamId,
            memberId = memberId,
            deviceFingerprint = deviceFp,
            role = role,
            credentialData = validSigHex,
            useTls = false
        )

        assertTrue("Gửi file có chữ ký hợp lệ phải thành công", sendResult.isSuccess)
        val receiveResult = receiverJob.await()
        assertTrue("Nhận file có chữ ký hợp lệ phải thành công", receiveResult.isSuccess)
        assertEquals(expectedSha, Sha256StreamCalculator.calculateFileSha256(receiveResult.getOrNull()!!))
    }

    @Test
    fun testSenderHandlesReceiverSuddenCloseWithoutVerifyResult() = runBlocking {
        val sourceFile = createDummyFile(sizeBytes = 20 * 1024, name = "sudden_close.txt")
        val port = TlsSocketFactory.findAvailablePort()

        val serverSocket = java.net.ServerSocket(port)
        val senderEngine = LanTransferEngine()

        // Giả lập máy nhận: chấp nhận kết nối, nhận Handshake + Manifest + Data, rồi đột ngột đóng socket mà không gửi VERIFY_RESULT
        val mockReceiverJob = async(Dispatchers.IO) {
            val sock = serverSocket.accept()
            val parser = com.teamshare.app.core.protocol.BinaryFrameParser()
            val serializer = com.teamshare.app.core.protocol.FrameSerializer()
            val is_ = sock.getInputStream()
            val os_ = sock.getOutputStream()

            // 1. Nhận HANDSHAKE_AUTH
            val hFrame = parser.parseNextFrame(is_)
            // 2. Nhận TRANSFER_MANIFEST
            val mFrame = parser.parseNextFrame(is_)
            // Trả lời MANIFEST_RESPONSE
            val mResp = com.teamshare.app.core.protocol.ManifestResponsePayload(accepted = true, startOffset = 0L)
            serializer.serializeTo(serializer.buildManifestResponseFrame(mFrame!!.streamId, mResp), os_)

            // 3. Đọc dữ liệu rồi đóng kết nối bất ngờ
            var f = parser.parseNextFrame(is_)
            while (f != null && f.msgType == com.teamshare.app.core.protocol.ChunkFrame.TYPE_DATA_CHUNK) {
                // Đóng kết nối sạch sẽ giữa chừng
                break
            }
            sock.close()
            serverSocket.close()
        }

        kotlinx.coroutines.delay(100)

        // Sender truyền file
        val sendResult = senderEngine.sendFile(
            host = "127.0.0.1",
            port = port,
            file = sourceFile,
            docId = "doc-sudden-close",
            teamId = "team-test",
            memberId = "mem-test",
            deviceFingerprint = "fp-test",
            role = "MEMBER",
            credentialData = "dummy",
            useTls = false
        )

        mockReceiverJob.await()
        // Phía Sender phải fail lập tức do EOFException, KHÔNG ĐƯỢC TREO luồng await()
        assertTrue("Sender phải nhận biết lỗi ngắt kết nối và trả về failure", sendResult.isFailure)
    }

    @Test
    fun testReceiverSocketTimeout() = runBlocking {
        val targetDir = tempFolder.newFolder("timeout_dir")
        val receiverEngine = LanTransferEngine()
        val port = TlsSocketFactory.findAvailablePort()

        val start = System.currentTimeMillis()
        val result = receiverEngine.receiveFile(
            port = port,
            targetDirectory = targetDir,
            serverTimeoutMs = 300 // Timeout ngắn 300ms
        )
        val elapsed = System.currentTimeMillis() - start

        assertTrue("receiveFile phải thất bại do timeout", result.isFailure)
        assertTrue("Ngoại lệ phải là SocketTimeoutException", result.exceptionOrNull() is java.net.SocketTimeoutException)
        assertTrue("Thời gian trôi qua phải xấp xỉ 300ms (>=250ms)", elapsed >= 250)
    }

    @Test
    fun testBufferPoolNoLeakOnReadException() = runBlocking {
        val pool = com.teamshare.app.core.transfer.buffer.BufferPoolManager(bufferSize = 1024, maxBuffers = 4)
        val initialPermits = pool.availablePermits()
        assertEquals(4, initialPermits)

        // Tạo file và khóa tệp hoặc đóng file descriptor giữa chừng
        val testFile = createDummyFile(sizeBytes = 2048, name = "permits_test.dat")
        val senderEngine = LanTransferEngine(bufferPool = pool)

        // Thử gửi tới cổng không tồn tại -> fail ngay ở connect
        senderEngine.sendFile(
            host = "127.0.0.1",
            port = 49999,
            file = testFile,
            docId = "doc-leak-test",
            teamId = "team-leak",
            memberId = "mem-leak",
            deviceFingerprint = "fp-leak",
            role = "MEMBER",
            credentialData = "dummy"
        )

        // Số permits phải giữ nguyên 4
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
}

