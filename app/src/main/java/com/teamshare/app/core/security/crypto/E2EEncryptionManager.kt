package com.teamshare.app.core.security.crypto

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.security.PublicKey
import java.util.Arrays

/**
 * Trình Quản lý Mã hóa Đầu cuối E2EE (End-to-End Encryption Manager).
 * Đảm bảo:
 * 1. Khởi tạo và quản lý cặp khóa tạm thời Ephemeral ECDH (NIST P-256) cho mỗi phiên truyền tệp.
 * 2. Đàm phán và dẫn xuất Khóa Mã Hóa Phiên 256-bit qua HKDF-SHA256 (RFC 5869) độc lập với Relay Server.
 * 3. Tính toán chuỗi xác thực ngắn SAS 6 chữ số chống tấn công Man-In-The-Middle (MITM).
 * 4. Mã hóa và giải mã từng khối dữ liệu (DATA_CHUNK) bằng AES-256-GCM với vector khởi tạo IV tăng dần duy nhất.
 * 5. Tự động xóa trắng khóa phiên trong RAM khi hoàn tất (Anti-Forensics).
 */
class E2EEncryptionManager {

    private var streamId: String? = null
    private var localKeyPair: KeyPair? = null
    private var localNonce: ByteArray? = null
    private var peerPublicKey: PublicKey? = null
    private var peerNonce: ByteArray? = null
    private var sessionKey: ByteArray? = null
    private var sasCode: String? = null

    /**
     * Khởi tạo phiên E2EE mới cho [streamId].
     * Tự động sinh cặp khóa ngẫu nhiên Ephemeral ECDH và nonce của client.
     */
    fun initSession(streamId: String): E2EKeyExchangePayload {
        clear()
        this.streamId = streamId
        val keyPair = CryptoEngine.generateEcdhKeyPair()
        this.localKeyPair = keyPair
        val nonce = CryptoEngine.generateRandomBytes(16)
        this.localNonce = nonce

        return E2EKeyExchangePayload(
            streamId = streamId,
            ephemeralPublicKeyBytes = CryptoEngine.encodeEcPublicKey(keyPair.public),
            clientNonce = nonce,
            timestamp = System.currentTimeMillis()
        )
    }

    /**
     * Hoàn tất quá trình đàm phán khóa phiên khi nhận tải trọng [peerPayload] từ đối tác.
     * Tính toán Shared Secret, dẫn xuất khóa AES-256-GCM và sinh mã SAS.
     */
    fun completeKeyExchange(peerPayload: E2EKeyExchangePayload): String {
        val currentStreamId = checkNotNull(this.streamId) { "Phiên E2EE chưa được khởi tạo qua initSession" }
        require(peerPayload.streamId == currentStreamId) {
            "StreamId không khớp (Hiện tại: $currentStreamId, Nhận được: ${peerPayload.streamId})"
        }
        require(peerPayload.clientNonce.size == 16) {
            "Client nonce của đối tác không hợp lệ: yêu cầu độ dài đúng 16 bytes (nhận được: ${peerPayload.clientNonce.size} bytes)"
        }
        val keyPair = checkNotNull(this.localKeyPair) { "Khóa riêng tư cục bộ chưa sẵn sàng" }
        val nonceA = checkNotNull(this.localNonce) { "Nonce cục bộ chưa sẵn sàng" }

        val peerPubKey = CryptoEngine.decodeEcPublicKey(peerPayload.ephemeralPublicKeyBytes)
        this.peerPublicKey = peerPubKey
        this.peerNonce = peerPayload.clientNonce

        // 1. Tính toán bí mật dùng chung ECDH
        val sharedSecret = CryptoEngine.computeSharedSecret(keyPair.private, peerPubKey)

        try {
            // 2. Dẫn xuất khóa phiên 256-bit bằng HKDF-SHA256
            val salt = currentStreamId.toByteArray(StandardCharsets.UTF_8)
            val info = "teamshare-e2ee-session".toByteArray(StandardCharsets.UTF_8)
            this.sessionKey = CryptoEngine.computeHkdf(
                ikm = sharedSecret,
                salt = salt,
                info = info,
                length = CryptoEngine.AES_KEY_SIZE_BYTES
            )

            // 3. Tính toán chuỗi xác thực ngắn SAS 6 chữ số
            val combinedNonce = ByteArray(16)
            for (i in 0 until 16) {
                combinedNonce[i] = (nonceA[i].toInt() xor peerPayload.clientNonce[i].toInt()).toByte()
            }
            val sas = SasVerificationHelper.generateSasCode(
                pubKeyA = CryptoEngine.encodeEcPublicKey(keyPair.public),
                pubKeyB = peerPayload.ephemeralPublicKeyBytes,
                nonce = combinedNonce
            )
            this.sasCode = sas
            return sas
        } finally {
            // Xóa sạch shared secret khỏi RAM ngay sau khi đã dẫn xuất xong khóa phiên
            Arrays.fill(sharedSecret, 0.toByte())
        }
    }

