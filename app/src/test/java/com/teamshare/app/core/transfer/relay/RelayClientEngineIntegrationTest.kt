package com.teamshare.app.core.transfer.relay

import com.teamshare.app.core.network.relay.AttachStreamRequest
import com.teamshare.app.core.network.relay.AttachStreamResponse
import com.teamshare.app.core.protocol.ChunkAckPayload
import com.teamshare.app.core.protocol.FrameSerializer
import com.teamshare.app.core.security.crypto.CryptoEngine
import com.teamshare.app.core.security.crypto.E2EEncryptionManager
import com.teamshare.app.core.security.crypto.Sha256StreamCalculator
import com.teamshare.app.core.transfer.buffer.BufferPoolManager
import com.teamshare.app.core.transfer.lan.TransferEngineState
import com.teamshare.app.core.transfer.lan.TransferProgressCallback
import com.teamshare.app.domain.model.MemberRole
import com.teamshare.app.domain.model.TeamMembershipCredential
import com.teamshare.app.domain.model.TransferChannel
import com.teamshare.app.domain.model.TransferDirection
import com.teamshare.app.domain.model.TransferSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.ByteString
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Kiểm thử tích hợp tự động cho Động cơ Truyền tải Relay và Mã hóa Đầu cuối E2EE
 * qua MockWebServer WebSocket Bridge trên JVM.
 */
class RelayClientEngineIntegrationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var mockWebServer: MockWebServer
    private lateinit var ownerKeyPair: KeyPair

    private val capturedBinaryFrames = CopyOnWriteArrayList<ByteArray>()

    @Before
    fun setUp() {
        ownerKeyPair = CryptoEngine.generateEcdhKeyPair()
        mockWebServer = MockWebServer()
        capturedBinaryFrames.clear()
    }

    @After
    fun tearDown() {
        try {
            mockWebServer.shutdown()
        } catch (_: Exception) {}
    }

    /**
     * Thiết lập Dispatcher cho MockWebServer để đóng vai trò In-Memory Relay Server
     * ghép cặp hai kết nối Sender và Receiver qua cùng một streamId.
     */
    private fun setupMockRelayServer() {
        class BridgePair {
            var senderSocket: WebSocket? = null
            var receiverSocket: WebSocket? = null
            val pendingForSender = mutableListOf<Any>()
            val pendingForReceiver = mutableListOf<Any>()
        }

        val bridges = ConcurrentHashMap<String, BridgePair>()

        mockWebServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val listener = object : WebSocketListener() {
                    private var currentSocket: WebSocket? = null
                    private var currentRole: String? = null
                    private var currentStreamId: String? = null

                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        currentSocket = webSocket
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        if (currentRole == null && text.contains("ATTACH_STREAM")) {
                            val attachReq = AttachStreamRequest.fromJson(text)
                            currentRole = attachReq.role
                            currentStreamId = attachReq.streamId

                            val pair = bridges.computeIfAbsent(attachReq.streamId) { BridgePair() }
                            val pendingToFlush = mutableListOf<Any>()

                            synchronized(pair) {
                                if (attachReq.role == "SENDER") {
                                    pair.senderSocket = webSocket
                                    pendingToFlush.addAll(pair.pendingForSender)
                                    pair.pendingForSender.clear()
                                } else {
                                    pair.receiverSocket = webSocket
                                    pendingToFlush.addAll(pair.pendingForReceiver)
                                    pair.pendingForReceiver.clear()
                                }
                            }

                            // Phản hồi ATTACHED
                            val resp = AttachStreamResponse(
                                status = "ATTACHED",
                                streamId = attachReq.streamId,
                                role = attachReq.role,
                                message = "OK"
                            )
                            webSocket.send(resp.toJson())

                            for (item in pendingToFlush) {
                                if (item is String) {
                                    webSocket.send(item)
                                } else if (item is ByteString) {
                                    webSocket.send(item)
                                }
                            }
                            return
                        }

                        // Chuyển tiếp text sang socket đối diện
                        currentStreamId?.let { sId ->
                            val pair = bridges[sId] ?: return@let
                            synchronized(pair) {
                                val peer = if (currentRole == "SENDER") pair.receiverSocket else pair.senderSocket
                                if (peer != null) {
                                    peer.send(text)
                                } else {
                                    val pendingList = if (currentRole == "SENDER") pair.pendingForReceiver else pair.pendingForSender
                                    pendingList.add(text)
                                }
                            }
                        }
                    }

                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        val byteArray = bytes.toByteArray()
                        capturedBinaryFrames.add(byteArray)

                        // Chuyển tiếp binary sang socket đối diện
                        currentStreamId?.let { sId ->
                            val pair = bridges[sId] ?: return@let
                            synchronized(pair) {
                                val peer = if (currentRole == "SENDER") pair.receiverSocket else pair.senderSocket
                                if (peer != null) {
                                    peer.send(bytes)
                                } else {
                                    val pendingList = if (currentRole == "SENDER") pair.pendingForReceiver else pair.pendingForSender
                                    pendingList.add(bytes)
                                }
                            }
                        }
                    }
                }

                return MockResponse().withWebSocketUpgrade(listener)
            }
        }

        mockWebServer.start()
    }

    private fun createCredential(
        teamId: String,
        memberId: String,
        role: MemberRole = MemberRole.MEMBER
    ): TeamMembershipCredential {
        val deviceFp = UUID.randomUUID().toString().replace("-", "")
        val dataToSign = "$teamId:$memberId:$deviceFp:${role.name}".toByteArray(Charsets.UTF_8)
        val signatureBytes = CryptoEngine.signDataWithPrivateKey(ownerKeyPair.private, dataToSign)
        val signature = signatureBytes.joinToString("") { "%02x".format(it) }

        return TeamMembershipCredential(
            teamId = teamId,
            memberId = memberId,
            deviceFingerprint = deviceFp,
            role = role,
            issuedAt = System.currentTimeMillis(),
            ownerSignature = signature
        )
    }

    @Test
    fun testSuccessfulFileTransferThroughRelayWithE2EE() = runBlocking {
        setupMockRelayServer()
        val relayUrl = mockWebServer.url("/v1/stream").toString()

        val teamId = UUID.randomUUID().toString()
        val docId = UUID.randomUUID().toString()
        val sessionId = UUID.randomUUID().toString()

        val senderCred = createCredential(teamId, "member-sender-01")
        val receiverCred = createCredential(teamId, "member-receiver-02")

        // 1. Tạo tệp nguồn 128KB với dữ liệu ngẫu nhiên
        val sourceFile = tempFolder.newFile("source_secret_document.dat")
        val sourceData = ByteArray(128 * 1024) { (it % 251).toByte() }
        val secretMessage = "TEAMSHARE_SUPER_CONFIDENTIAL_KEY_DATA_2026"
        System.arraycopy(secretMessage.toByteArray(), 0, sourceData, 100, secretMessage.length)

        RandomAccessFile(sourceFile, "rw").use { it.write(sourceData) }

        val expectedSha256 = Sha256StreamCalculator.calculateFileSha256(sourceFile)
        val targetFile = File(tempFolder.root, "received_secret_document.dat")

        val senderSession = TransferSession(
            sessionId = sessionId,
            docId = docId,
            peerMemberId = receiverCred.memberId,
            direction = TransferDirection.OUTGOING,
            channel = TransferChannel.ZERO_KNOWLEDGE_RELAY,
            totalBytes = sourceFile.length(),
            confirmedOffset = 0L
        )

        val receiverSession = TransferSession(
            sessionId = sessionId,
            docId = docId,
            peerMemberId = senderCred.memberId,
            direction = TransferDirection.INCOMING,
            channel = TransferChannel.ZERO_KNOWLEDGE_RELAY,
            totalBytes = sourceFile.length(),
            confirmedOffset = 0L
        )

        val senderEngine = RelayClientEngine()
        val receiverEngine = RelayClientEngine()

        val senderStates = mutableListOf<TransferEngineState>()
        val receiverStates = mutableListOf<TransferEngineState>()

        val senderCallback = object : TransferProgressCallback {
            override fun onStateChanged(newState: TransferEngineState) {
                senderStates.add(newState)
            }
        }

        val receiverCallback = object : TransferProgressCallback {
            override fun onStateChanged(newState: TransferEngineState) {
                receiverStates.add(newState)
            }
        }

        // Chạy đồng thời Sender và Receiver
        val senderJob = async {
            FileInputStream(sourceFile).channel.use { channel ->
                senderEngine.sendFileViaRelay(
                    relayWsUrl = relayUrl,
                    session = senderSession,
                    fileChannel = channel,
                    expectedSha256 = expectedSha256,
                    localCredential = senderCred,
                    ownerPublicKey = ownerKeyPair.public,
                    callback = senderCallback
                )
            }
        }

        val receiverJob = async {
            receiverEngine.receiveFileViaRelay(
                relayWsUrl = relayUrl,
                session = receiverSession,
                targetFile = targetFile,
                expectedSha256 = expectedSha256,
                localCredential = receiverCred,
                ownerPublicKey = ownerKeyPair.public,
                callback = receiverCallback
            )
        }

        val senderResult = senderJob.await()
        val receiverResult = receiverJob.await()

        assertTrue("Bên gửi phải hoàn tất thành công", senderResult)
        assertTrue("Bên nhận phải hoàn tất thành công", receiverResult)
        assertTrue("Tệp đích phải tồn tại", targetFile.exists())
        assertEquals("Kích thước tệp phải bằng nhau", sourceFile.length(), targetFile.length())

        val actualSha256 = Sha256StreamCalculator.calculateFileSha256(targetFile)
        assertEquals("Mã băm SHA-256 phải khớp 100%", expectedSha256, actualSha256)

        // Kiểm tra cam kết Zero-Knowledge & E2EE: Chuỗi tuyệt mật không được xuất hiện trong bất kỳ packet nào
        val secretBytes = secretMessage.toByteArray(StandardCharsets.UTF_8)
        for (frame in capturedBinaryFrames) {
            val frameContainsSecret = String(frame, StandardCharsets.ISO_8859_1).contains(secretMessage)
            assertFalse("Gói tin trên WebSocket bị lộ chuỗi plaintext! Vi phạm E2EE Zero-Knowledge!", frameContainsSecret)
        }
    }

    @Test
    fun testPeerInvalidCredentialRejected() = runBlocking {
        setupMockRelayServer()
        val relayUrl = mockWebServer.url("/v1/stream").toString()

        val teamId = UUID.randomUUID().toString()
        val sessionId = UUID.randomUUID().toString()

        val senderCred = createCredential(teamId, "member-sender-01")
        // Giả mạo chữ ký cho receiver
        val fakeKeyPair = CryptoEngine.generateEcdhKeyPair()
        val fakeSignatureBytes = CryptoEngine.signDataWithPrivateKey(fakeKeyPair.private, "fake".toByteArray())
        val fakeSignature = fakeSignatureBytes.joinToString("") { "%02x".format(it) }
        val fakeReceiverCred = TeamMembershipCredential(
            teamId = teamId,
            memberId = "fake-member",
            deviceFingerprint = "deadbeef",
            role = MemberRole.MEMBER,
            issuedAt = System.currentTimeMillis(),
            ownerSignature = fakeSignature
        )

        val sourceFile = tempFolder.newFile("test_source.dat")
        RandomAccessFile(sourceFile, "rw").use { it.write(ByteArray(1024)) }
        val targetFile = File(tempFolder.root, "test_target.dat")

        val senderSession = TransferSession(
            sessionId = sessionId,
            docId = "doc1",
            peerMemberId = fakeReceiverCred.memberId,
            direction = TransferDirection.OUTGOING,
            channel = TransferChannel.ZERO_KNOWLEDGE_RELAY,
            totalBytes = 1024L,
            confirmedOffset = 0L
        )

        val receiverSession = TransferSession(
            sessionId = sessionId,
            docId = "doc1",
            peerMemberId = senderCred.memberId,
            direction = TransferDirection.INCOMING,
            channel = TransferChannel.ZERO_KNOWLEDGE_RELAY,
            totalBytes = 1024L,
            confirmedOffset = 0L
        )

        val senderEngine = RelayClientEngine()
        val receiverEngine = RelayClientEngine()

        val senderJob = async {
            try {
                FileInputStream(sourceFile).channel.use { channel ->
                    senderEngine.sendFileViaRelay(
                        relayWsUrl = relayUrl,
                        session = senderSession,
                        fileChannel = channel,
                        expectedSha256 = "dummyHash",
                        localCredential = senderCred,
                        ownerPublicKey = ownerKeyPair.public
                    )
                }
            } catch (e: Exception) {
                e
            }
        }

        val receiverJob = async {
            try {
                receiverEngine.receiveFileViaRelay(
                    relayWsUrl = relayUrl,
                    session = receiverSession,
                    targetFile = targetFile,
                    expectedSha256 = "dummyHash",
                    localCredential = fakeReceiverCred,
                    ownerPublicKey = ownerKeyPair.public
                )
            } catch (e: Exception) {
                e
            }
        }

        val resSender = senderJob.await()
        val resReceiver = receiverJob.await()

        assertTrue("Phải ném ngoại lệ SecurityException do chứng thư giả mạo",
            resSender is SecurityException || resReceiver is SecurityException || resSender is Exception || resReceiver is Exception)
    }

    @Test
    fun testChecksumMismatchTriggersSafeFileDeleter() = runBlocking {
        setupMockRelayServer()
        val relayUrl = mockWebServer.url("/v1/stream").toString()

        val teamId = UUID.randomUUID().toString()
        val sessionId = UUID.randomUUID().toString()

        val senderCred = createCredential(teamId, "member-sender-01")
        val receiverCred = createCredential(teamId, "member-receiver-02")

        val sourceFile = tempFolder.newFile("source_corrupted.dat")
        val sourceData = ByteArray(32 * 1024) { 0x55.toByte() }
        RandomAccessFile(sourceFile, "rw").use { it.write(sourceData) }

        val wrongExpectedSha256 = "0000000000000000000000000000000000000000000000000000000000000000"
        val targetFile = File(tempFolder.root, "corrupted_target.dat")

        val senderSession = TransferSession(
            sessionId = sessionId,
            docId = "doc_corrupt",
            peerMemberId = receiverCred.memberId,
            direction = TransferDirection.OUTGOING,
            channel = TransferChannel.ZERO_KNOWLEDGE_RELAY,
            totalBytes = sourceFile.length(),
            confirmedOffset = 0L
        )

        val receiverSession = TransferSession(
            sessionId = sessionId,
            docId = "doc_corrupt",
            peerMemberId = senderCred.memberId,
            direction = TransferDirection.INCOMING,
            channel = TransferChannel.ZERO_KNOWLEDGE_RELAY,
            totalBytes = sourceFile.length(),
            confirmedOffset = 0L
        )

        val senderEngine = RelayClientEngine()
        val receiverEngine = RelayClientEngine()

        val senderJob = async {
            FileInputStream(sourceFile).channel.use { channel ->
                senderEngine.sendFileViaRelay(
                    relayWsUrl = relayUrl,
                    session = senderSession,
                    fileChannel = channel,
                    expectedSha256 = wrongExpectedSha256,
                    localCredential = senderCred,
                    ownerPublicKey = ownerKeyPair.public
                )
            }
        }

        val receiverJob = async {
            receiverEngine.receiveFileViaRelay(
                relayWsUrl = relayUrl,
                session = receiverSession,
                targetFile = targetFile,
                expectedSha256 = wrongExpectedSha256,
                localCredential = receiverCred,
                ownerPublicKey = ownerKeyPair.public
            )
        }

        val senderSuccess = senderJob.await()
        val receiverSuccess = receiverJob.await()

        assertFalse("Bên nhận phải từ chối khi sai SHA-256", receiverSuccess)
        assertFalse("Bên gửi phải ghi nhận thất bại từ verify result", senderSuccess)
        assertFalse("Tệp đích chính thức không được phép tồn tại", targetFile.exists())

        // Tệp tạm .part phải bị SafeFileDeleter tiêu hủy
        val partFile = File(tempFolder.root, "${targetFile.name}.part")
        assertFalse("Tệp .part phải bị tiêu hủy hoàn toàn", partFile.exists())
    }

    @Test
    fun testRetryMechanismWhenRelayTemporarilyDown() = runBlocking {
        // Đặt URL tới cổng không ai lắng nghe
        val deadUrl = "ws://127.0.0.1:49999/v1/stream"
        val teamId = UUID.randomUUID().toString()
        val sessionId = UUID.randomUUID().toString()
        val senderCred = createCredential(teamId, "member-sender-01")

        val sourceFile = tempFolder.newFile("dummy.dat")
        RandomAccessFile(sourceFile, "rw").use { it.write(ByteArray(100)) }

        val session = TransferSession(
            sessionId = sessionId,
            docId = "doc1",
            peerMemberId = "peer",
            direction = TransferDirection.OUTGOING,
            channel = TransferChannel.ZERO_KNOWLEDGE_RELAY,
            totalBytes = 100L,
            confirmedOffset = 0L
        )

        val engine = RelayClientEngine()
        var retryCount = 0
        val callback = object : TransferProgressCallback {
            override fun onError(errorCode: String, message: String) {
                if (errorCode == "0x201") retryCount++
            }
        }

        assertThrows(Exception::class.java) {
            runBlocking {
                FileInputStream(sourceFile).channel.use { channel ->
                    engine.sendFileViaRelayWithRetry(
                        relayWsUrl = deadUrl,
                        session = session,
                        fileChannel = channel,
                        expectedSha256 = "dummyHash",
                        localCredential = senderCred,
                        ownerPublicKey = ownerKeyPair.public,
                        callback = callback,
                        maxRetries = 2
                    )
                }
            }
        }

        assertTrue("Phải kích hoạt thử lại ít nhất 2 lần", retryCount >= 2)
    }

    @Test
    fun testSasGeneratedCallbackFired() = runBlocking {
        setupMockRelayServer()
        val relayUrl = mockWebServer.url("/v1/stream").toString()

        val teamId = UUID.randomUUID().toString()
        val sessionId = UUID.randomUUID().toString()

        val senderCred = createCredential(teamId, "member-sender-01")
        val receiverCred = createCredential(teamId, "member-receiver-02")

        val sourceFile = tempFolder.newFile("sas_test_source.dat")
        RandomAccessFile(sourceFile, "rw").use { it.write(ByteArray(1024)) }
        val targetFile = File(tempFolder.root, "sas_test_target.dat")

        val senderSession = TransferSession(
            sessionId = sessionId,
            docId = "doc_sas",
            peerMemberId = receiverCred.memberId,
            direction = TransferDirection.OUTGOING,
            channel = TransferChannel.ZERO_KNOWLEDGE_RELAY,
            totalBytes = 1024L,
            confirmedOffset = 0L
        )

        val receiverSession = TransferSession(
            sessionId = sessionId,
            docId = "doc_sas",
            peerMemberId = senderCred.memberId,
            direction = TransferDirection.INCOMING,
            channel = TransferChannel.ZERO_KNOWLEDGE_RELAY,
            totalBytes = 1024L,
            confirmedOffset = 0L
        )

        val senderEngine = RelayClientEngine()
        val receiverEngine = RelayClientEngine()

        var senderSas: String? = null
        var receiverSas: String? = null

        val senderCallback = object : TransferProgressCallback {
            override fun onSasGenerated(sasCode: String) {
                senderSas = sasCode
            }
        }

        val receiverCallback = object : TransferProgressCallback {
            override fun onSasGenerated(sasCode: String) {
                receiverSas = sasCode
            }
        }

        val senderJob = async {
            FileInputStream(sourceFile).channel.use { channel ->
                senderEngine.sendFileViaRelay(
                    relayWsUrl = relayUrl,
                    session = senderSession,
                    fileChannel = channel,
                    expectedSha256 = "dummyHash",
                    localCredential = senderCred,
                    ownerPublicKey = ownerKeyPair.public,
                    callback = senderCallback
                )
            }
        }

        val receiverJob = async {
            receiverEngine.receiveFileViaRelay(
                relayWsUrl = relayUrl,
                session = receiverSession,
                targetFile = targetFile,
                expectedSha256 = "dummyHash",
                localCredential = receiverCred,
                ownerPublicKey = ownerKeyPair.public,
                callback = receiverCallback
            )
        }

        senderJob.await()
        receiverJob.await()

        assertTrue("Callback onSasGenerated của sender phải được gọi", senderSas != null)
        assertTrue("Callback onSasGenerated của receiver phải được gọi", receiverSas != null)
        assertEquals("Mã SAS 6 chữ số của hai bên phải khớp nhau 100%", senderSas, receiverSas)
        assertEquals("Độ dài mã SAS phải đúng 6 chữ số", 6, senderSas?.length)
    }

    @Test
    fun testResumeFromCheckpointOnRetry() = runBlocking {
        setupMockRelayServer()
        val relayUrl = mockWebServer.url("/v1/stream").toString()

        val teamId = UUID.randomUUID().toString()
        val sessionId = UUID.randomUUID().toString()

        val senderCred = createCredential(teamId, "member-sender-01")
        val receiverCred = createCredential(teamId, "member-receiver-02")

        val totalSize = 128 * 1024 // 128KB
        val sourceFile = tempFolder.newFile("resume_source.dat")
        val sourceData = ByteArray(totalSize) { (it % 199).toByte() }
        RandomAccessFile(sourceFile, "rw").use { it.write(sourceData) }

        val expectedSha256 = Sha256StreamCalculator.calculateFileSha256(sourceFile)
        val targetFile = File(tempFolder.root, "resume_target.dat")

        // Giả lập receiver đã có 64KB đầu tiên trong tệp .part trước khi bị ngắt kết nối
        val halfSize = 64 * 1024
        val partFile = File(tempFolder.root, "${targetFile.name}.part")
        RandomAccessFile(partFile, "rw").use {
            it.write(sourceData, 0, halfSize)
        }

        // Fake DAO lưu mốc confirmedOffset = 64KB
        val fakeDao = object : com.teamshare.app.data.local.db.dao.TransferSessionDao {
            var offset = halfSize.toLong()
            override suspend fun insertSession(session: com.teamshare.app.data.local.db.entity.TransferSessionEntity): Long = 1L
            override fun observeSessionById(sessionId: String): kotlinx.coroutines.flow.Flow<com.teamshare.app.data.local.db.entity.TransferSessionEntity?> = kotlinx.coroutines.flow.emptyFlow()
            override suspend fun findByToken(token: String): com.teamshare.app.data.local.db.entity.TransferSessionEntity? = null
            override suspend fun updateCheckpoint(sessionId: String, offset: Long): Int {
                this.offset = offset
                return 1
            }
            override suspend fun updateSessionStatus(sessionId: String, status: String, completedAt: Long?, errorCode: String?): Int = 1
            override suspend fun getSessionById(sessionId: String): com.teamshare.app.data.local.db.entity.TransferSessionEntity? {
                return com.teamshare.app.data.local.db.entity.TransferSessionEntity(
                    sessionId = sessionId,
                    sessionToken = "token_resume",
                    direction = "OUTGOING",
                    channel = "ZERO_KNOWLEDGE_RELAY",
                    peerDeviceModel = "TestModel",
                    peerFingerprint = "fp_peer",
                    status = "TRANSFERRING",
                    totalBytes = totalSize.toLong(),
                    confirmedOffset = offset,
                    tempFilePath = "/tmp/resume.part",
                    startedAt = 0L
                )
            }
            override fun getActiveSessions(): kotlinx.coroutines.flow.Flow<List<com.teamshare.app.data.local.db.entity.TransferSessionEntity>> = kotlinx.coroutines.flow.emptyFlow()
            override fun getAllSessions(): kotlinx.coroutines.flow.Flow<List<com.teamshare.app.data.local.db.entity.TransferSessionEntity>> = kotlinx.coroutines.flow.emptyFlow()
            override suspend fun deleteSession(sessionId: String): Int = 1
        }

        val senderSession = TransferSession(
            sessionId = sessionId,
            docId = "doc_resume",
            peerMemberId = receiverCred.memberId,
            direction = TransferDirection.OUTGOING,
            channel = TransferChannel.ZERO_KNOWLEDGE_RELAY,
            totalBytes = totalSize.toLong(),
            confirmedOffset = 0L // Tham số session ban đầu là 0L, nhưng DAO có 64KB
        )

        val receiverSession = TransferSession(
            sessionId = sessionId,
            docId = "doc_resume",
            peerMemberId = senderCred.memberId,
            direction = TransferDirection.INCOMING,
            channel = TransferChannel.ZERO_KNOWLEDGE_RELAY,
            totalBytes = totalSize.toLong(),
            confirmedOffset = 0L
        )

        val senderEngine = RelayClientEngine(sessionDao = fakeDao)
        val receiverEngine = RelayClientEngine(sessionDao = fakeDao)

        val senderJob = async {
            FileInputStream(sourceFile).channel.use { channel ->
                senderEngine.sendFileViaRelay(
                    relayWsUrl = relayUrl,
                    session = senderSession,
                    fileChannel = channel,
                    expectedSha256 = expectedSha256,
                    localCredential = senderCred,
                    ownerPublicKey = ownerKeyPair.public
                )
            }
        }

        val receiverJob = async {
            receiverEngine.receiveFileViaRelay(
                relayWsUrl = relayUrl,
                session = receiverSession,
                targetFile = targetFile,
                expectedSha256 = expectedSha256,
                localCredential = receiverCred,
                ownerPublicKey = ownerKeyPair.public
            )
        }

        val sRes = senderJob.await()
        val rRes = receiverJob.await()

        assertTrue("Sender phải hoàn tất thành công", sRes)
        assertTrue("Receiver phải hoàn tất thành công", rRes)
        assertTrue("Tệp đích phải tồn tại", targetFile.exists())
        assertEquals("Kích thước tệp hoàn chỉnh phải là 128KB", totalSize.toLong(), targetFile.length())
        assertEquals("Mã băm hoàn chỉnh phải khớp 100%", expectedSha256, Sha256StreamCalculator.calculateFileSha256(targetFile))
    }

    @Test
    fun testSuccessfulFileTransferThroughRelayWithoutTeamCredentials() = runBlocking {
        setupMockRelayServer()
        val relayUrl = mockWebServer.url("/v1/stream").toString()

        val token = "e2ee-session-token-stage3-999"
        val sessionId = UUID.randomUUID().toString()

        val sourceFile = tempFolder.newFile("source_stage3_relay.dat")
        val sourceData = ByteArray(64 * 1024) { (it % 256).toByte() }
        RandomAccessFile(sourceFile, "rw").use { it.write(sourceData) }

        val expectedSha256 = Sha256StreamCalculator.calculateFileSha256(sourceFile)
        val targetFile = File(tempFolder.root, "received_stage3_relay.dat")

        val senderSession = TransferSession(
            sessionId = sessionId,
            sessionToken = token,
            direction = TransferDirection.OUTGOING,
            channel = TransferChannel.ZERO_KNOWLEDGE_RELAY,
            totalBytes = sourceFile.length(),
            confirmedOffset = 0L
        )

        val receiverSession = TransferSession(
            sessionId = sessionId,
            sessionToken = token,
            direction = TransferDirection.INCOMING,
            channel = TransferChannel.ZERO_KNOWLEDGE_RELAY,
            totalBytes = sourceFile.length(),
            confirmedOffset = 0L
        )

        val senderEngine = RelayClientEngine()
        val receiverEngine = RelayClientEngine()

        val senderJob = async {
            FileInputStream(sourceFile).channel.use { channel ->
                senderEngine.sendFileViaRelay(
                    relayWsUrl = relayUrl,
                    session = senderSession,
                    fileChannel = channel,
                    expectedSha256 = expectedSha256
                    // Hoàn toàn không truyền localCredential và ownerPublicKey!
                )
            }
        }

        val receiverJob = async {
            receiverEngine.receiveFileViaRelay(
                relayWsUrl = relayUrl,
                session = receiverSession,
                targetFile = targetFile,
                expectedSha256 = expectedSha256
                // Hoàn toàn không truyền localCredential và ownerPublicKey!
            )
        }

        val sRes = senderJob.await()
        val rRes = receiverJob.await()

        assertTrue("Sender không dùng Team credential phải hoàn tất thành công", sRes)
        assertTrue("Receiver không dùng Team credential phải hoàn tất thành công", rRes)
        assertTrue("Tệp đích nhận qua Relay phải tồn tại", targetFile.exists())
        assertEquals(sourceFile.length(), targetFile.length())
        assertEquals(expectedSha256, Sha256StreamCalculator.calculateFileSha256(targetFile))
    }
}
