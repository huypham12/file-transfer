package com.teamshare.app.e2e

import com.teamshare.app.core.protocol.BinaryFrameParser
import com.teamshare.app.core.protocol.ChunkFrame
import com.teamshare.app.core.protocol.FrameSerializer
import com.teamshare.app.core.security.crypto.E2EEncryptionManager
import com.teamshare.app.core.security.crypto.Sha256StreamCalculator
import com.teamshare.app.core.transfer.buffer.BufferPoolManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Stage 8 E2E Test: Kiểm thử Tích hợp Toàn diện Luồng Chuyển tiếp E2EE qua Go Relay Server.
 * Kiểm chứng:
 * 1. Ghép nối WebSocket WSS 2 chiều qua cặp ChannelBridge trên bộ nhớ RAM.
 * 2. Trao đổi khóa ECDH Ephemeral (secp256r1) và dẫn xuất HKDF-SHA256 (RFC 5869) ra khóa phiên K_session.
 * 3. Chuỗi xác minh trực quan SAS 6 chữ số giữa 2 bên.
 * 4. Mã hóa từng chunk 64KB bằng AES-256-GCM với IV tăng dần và AEAD Auth Tag.
 * 5. Nguyên lý Zero-Knowledge: Relay Server chỉ trung chuyển dòng byte mã hóa, không lưu đĩa, không có khóa giải mã.
 * 6. Phát hiện và từ chối ngay lập tức khi bản tin mã hóa bị can thiệp (Tampered Ciphertext).
 */
