package com.filetransfer.app.core.security.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.UUID
import javax.crypto.AEADBadTagException

/**
 * Kiểm thử đơn vị toàn diện cho phân hệ mã hóa đầu cuối E2EE (E2EEncryptionManagerTest).
 */
class E2EEncryptionManagerTest {

    @Test
    fun testPayloadSerializationAndDeserialization() {
        val streamId = UUID.randomUUID().toString()
        val keyPair = CryptoEngine.generateEcdhKeyPair()
        val pubKeyBytes = CryptoEngine.encodeEcPublicKey(keyPair.public)
        val nonce = CryptoEngine.generateRandomBytes(16)
        val timestamp = System.currentTimeMillis()

        val originalPayload = E2EKeyExchangePayload(streamId, pubKeyBytes, nonce, timestamp)
        val jsonStr = originalPayload.toJson()
        val deserializedPayload = E2EKeyExchangePayload.fromJson(jsonStr)

        assertEquals(originalPayload, deserializedPayload)

        val bytes = originalPayload.toByteArray()
        val fromBytesPayload = E2EKeyExchangePayload.fromByteArray(bytes)
        assertEquals(originalPayload, fromBytesPayload)
    }

    @Test
    fun testE2EKeyExchangeMutualEstablishmentAndSameSas() {
        val streamId = UUID.randomUUID().toString()

        val senderManager = E2EEncryptionManager()
        val receiverManager = E2EEncryptionManager()

        val senderPayload = senderManager.initSession(streamId)
        val receiverPayload = receiverManager.initSession(streamId)

        assertFalse(senderManager.isEstablished())
        assertFalse(receiverManager.isEstablished())

        // Hai bên trao đổi tải trọng khóa tạm thời cho nhau
        val sasSender = senderManager.completeKeyExchange(receiverPayload)
        val sasReceiver = receiverManager.completeKeyExchange(senderPayload)

        assertTrue(senderManager.isEstablished())
        assertTrue(receiverManager.isEstablished())

        // Cả 2 bên bắt buộc phải tính ra chuỗi SAS 6 chữ số trùng khớp 100%
        assertNotNull(sasSender)
        assertEquals(6, sasSender.length)
        assertEquals(sasSender, sasReceiver)
        assertEquals(sasSender, senderManager.getSasCode())
        assertEquals(sasReceiver, receiverManager.getSasCode())
    }

    @Test
    fun testEncryptAndDecryptChunkSuccess() {
        val streamId = UUID.randomUUID().toString()
        val senderManager = E2EEncryptionManager()
        val receiverManager = E2EEncryptionManager()

        val p1 = senderManager.initSession(streamId)
        val p2 = receiverManager.initSession(streamId)
        senderManager.completeKeyExchange(p2)
        receiverManager.completeKeyExchange(p1)

        val originalText = "filetransfer: Bảo mật chuyển tiếp Zero-Knowledge và E2EE P2P"
        val plaintext = originalText.toByteArray(StandardCharsets.UTF_8)
        val chunkIndex = 42L

        // Sender mã hóa khối
        val encryptedPacket = senderManager.encryptChunk(chunkIndex, plaintext)
        assertTrue("Packet mã hóa phải lớn hơn bản rõ do thêm IV (12B) và Tag (16B)",
            encryptedPacket.size >= plaintext.size + 28)

        // Receiver giải mã khối
        val decryptedBytes = receiverManager.decryptChunk(chunkIndex, encryptedPacket)
        assertArrayEquals(plaintext, decryptedBytes)
        assertEquals(originalText, String(decryptedBytes, StandardCharsets.UTF_8))
    }

    @Test
    fun testTamperedCiphertextThrowsException() {
        val streamId = UUID.randomUUID().toString()
        val senderManager = E2EEncryptionManager()
        val receiverManager = E2EEncryptionManager()

        val p1 = senderManager.initSession(streamId)
        val p2 = receiverManager.initSession(streamId)
        senderManager.completeKeyExchange(p2)
        receiverManager.completeKeyExchange(p1)

        val plaintext = "Dữ liệu tuyệt mật không được phép sửa đổi".toByteArray(StandardCharsets.UTF_8)
        val chunkIndex = 1L
        val encryptedPacket = senderManager.encryptChunk(chunkIndex, plaintext)

        // Sửa đổi 1 byte bất kỳ trong phần ciphertext (sau 12 bytes IV)
        encryptedPacket[15] = (encryptedPacket[15].toInt() xor 0xFF).toByte()

        assertThrows(AEADBadTagException::class.java) {
            receiverManager.decryptChunk(chunkIndex, encryptedPacket)
        }
    }

    @Test
    fun testReorderingAttackThrowsException() {
        val streamId = UUID.randomUUID().toString()
        val senderManager = E2EEncryptionManager()
        val receiverManager = E2EEncryptionManager()

        val p1 = senderManager.initSession(streamId)
        val p2 = receiverManager.initSession(streamId)
        senderManager.completeKeyExchange(p2)
        receiverManager.completeKeyExchange(p1)

        val plaintext = "Khối dữ liệu số 1".toByteArray(StandardCharsets.UTF_8)
        val chunkIndex = 1L
        val encryptedPacket = senderManager.encryptChunk(chunkIndex, plaintext)

        // Kẻ tấn công cố tình thay đổi thứ tự, giải mã với chunkIndex = 2L
        val wrongChunkIndex = 2L
        assertThrows(IllegalArgumentException::class.java) {
            receiverManager.decryptChunk(wrongChunkIndex, encryptedPacket)
        }
    }

    @Test
    fun testClearDestroysSession() {
        val streamId = UUID.randomUUID().toString()
        val manager = E2EEncryptionManager()
        val payload = manager.initSession(streamId)

        val other = E2EEncryptionManager()
        val otherPayload = other.initSession(streamId)
        manager.completeKeyExchange(otherPayload)
        assertTrue(manager.isEstablished())

        manager.clear()
        assertFalse(manager.isEstablished())

        assertThrows(IllegalStateException::class.java) {
            manager.encryptChunk(0L, "test".toByteArray())
        }
    }

    @Test
    fun testInvalidClientNonceLengthRejected() {
        val streamId = UUID.randomUUID().toString()
        val manager = E2EEncryptionManager()
        manager.initSession(streamId)

        val invalidPayload = E2EKeyExchangePayload(
            streamId = streamId,
            ephemeralPublicKeyBytes = CryptoEngine.encodeEcPublicKey(CryptoEngine.generateEcdhKeyPair().public),
            clientNonce = ByteArray(8), // Sai độ dài (8 bytes != 16 bytes)
            timestamp = System.currentTimeMillis()
        )

        val ex = assertThrows(IllegalArgumentException::class.java) {
            manager.completeKeyExchange(invalidPayload)
        }
        assertTrue("Thông báo lỗi phải đề cập đến độ dài nonce 16 bytes", ex.message?.contains("16 bytes") == true)
    }
}

