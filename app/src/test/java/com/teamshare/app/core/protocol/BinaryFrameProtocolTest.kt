package com.teamshare.app.core.protocol

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Kiểm thử toàn diện Giao thức Khung tin Nhị phân 11-byte Header
 * và 8 loại gói tin nghiệp vụ theo chuẩn SDD Chương 6.2.
 */
class BinaryFrameProtocolTest {

    private lateinit var serializer: FrameSerializer
    private lateinit var parser: BinaryFrameParser

    @Before
    fun setUp() {
        serializer = FrameSerializer()
        parser = BinaryFrameParser()
    }

    // -------------------------------------------------------------------------------------
    // 0x01: CONNECT_REQUEST (SDD Mục 6.2)
    // -------------------------------------------------------------------------------------
    @Test
    fun testConnectRequestPayload() {
        val payload = ConnectRequestPayload(
            sessionToken = "a1b2c3d4e5f6789012345678abcdef01",
            deviceModel = "Xiaomi Redmi Turbo 4 Pro",
            deviceManufacturer = "Xiaomi",
            deviceFingerprint = "fingerprint-hex-64-bytes-sample-for-unit-test-verification",
            clientNonce = "nonce-client-987654321"
        )
        val frame = serializer.buildConnectRequestFrame(streamId = 1, payload = payload)
        val serializedBytes = serializer.serialize(frame)

        val parsedFrame = parser.parseNextFrame(ByteArrayInputStream(serializedBytes))
        assertNotNull(parsedFrame)
        assertEquals(ChunkFrame.TYPE_CONNECT_REQUEST, parsedFrame!!.msgType)
        assertEquals(1, parsedFrame.streamId)

        val deserialized = ConnectRequestPayload.fromBytes(parsedFrame.payload)
        assertEquals(payload.sessionToken, deserialized.sessionToken)
        assertEquals(payload.deviceModel, deserialized.deviceModel)
        assertEquals(payload.deviceManufacturer, deserialized.deviceManufacturer)
        assertEquals(payload.deviceFingerprint, deserialized.deviceFingerprint)
        assertEquals(payload.clientNonce, deserialized.clientNonce)
    }

    // -------------------------------------------------------------------------------------
    // 0x02: CONNECT_ACCEPTED (SDD Mục 6.2)
    // -------------------------------------------------------------------------------------
    @Test
    fun testConnectAcceptedPayloadApproved() {
        val manifestItem = ManifestItemPayload(
            relativePath = "tai_lieu_mat.pdf",
            fileSize = 10485760L,
            mimeType = "application/pdf",
            sha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        )
        val payload = ConnectAcceptedPayload(
            status = "APPROVED",
            rejectionReason = null,
            transferType = "SINGLE_FILE",
            manifest = listOf(manifestItem)
        )
        val frame = serializer.buildConnectAcceptedFrame(streamId = 2, payload = payload)
        val serializedBytes = serializer.serialize(frame)

        val parsedFrame = parser.parseNextFrame(ByteArrayInputStream(serializedBytes))
        assertNotNull(parsedFrame)
        assertEquals(ChunkFrame.TYPE_CONNECT_ACCEPTED, parsedFrame!!.msgType)

        val deserialized = ConnectAcceptedPayload.fromBytes(parsedFrame.payload)
        assertTrue(deserialized.isApproved)
        assertEquals("APPROVED", deserialized.status)
        assertNull(deserialized.rejectionReason)
        assertEquals("SINGLE_FILE", deserialized.transferType)
        assertEquals(1, deserialized.manifest.size)
        assertEquals("tai_lieu_mat.pdf", deserialized.manifest[0].relativePath)
        assertEquals(10485760L, deserialized.manifest[0].fileSize)
        assertEquals("application/pdf", deserialized.manifest[0].mimeType)
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", deserialized.manifest[0].sha256)
    }

    @Test
    fun testConnectAcceptedPayloadRejected() {
        val payload = ConnectAcceptedPayload(
            status = "REJECTED",
            rejectionReason = "0x101: Session token không hợp lệ",
            transferType = "SINGLE_FILE",
            manifest = emptyList()
        )
        val frame = serializer.buildConnectAcceptedFrame(streamId = 3, payload = payload)
        val serializedBytes = serializer.serialize(frame)

        val parsedFrame = parser.parseNextFrame(ByteArrayInputStream(serializedBytes))
        assertNotNull(parsedFrame)
        assertEquals(ChunkFrame.TYPE_CONNECT_ACCEPTED, parsedFrame!!.msgType)

        val deserialized = ConnectAcceptedPayload.fromBytes(parsedFrame.payload)
        assertFalse(deserialized.isApproved)
        assertEquals("REJECTED", deserialized.status)
        assertEquals("0x101: Session token không hợp lệ", deserialized.rejectionReason)
        assertTrue(deserialized.manifest.isEmpty())
    }