class RelayTransferE2ETest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var mockWebServer: MockWebServer
    private val capturedFramesOnRelay = CopyOnWriteArrayList<ByteArray>()
    private val client = OkHttpClient.Builder()
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    @Before
    fun setUp() {
        capturedFramesOnRelay.clear()
        mockWebServer = MockWebServer()
        setupMockRelayServer()
        mockWebServer.start()
    }

    @After
    fun tearDown() {
        try {
            mockWebServer.shutdown()
        } catch (_: Exception) {}
    }

    /**
     * Giả lập Go Relay Server chuyển tiếp nhị phân phi trạng thái trên RAM (Zero-Knowledge).
     */
    private fun setupMockRelayServer() {
        class ChannelBridge {
            var senderSocket: WebSocket? = null
            var receiverSocket: WebSocket? = null
        }

        val bridges = ConcurrentHashMap<String, ChannelBridge>()

        mockWebServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val listener = object : WebSocketListener() {
                    private var currentSocket: WebSocket? = null
                    private var assignedStreamId: String? = null
                    private var role: String? = null

                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        currentSocket = webSocket
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        // Protocol handshake: {"action":"ATTACH_STREAM", "streamId":"xyz", "role":"SENDER|RECEIVER"}
                        if (text.contains("ATTACH_STREAM")) {
                            val streamId = if (text.contains("stream_e2e_001")) "stream_e2e_001" else "default_stream"
                            assignedStreamId = streamId
                            role = if (text.contains("SENDER")) "SENDER" else "RECEIVER"

                            val bridge = bridges.computeIfAbsent(streamId) { ChannelBridge() }
                            if (role == "SENDER") {
                                bridge.senderSocket = webSocket
                            } else {
                                bridge.receiverSocket = webSocket
                            }
                            webSocket.send("{\"status\": 200, \"message\": \"ATTACHED_OK\"}")
                        }
                    }

                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        val rawData = bytes.toByteArray()
                        capturedFramesOnRelay.add(rawData)

                        // Relay Server định tuyến dòng byte: nếu nhận từ Sender thì forward sang Receiver
                        assignedStreamId?.let { streamId ->
                            val bridge = bridges[streamId]
                            if (role == "SENDER") {
                                bridge?.receiverSocket?.send(bytes)
                            } else {
                                bridge?.senderSocket?.send(bytes)
                            }
                        }
                    }
                }
                return MockResponse().withWebSocketUpgrade(listener)
            }
        }
    }

    private fun createDummyFile(sizeBytes: Int, name: String = "relay_payload.bin"): File {
        val file = tempFolder.newFile(name)
        FileOutputStream(file).use { fos ->
            val buf = ByteArray(1024) { (it * 7 % 256).toByte() }
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
    fun testEndToEndRelayTransfer_E2EEncryption(): Unit = runBlocking {
        val sourceFile = createDummyFile(sizeBytes = 128 * 1024, name = "top_secret_e2e.doc")
        val targetFile = tempFolder.newFile("received_e2e.doc")
        val sourceSha256 = Sha256StreamCalculator.calculateFileSha256(sourceFile)

        val streamId = "stream_e2e_001"
        val wsUrl = mockWebServer.url("/v1/stream").toString()

        // 1. Khởi tạo E2EEncryptionManager cho Sender (Alice) và Receiver (Bob)
        val aliceE2E = E2EEncryptionManager()
        val bobE2E = E2EEncryptionManager()

        val aliceKeyPayload = aliceE2E.initSession(streamId)
        val bobKeyPayload = bobE2E.initSession(streamId)

        // 2. Trao đổi khóa Ephemeral ECDH secp256r1 và sinh mã SAS 6 chữ số
        val aliceSas = aliceE2E.completeKeyExchange(bobKeyPayload)
        val bobSas = bobE2E.completeKeyExchange(aliceKeyPayload)
        assertEquals("Mã SAS đối chiếu giữa 2 thiết bị phải trùng khớp 100%", aliceSas, bobSas)
        assertEquals(6, aliceSas.length)
        assertTrue(aliceE2E.isEstablished())
        assertTrue(bobE2E.isEstablished())

        // 3. Kết nối WebSocket vào Relay Server
        val senderAttached = CompletableDeferred<Unit>()
        val receiverAttached = CompletableDeferred<Unit>()
        val receiverDone = CompletableDeferred<Unit>()

        val serializer = FrameSerializer()
        val parser = BinaryFrameParser()

        // --- Receiver WebSocket ---
        val receiverReq = Request.Builder().url(wsUrl).build()
        val receivedPlaintextChunks = CopyOnWriteArrayList<ByteArray>()
        var receiverChunkCounter = 0L

        val receiverWs = client.newWebSocket(receiverReq, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("{\"action\":\"ATTACH_STREAM\", \"streamId\":\"$streamId\", \"role\":\"RECEIVER\"}")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.contains("ATTACHED_OK")) {
                    receiverAttached.complete(Unit)
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val frame = parser.parseNextFrame(ByteArrayInputStream(bytes.toByteArray()))
                if (frame != null && frame.msgType == ChunkFrame.TYPE_DATA_CHUNK) {
                    // Receiver giải mã từng chunk AES-256-GCM qua E2EEncryptionManager
                    val decrypted = bobE2E.decryptChunk(chunkIndex = receiverChunkCounter++, encryptedPacket = frame.payload)
                    receivedPlaintextChunks.add(decrypted)
                } else if (frame != null && frame.msgType == ChunkFrame.TYPE_CONTROL_SIGNAL) {
                    receiverDone.complete(Unit)
                }
            }
        })

        receiverAttached.await()

        // --- Sender WebSocket ---
        val senderReq = Request.Builder().url(wsUrl).build()
        val senderWs = client.newWebSocket(senderReq, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("{\"action\":\"ATTACH_STREAM\", \"streamId\":\"$streamId\", \"role\":\"SENDER\"}")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.contains("ATTACHED_OK")) {
                    senderAttached.complete(Unit)
                }
            }
        })

        senderAttached.await()

        // 4. Sender đọc tệp nguồn, mã hóa bằng AES-256-GCM và gửi qua Relay
        val bufferPool = BufferPoolManager(bufferSize = 64 * 1024, maxBuffers = 4)
        var senderChunkCounter = 0L

        val senderJob = async(Dispatchers.IO) {
            FileInputStream(sourceFile).use { fis ->
                var bytesRead: Int
                val readBuf = bufferPool.acquire()
                try {
                    while (fis.read(readBuf).also { bytesRead = it } != -1) {
                        val chunkPlaintext = readBuf.copyOf(bytesRead)
                        val encryptedPacket = aliceE2E.encryptChunk(chunkIndex = senderChunkCounter++, plaintext = chunkPlaintext)

                        val chunkFrame = ChunkFrame(
                            msgType = ChunkFrame.TYPE_DATA_CHUNK,
                            streamId = streamId.hashCode(),
                            payload = encryptedPacket
                        )
                        val frameBytes = serializer.serialize(chunkFrame)
                        senderWs.send(frameBytes.toByteString())
                    }
                } finally {
                    bufferPool.release(readBuf)
                }
            }

            // Gửi khung tin điều khiển báo kết thúc (TYPE_CONTROL_SIGNAL)
            val endFrame = ChunkFrame(
                msgType = ChunkFrame.TYPE_CONTROL_SIGNAL,
                streamId = streamId.hashCode(),
                payload = "DONE".toByteArray(Charsets.UTF_8)
            )
            senderWs.send(serializer.serialize(endFrame).toByteString())
        }

        senderJob.await()
        receiverDone.await()

        // 5. Ghi các chunk đã giải mã vào file đích và thẩm định mã băm SHA-256
        FileOutputStream(targetFile).use { fos ->
            for (chunk in receivedPlaintextChunks) {
                fos.write(chunk)
            }
        }

        assertEquals(sourceFile.length(), targetFile.length())
        val targetSha256 = Sha256StreamCalculator.calculateFileSha256(targetFile)
        assertEquals("Mã SHA-256 sau khi giải mã E2EE phải trùng khớp 100% với tệp gốc", sourceSha256, targetSha256)

        // 6. Thẩm định thuộc tính Zero-Knowledge trên Go Relay Server
        assertTrue("Relay Server phải bắt được các khung tin trung chuyển", capturedFramesOnRelay.isNotEmpty())
        for (capturedBytes in capturedFramesOnRelay) {
            val frame = parser.parseNextFrame(ByteArrayInputStream(capturedBytes))
            if (frame != null && frame.msgType == ChunkFrame.TYPE_DATA_CHUNK) {
                // Thử giải mã bằng một instance E2E mới chưa trao đổi khóa: Relay tuyệt đối không thể đọc plaintext
                val eavesdropper = E2EEncryptionManager()
                eavesdropper.initSession("unauthorized_stream")
                assertThrows("Relay không có khóa phiên nên không thể giải mã nội dung", Exception::class.java) {
                    eavesdropper.decryptChunk(0L, frame.payload)
                }
            }
        }

        senderWs.close(1000, "Done")
        receiverWs.close(1000, "Done")
    }

    @Test
    fun testRelayTransfer_TamperedCiphertext_RejectsImmediately() {
        val aliceE2E = E2EEncryptionManager()
        val bobE2E = E2EEncryptionManager()

        val alicePayload = aliceE2E.initSession("stream_tamper_test")
        val bobPayload = bobE2E.initSession("stream_tamper_test")

        aliceE2E.completeKeyExchange(bobPayload)
        bobE2E.completeKeyExchange(alicePayload)

        val plaintext = "Dữ liệu tuyệt mật của Học viện Kỹ thuật Mật mã".toByteArray(Charsets.UTF_8)
        val encryptedPacket = aliceE2E.encryptChunk(chunkIndex = 0L, plaintext = plaintext)

        // Kẻ tấn công trên đường truyền sửa đổi 1 bit trong ciphertext
        val tamperedCiphertext = encryptedPacket.copyOf()
        tamperedCiphertext[tamperedCiphertext.size - 5] = (tamperedCiphertext[tamperedCiphertext.size - 5].toInt() xor 0x01).toByte()

        // Thẩm định: Thuật toán giải mã AES-256-GCM phải ném ngoại lệ AEAD Tag Mismatch
        assertThrows(Exception::class.java) {
            bobE2E.decryptChunk(chunkIndex = 0L, encryptedPacket = tamperedCiphertext)
        }
    }
}