    /**
     * Kiểm tra trạng thái kênh E2EE đã thiết lập khóa phiên thành công hay chưa.
     */
    fun isEstablished(): Boolean {
        return sessionKey != null
    }

    /**
     * Lấy mã xác thực SAS 6 chữ số đã tính toán.
     */
    fun getSasCode(): String? {
        return sasCode
    }

    /**
     * Mã hóa khối dữ liệu (DATA_CHUNK) bằng AES-256-GCM.
     * Cấu trúc gói trả về: `[IV 12B] [Ciphertext + AuthTag 16B]`.
     *
     * @param chunkIndex Số thứ tự của chunk (dùng để sinh IV duy nhất và đưa vào AAD)
     * @param plaintext Dữ liệu thô cần mã hóa
     * @param offset Vị trí bắt đầu trong mảng [plaintext]
     * @param length Số byte cần mã hóa
     */
    fun encryptChunk(
        chunkIndex: Long,
        plaintext: ByteArray,
        offset: Int = 0,
        length: Int = plaintext.size
    ): ByteArray {
        val key = checkNotNull(sessionKey) { "Kênh E2EE chưa được thiết lập khóa phiên" }

        // Sinh IV 12 bytes: 4 bytes ngẫu nhiên + 8 bytes chunkIndex Big-Endian
        val iv = ByteArray(12)
        val randomPrefix = CryptoEngine.generateRandomBytes(4)
        System.arraycopy(randomPrefix, 0, iv, 0, 4)
        ByteBuffer.wrap(iv, 4, 8).putLong(chunkIndex)

        // Đưa chunkIndex vào AAD để chống hoán đổi thứ tự khối (Reordering Attack)
        val aad = ByteBuffer.allocate(8).putLong(chunkIndex).array()

        val actualPlaintext = if (offset == 0 && length == plaintext.size) {
            plaintext
        } else {
            val slice = ByteArray(length)
            System.arraycopy(plaintext, offset, slice, 0, length)
            slice
        }

        val ciphertextWithTag = CryptoEngine.encryptAesGcm(
            plaintext = actualPlaintext,
            keyBytes = key,
            iv = iv,
            aad = aad
        )

        // Ghép [IV 12B] + [Ciphertext + Tag]
        val result = ByteArray(iv.size + ciphertextWithTag.size)
        System.arraycopy(iv, 0, result, 0, iv.size)
        System.arraycopy(ciphertextWithTag, 0, result, iv.size, ciphertextWithTag.size)
        return result
    }

    /**
     * Giải mã khối dữ liệu (DATA_CHUNK) đã được mã hóa bằng [encryptChunk].
     *
     * @param chunkIndex Số thứ tự khối (kiểm tra xác thực AAD)
     * @param encryptedPacket Dữ liệu nhị phân chứa `[IV 12B] [Ciphertext + Tag]`
     * @return Dữ liệu bản rõ ban đầu
     * @throws javax.crypto.AEADBadTagException nếu ciphertext bị sửa đổi hoặc sai khóa
     */
    fun decryptChunk(chunkIndex: Long, encryptedPacket: ByteArray): ByteArray {
        val key = checkNotNull(sessionKey) { "Kênh E2EE chưa được thiết lập khóa phiên" }
        require(encryptedPacket.size >= 12 + 16) {
            "Gói tin mã hóa không hợp lệ (kích thước ${encryptedPacket.size} < 28 bytes)"
        }

        val iv = ByteArray(12)
        System.arraycopy(encryptedPacket, 0, iv, 0, 12)

        // Kiểm tra xem 8 bytes cuối của IV có khớp với chunkIndex mong đợi không
        val ivChunkIndex = ByteBuffer.wrap(iv, 4, 8).long
        require(ivChunkIndex == chunkIndex) {
            "Phát hiện tấn công hoán đổi khối (IV ChunkIndex: $ivChunkIndex != Kỳ vọng: $chunkIndex)"
        }

        val ciphertextWithTag = ByteArray(encryptedPacket.size - 12)
        System.arraycopy(encryptedPacket, 12, ciphertextWithTag, 0, ciphertextWithTag.size)

        val aad = ByteBuffer.allocate(8).putLong(chunkIndex).array()

        return CryptoEngine.decryptAesGcm(
            ciphertextWithTag = ciphertextWithTag,
            keyBytes = key,
            iv = iv,
            aad = aad
        )
    }

    /**
     * Tiêu hủy toàn bộ khóa phiên và thông số mật mã trong bộ nhớ RAM (Anti-Forensics).
     */
    fun clear() {
        sessionKey?.let { Arrays.fill(it, 0.toByte()) }
        sessionKey = null
        localKeyPair = null
        localNonce = null
        peerPublicKey = null
        peerNonce = null
        sasCode = null
        streamId = null
    }
}