    // -------------------------------------------------------------------------------------
    // 0x03: REQUEST_STREAM (SDD Mục 6.2)
    // -------------------------------------------------------------------------------------
    @Test
    fun testRequestStreamPayload() {
        val payload = RequestStreamPayload(
            sessionId = "session-test-uuid-1234",
            startOffset = 2097152L
        )
        val frame = serializer.buildRequestStreamFrame(streamId = 4, payload = payload)
        val serializedBytes = serializer.serialize(frame)

        val parsedFrame = parser.parseNextFrame(ByteArrayInputStream(serializedBytes))
        assertNotNull(parsedFrame)
        assertEquals(ChunkFrame.TYPE_REQUEST_STREAM, parsedFrame!!.msgType)

        val deserialized = RequestStreamPayload.fromBytes(parsedFrame.payload)
        assertEquals("session-test-uuid-1234", deserialized.sessionId)
        assertEquals(2097152L, deserialized.startOffset)
    }

    // -------------------------------------------------------------------------------------
    // 0x04: DATA_CHUNK (SDD Mục 6.2)
    // -------------------------------------------------------------------------------------
    @Test
    fun testDataChunkPayload() {
        val dummyData = ByteArray(65536) { (it % 256).toByte() }
        val payload = DataChunkPayload(
            chunkSeq = 42,
            chunkOffset = 2752512L,
            chunkData = dummyData
        )
        val frame = serializer.buildDataChunkFrame(streamId = 5, payload = payload)
        val serializedBytes = serializer.serialize(frame)

        val parsedFrame = parser.parseNextFrame(ByteArrayInputStream(serializedBytes))
        assertNotNull(parsedFrame)
        assertEquals(ChunkFrame.TYPE_DATA_CHUNK, parsedFrame!!.msgType)

        val deserialized = DataChunkPayload.fromBytes(parsedFrame.payload)
        assertEquals(42, deserialized.chunkSeq)
        assertEquals(2752512L, deserialized.chunkOffset)
        assertArrayEquals(dummyData, deserialized.chunkData)
    }

    // -------------------------------------------------------------------------------------
    // 0x05: CHUNK_ACK (SDD Mục 6.2)
    // -------------------------------------------------------------------------------------
    @Test
    fun testChunkAckPayload() {
        val payload = ChunkAckPayload(confirmedOffset = 4194304L)
        val frame = serializer.buildChunkAckFrame(streamId = 6, payload = payload)
        val serializedBytes = serializer.serialize(frame)

        val parsedFrame = parser.parseNextFrame(ByteArrayInputStream(serializedBytes))
        assertNotNull(parsedFrame)
        assertEquals(ChunkFrame.TYPE_CHUNK_ACK, parsedFrame!!.msgType)

        val deserialized = ChunkAckPayload.fromBytes(parsedFrame.payload)
        assertEquals(4194304L, deserialized.confirmedOffset)
    }

    // -------------------------------------------------------------------------------------
    // 0x06: CONTROL_SIGNAL (SDD Mục 6.2)
    // -------------------------------------------------------------------------------------
    @Test
    fun testControlSignalPayload() {
        val payload = ControlSignalPayload(command = "PAUSE", reasonCode = 1)
        val frame = serializer.buildControlSignalFrame(streamId = 7, payload = payload)
        val serializedBytes = serializer.serialize(frame)

        val parsedFrame = parser.parseNextFrame(ByteArrayInputStream(serializedBytes))
        assertNotNull(parsedFrame)
        assertEquals(ChunkFrame.TYPE_CONTROL_SIGNAL, parsedFrame!!.msgType)

        val deserialized = ControlSignalPayload.fromBytes(parsedFrame.payload)
        assertEquals("PAUSE", deserialized.command)
        assertEquals(1, deserialized.reasonCode)
    }

    // -------------------------------------------------------------------------------------
    // 0x07: VERIFY_RESULT (SDD Mục 6.2)
    // -------------------------------------------------------------------------------------
    @Test
    fun testVerifyResultPayload() {
        val payload = VerifyResultPayload(
            sessionId = "session-verify-001",
            status = "SUCCESS",
            actualSha256 = "abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890"
        )
        val frame = serializer.buildVerifyResultFrame(streamId = 8, payload = payload)
        val serializedBytes = serializer.serialize(frame)

        val parsedFrame = parser.parseNextFrame(ByteArrayInputStream(serializedBytes))
        assertNotNull(parsedFrame)
        assertEquals(ChunkFrame.TYPE_VERIFY_RESULT, parsedFrame!!.msgType)

        val deserialized = VerifyResultPayload.fromBytes(parsedFrame.payload)
        assertEquals("session-verify-001", deserialized.sessionId)
        assertTrue(deserialized.isSuccess)
        assertEquals("abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890", deserialized.actualSha256)
    }

