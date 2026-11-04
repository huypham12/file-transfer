package com.teamshare.app.core.protocol

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

class BinaryFrameProtocolTest {

    private lateinit var serializer: FrameSerializer
    private lateinit var parser: BinaryFrameParser

    @Before
    fun setUp() {
        serializer = FrameSerializer()
        parser = BinaryFrameParser()
    }

    @Test
    fun testHandshakeAuthSerializationAndDeserialization() {
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
        assertEquals(ChunkFrame.TYPE_HANDSHAKE_AUTH, parsedFrame!!.msgType)
        assertEquals(100, parsedFrame.streamId)

        val deserializedPayload = HandshakeAuthPayload.fromBytes(parsedFrame.payload)
        assertEquals(payload.teamId, deserializedPayload.teamId)
        assertEquals(payload.memberId, deserializedPayload.memberId)
        assertEquals(payload.deviceFingerprint, deserializedPayload.deviceFingerprint)
        assertEquals(payload.role, deserializedPayload.role)
        assertEquals(payload.credentialData, deserializedPayload.credentialData)
        assertEquals(payload.clientNonce, deserializedPayload.clientNonce)
    }

    @Test
    fun testTransferManifestSerializationAndDeserialization() {
        val payload = TransferManifestPayload(
            docId = "doc-report-2026",
            fileName = "bao_cao_tai_chinh.pdf",
            fileSize = 10485760L,
            mimeType = "application/pdf",
            expectedSha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            chunkSize = 65536
        )
        val frame = serializer.buildTransferManifestFrame(streamId = 101, payload = payload)
        val serializedBytes = serializer.serialize(frame)

        val parsedFrame = parser.parseNextFrame(ByteArrayInputStream(serializedBytes))
        assertNotNull(parsedFrame)
        assertEquals(ChunkFrame.TYPE_TRANSFER_MANIFEST, parsedFrame!!.msgType)
        assertEquals(101, parsedFrame.streamId)

        val deserializedPayload = TransferManifestPayload.fromBytes(parsedFrame.payload)
        assertEquals(payload.docId, deserializedPayload.docId)
        assertEquals(payload.fileName, deserializedPayload.fileName)
        assertEquals(payload.fileSize, deserializedPayload.fileSize)
        assertEquals(payload.mimeType, deserializedPayload.mimeType)
        assertEquals(payload.expectedSha256, deserializedPayload.expectedSha256)
        assertEquals(payload.chunkSize, deserializedPayload.chunkSize)
    }

    @Test
    fun testManifestResponseAcceptedAndRejected() {
        // Case Accepted
        val acceptPayload = ManifestResponsePayload(accepted = true, startOffset = 2097152L)
        val acceptFrame = serializer.buildManifestResponseFrame(streamId = 102, payload = acceptPayload)
        val acceptBytes = serializer.serialize(acceptFrame)
        val parsedAccept = parser.parseNextFrame(ByteArrayInputStream(acceptBytes))!!
        val resultAccept = ManifestResponsePayload.fromBytes(parsedAccept.payload)
        assertTrue(resultAccept.accepted)
        assertEquals(2097152L, resultAccept.startOffset)
        assertNull(resultAccept.rejectionReason)

        // Case Rejected
        val rejectPayload = ManifestResponsePayload(
            accepted = false,
            startOffset = 0L,
            rejectionReason = "STORAGE_FULL"
        )
        val rejectFrame = serializer.buildManifestResponseFrame(streamId = 103, payload = rejectPayload)
        val rejectBytes = serializer.serialize(rejectFrame)
        val parsedReject = parser.parseNextFrame(ByteArrayInputStream(rejectBytes))!!
        val resultReject = ManifestResponsePayload.fromBytes(parsedReject.payload)
        assertFalse(resultReject.accepted)
        assertEquals(0L, resultReject.startOffset)
        assertEquals("STORAGE_FULL", resultReject.rejectionReason)
    }

    @Test
    fun testDataChunkBinaryPayload() {
        val dummyData = ByteArray(65536) { (it % 256).toByte() }
        val payload = DataChunkPayload(
            chunkSeq = 42,
            chunkOffset = 2752512L,
            chunkData = dummyData
        )
        val frame = serializer.buildDataChunkFrame(streamId = 104, payload = payload)
        val serializedBytes = serializer.serialize(frame)

        val parsedFrame = parser.parseNextFrame(ByteArrayInputStream(serializedBytes))
        assertNotNull(parsedFrame)
        assertEquals(ChunkFrame.TYPE_DATA_CHUNK, parsedFrame!!.msgType)
        assertEquals(104, parsedFrame.streamId)

        val deserializedPayload = DataChunkPayload.fromBytes(parsedFrame.payload)
        assertEquals(42, deserializedPayload.chunkSeq)
        assertEquals(2752512L, deserializedPayload.chunkOffset)
        assertArrayEquals(dummyData, deserializedPayload.chunkData)
    }