    // -------------------------------------------------------------------------------------
    // 0x08: ERROR_ALERT (SDD Mục 6.2)
    // -------------------------------------------------------------------------------------
    @Test
    fun testErrorAlertPayload() {
        val payload = ErrorAlertPayload(
            errorCode = "0x302",
            errorMessage = "ERR_INSUFFICIENT_STORAGE: Thiết bị không đủ bộ nhớ trống"
        )
        val frame = serializer.buildErrorAlertFrame(streamId = 9, payload = payload)
        val serializedBytes = serializer.serialize(frame)

        val parsedFrame = parser.parseNextFrame(ByteArrayInputStream(serializedBytes))
        assertNotNull(parsedFrame)
        assertEquals(ChunkFrame.TYPE_ERROR_ALERT, parsedFrame!!.msgType)

        val deserialized = ErrorAlertPayload.fromBytes(parsedFrame.payload)
        assertEquals("0x302", deserialized.errorCode)
        assertEquals("ERR_INSUFFICIENT_STORAGE: Thiết bị không đủ bộ nhớ trống", deserialized.errorMessage)
    }

    // -------------------------------------------------------------------------------------
    // 0x09: CONNECT_PENDING
    // -------------------------------------------------------------------------------------
    @Test
    fun testConnectPendingPayload() {
        val payload = ConnectPendingPayload(
            timeoutSeconds = 60,
            message = "Đang chờ người gửi phê duyệt kết nối..."
        )
        val frame = serializer.buildConnectPendingFrame(streamId = 10, payload = payload)
        val serializedBytes = serializer.serialize(frame)

        val parsedFrame = parser.parseNextFrame(ByteArrayInputStream(serializedBytes))
        assertNotNull(parsedFrame)
        assertEquals(ChunkFrame.TYPE_CONNECT_PENDING, parsedFrame!!.msgType)

        val deserialized = ConnectPendingPayload.fromBytes(parsedFrame.payload)
        assertEquals(60, deserialized.timeoutSeconds)
        assertEquals("Đang chờ người gửi phê duyệt kết nối...", deserialized.message)
    }

    // -------------------------------------------------------------------------------------
    // Backward Compatibility Tests
    // -------------------------------------------------------------------------------------
    @Test
    fun testLegacyHandshakeAuthPayload() {
        val payload = HandshakeAuthPayload(
            teamId = "team-alpha-001",
            memberId = "mem-alice-99",
            deviceFingerprint = "fingerprint-xyz",
            role = "OWNER",
            credentialData = "raw-cert-pem-base64",
            clientNonce = "nonce-123456"
        )
        val frame = serializer.buildHandshakeAuthFrame(streamId = 100, payload = payload)
        val serializedBytes = serializer.serialize(frame)

        val parsedFrame = parser.parseNextFrame(ByteArrayInputStream(serializedBytes))
        assertNotNull(parsedFrame)

        val deserializedPayload = HandshakeAuthPayload.fromBytes(parsedFrame!!.payload)
        assertEquals(payload.teamId, deserializedPayload.teamId)
        assertEquals(payload.memberId, deserializedPayload.memberId)
        assertEquals(payload.deviceFingerprint, deserializedPayload.deviceFingerprint)
        assertEquals(payload.role, deserializedPayload.role)
    }

    @Test
    fun testHandshakeAuthPayloadWithDeviceManufacturerAndModel() {
        val payload = HandshakeAuthPayload(
            teamId = "team-alpha-001",
            memberId = "mem-alice-99",
            deviceFingerprint = "fingerprint-xyz",
            role = "MEMBER",
            credentialData = "raw-cert-pem-base64",
            clientNonce = "nonce-123456",
            deviceManufacturer = "Xiaomi",
            deviceModel = "Redmi Turbo 4 Pro"
        )
        val frame = serializer.buildHandshakeAuthFrame(streamId = 101, payload = payload)
        val serializedBytes = serializer.serialize(frame)

        val parsedFrame = parser.parseNextFrame(ByteArrayInputStream(serializedBytes))
        assertNotNull(parsedFrame)

        val deserializedPayload = HandshakeAuthPayload.fromBytes(parsedFrame!!.payload)
        assertEquals("Xiaomi", deserializedPayload.deviceManufacturer)
        assertEquals("Redmi Turbo 4 Pro", deserializedPayload.deviceModel)
    }

    @Test
    fun testMultipleSequentialFramesInSingleStream() {
        val baos = ByteArrayOutputStream()
        val frame1 = ChunkFrame(ChunkFrame.TYPE_CHUNK_ACK, 1, ChunkAckPayload(1024L).toBytes())
        val frame2 = ChunkFrame(ChunkFrame.TYPE_CHUNK_ACK, 1, ChunkAckPayload(2048L).toBytes())
        val frame3 = ChunkFrame(ChunkFrame.TYPE_CHUNK_ACK, 1, ChunkAckPayload(3072L).toBytes())

        serializer.serializeTo(frame1, baos)
        serializer.serializeTo(frame2, baos)
        serializer.serializeTo(frame3, baos)

        val bais = ByteArrayInputStream(baos.toByteArray())
        val res1 = parser.parseNextFrame(bais)
        val res2 = parser.parseNextFrame(bais)
        val res3 = parser.parseNextFrame(bais)
        val res4 = parser.parseNextFrame(bais)

        assertNotNull(res1)
        assertNotNull(res2)
        assertNotNull(res3)
        assertNull(res4) // EOF sạch

        assertEquals(1024L, ChunkAckPayload.fromBytes(res1!!.payload).confirmedOffset)
        assertEquals(2048L, ChunkAckPayload.fromBytes(res2!!.payload).confirmedOffset)
        assertEquals(3072L, ChunkAckPayload.fromBytes(res3!!.payload).confirmedOffset)
    }

    // -------------------------------------------------------------------------------------
    // Exception & Boundary Tests
    // -------------------------------------------------------------------------------------
    @Test(expected = FrameCorruptedException::class)
    fun testInvalidMagicBytesThrowsException() {
        val badHeader = byteArrayOf(
            0x58, 0x59, // Sai magic "XY"
            0x01,
            0, 0, 0, 1,
            0, 0, 0, 0
        )
        parser.parseNextFrame(ByteArrayInputStream(badHeader))
    }

    @Test(expected = FrameCorruptedException::class)
    fun testInvalidMsgTypeThrowsException() {
        val badTypeHeader = byteArrayOf(
            0x53, 0x46,
            0x0A, // Invalid msgType 0x0A (cho phép 0x01..0x09)
            0, 0, 0, 1,
            0, 0, 0, 0
        )
        parser.parseNextFrame(ByteArrayInputStream(badTypeHeader))
    }

    @Test(expected = FrameCorruptedException::class)
    fun testNegativePayloadLengthThrowsException() {
        val badLenHeader = byteArrayOf(
            0x53, 0x46,
            0x01,
            0, 0, 0, 1,
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte() // -1
        )
        parser.parseNextFrame(ByteArrayInputStream(badLenHeader))
    }

    @Test(expected = FrameCorruptedException::class)
    fun testPayloadExceedsMaxLimitThrowsException() {
        val oversizedLenHeader = byteArrayOf(
            0x53, 0x46,
            0x01,
            0, 0, 0, 1,
            0x00, 0x20, 0x00, 0x01 // 2MB + 1 byte
        )
        parser.parseNextFrame(ByteArrayInputStream(oversizedLenHeader))
    }

    @Test(expected = FrameCorruptedException::class)
    fun testStreamTruncatedInHeaderThrowsException() {
        val partialHeader = byteArrayOf(0x53, 0x46, 0x01, 0x00) // Chỉ 4 bytes thay vì 11
        parser.parseNextFrame(ByteArrayInputStream(partialHeader))
    }

    @Test(expected = FrameCorruptedException::class)
    fun testStreamTruncatedInPayloadThrowsException() {
        val frame = ChunkFrame(ChunkFrame.TYPE_DATA_CHUNK, 1, byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10))
        val fullBytes = serializer.serialize(frame)
        val truncatedBytes = fullBytes.copyOfRange(0, fullBytes.size - 5)

        parser.parseNextFrame(ByteArrayInputStream(truncatedBytes))
    }

    @Test
    fun testFragmentedNetworkStreamParsing() {
        val frame = ChunkFrame(ChunkFrame.TYPE_DATA_CHUNK, 42, "Fragmented stream test data!".toByteArray())
        val fullBytes = serializer.serialize(frame)

        val oneByteAtATimeStream = object : InputStream() {
            private var idx = 0
            override fun read(): Int {
                return if (idx < fullBytes.size) {
                    fullBytes[idx++].toInt() and 0xFF
                } else {
                    -1
                }
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (idx >= fullBytes.size) return -1
                val byte = read()
                if (byte == -1) return -1
                b[off] = byte.toByte()
                return 1
            }
        }

        val parsedFrame = parser.parseNextFrame(oneByteAtATimeStream)
        assertNotNull(parsedFrame)
        assertEquals(frame.msgType, parsedFrame!!.msgType)
        assertEquals(frame.streamId, parsedFrame.streamId)
        assertArrayEquals(frame.payload, parsedFrame.payload)
    }
}