    @Test
    fun testChunkAckBinaryPayload() {
        val payload = ChunkAckPayload(confirmedOffset = 4194304L)
        val frame = serializer.buildChunkAckFrame(streamId = 105, payload = payload)
        val serializedBytes = serializer.serialize(frame)

        val parsedFrame = parser.parseNextFrame(ByteArrayInputStream(serializedBytes))
        assertNotNull(parsedFrame)
        assertEquals(ChunkFrame.TYPE_CHUNK_ACK, parsedFrame!!.msgType)

        val deserializedPayload = ChunkAckPayload.fromBytes(parsedFrame.payload)
        assertEquals(4194304L, deserializedPayload.confirmedOffset)
    }

    @Test
    fun testControlSignalPayload() {
        val payload = ControlSignalPayload(command = "PAUSE", reasonCode = 1)
        val frame = serializer.buildControlSignalFrame(streamId = 106, payload = payload)
        val serializedBytes = serializer.serialize(frame)

        val parsedFrame = parser.parseNextFrame(ByteArrayInputStream(serializedBytes))
        assertNotNull(parsedFrame)
        assertEquals(ChunkFrame.TYPE_CONTROL_SIGNAL, parsedFrame!!.msgType)

        val deserializedPayload = ControlSignalPayload.fromBytes(parsedFrame.payload)
        assertEquals("PAUSE", deserializedPayload.command)
        assertEquals(1, deserializedPayload.reasonCode)
    }

    @Test
    fun testRevokeSignalPayload() {
        val payload = RevokeSignalPayload(
            revokedMemberId = "member-attacker-666",
            ownerSignature = "owner-ecdsa-signature-hex"
        )
        val frame = serializer.buildRevokeSignalFrame(streamId = 107, payload = payload)
        val serializedBytes = serializer.serialize(frame)

        val parsedFrame = parser.parseNextFrame(ByteArrayInputStream(serializedBytes))
        assertNotNull(parsedFrame)
        assertEquals(ChunkFrame.TYPE_REVOKE_SIGNAL, parsedFrame!!.msgType)

        val deserializedPayload = RevokeSignalPayload.fromBytes(parsedFrame.payload)
        assertEquals("member-attacker-666", deserializedPayload.revokedMemberId)
        assertEquals("owner-ecdsa-signature-hex", deserializedPayload.ownerSignature)
    }

    @Test
    fun testVerifyResultPayload() {
        val payload = VerifyResultPayload(
            docId = "doc-important",
            status = "SUCCESS",
            actualSha256 = "abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890"
        )
        val frame = serializer.buildVerifyResultFrame(streamId = 108, payload = payload)
        val serializedBytes = serializer.serialize(frame)

        val parsedFrame = parser.parseNextFrame(ByteArrayInputStream(serializedBytes))
        assertNotNull(parsedFrame)
        assertEquals(ChunkFrame.TYPE_VERIFY_RESULT, parsedFrame!!.msgType)

        val deserializedPayload = VerifyResultPayload.fromBytes(parsedFrame.payload)
        assertEquals("doc-important", deserializedPayload.docId)
        assertEquals("SUCCESS", deserializedPayload.status)
        assertEquals("abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890", deserializedPayload.actualSha256)
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
        assertNull(res4) // EOF cleanly reached

        assertEquals(1024L, ChunkAckPayload.fromBytes(res1!!.payload).confirmedOffset)
        assertEquals(2048L, ChunkAckPayload.fromBytes(res2!!.payload).confirmedOffset)
        assertEquals(3072L, ChunkAckPayload.fromBytes(res3!!.payload).confirmedOffset)
    }

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
            0x99.toByte(), // Invalid msgType 0x99
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
        val truncatedBytes = fullBytes.copyOfRange(0, fullBytes.size - 5) // Mất 5 bytes cuối payload

        parser.parseNextFrame(ByteArrayInputStream(truncatedBytes))
    }

    @Test
    fun testFragmentedNetworkStreamParsing() {
        // Mô phỏng socket chỉ đọc được 1 byte mỗi lần
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
                return 1 // Chỉ trả về đúng 1 byte mỗi lần
            }
        }

        val parsedFrame = parser.parseNextFrame(oneByteAtATimeStream)
        assertNotNull(parsedFrame)
        assertEquals(frame.msgType, parsedFrame!!.msgType)
        assertEquals(frame.streamId, parsedFrame.streamId)
        assertArrayEquals(frame.payload, parsedFrame.payload)
    }
}

