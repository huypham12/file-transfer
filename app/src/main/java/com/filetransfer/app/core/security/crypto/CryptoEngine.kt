package com.filetransfer.app.core.security.crypto

import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.ceil

/**
 * Động cơ mật mã học toán học cốt lõi (Core Cryptographic Engine).
 * Cung cấp:
 * 1. Dẫn xuất khóa HKDF-SHA256 chuẩn hóa theo RFC 5869.
 * 2. Trao đổi khóa Diffie-Hellman trên đường cong Elliptic (ECDH secp256r1).
 * 3. Mã hóa và giải mã đối xứng khối AES-256-GCM với vector khởi tạo IV ngẫu nhiên và thẻ xác thực (Auth Tag).
 */
object CryptoEngine {

    const val AES_KEY_SIZE_BYTES: Int = 32 // 256 bits
    const val GCM_IV_LENGTH_BYTES: Int = 12 // 96 bits chuẩn khuyến nghị NIST
    const val GCM_TAG_LENGTH_BITS: Int = 128 // 16 bytes auth tag

    private const val HMAC_ALGORITHM = "HmacSHA256"
    private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val EC_CURVE = "secp256r1"

    private val secureRandom: SecureRandom by lazy { SecureRandom() }

    /**
     * Sinh vector khởi tạo IV ngẫu nhiên 12 bytes chuẩn NIST cho AES-GCM.
     */
    fun generateIv(): ByteArray {
        return generateRandomBytes(GCM_IV_LENGTH_BYTES)
    }

    /**
     * Sinh mảng byte ngẫu nhiên an toàn mật mã.
     */
    fun generateRandomBytes(length: Int): ByteArray {
        val bytes = ByteArray(length)
        secureRandom.nextBytes(bytes)
        return bytes
    }

    /**
     * Sinh cặp khóa tạm thời (ephemeral) trên đường cong Elliptic secp256r1 (NIST P-256).
     */
    fun generateEcdhKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec(EC_CURVE), secureRandom)
        return kpg.generateKeyPair()
    }

    /**
     * Mã hóa khóa công khai EC thành mảng byte định dạng chuẩn X.509 (SubjectPublicKeyInfo).
     */
    fun encodeEcPublicKey(publicKey: PublicKey): ByteArray {
        return publicKey.encoded
    }

    /**
     * Giải mã mảng byte X.509 thành đối tượng [PublicKey] trên đường cong Elliptic.
     */
    fun decodeEcPublicKey(encodedBytes: ByteArray): PublicKey {
        val keySpec = X509EncodedKeySpec(encodedBytes)
        val keyFactory = KeyFactory.getInstance("EC")
        return keyFactory.generatePublic(keySpec)
    }

    /**
     * Ký số dữ liệu bằng khóa riêng tư ECDSA (SHA256withECDSA).
     */
    fun signDataWithPrivateKey(privateKey: PrivateKey, data: ByteArray): ByteArray {
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(privateKey)
        sig.update(data)
        return sig.sign()
    }

    /**
     * Thẩm định tính hợp lệ của chữ ký số ECDSA (SHA256withECDSA).
     */
    fun verifySignature(publicKey: PublicKey, data: ByteArray, signature: ByteArray): Boolean {
        return try {
            val sig = Signature.getInstance("SHA256withECDSA")
            sig.initVerify(publicKey)
            sig.update(data)
            sig.verify(signature)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Tính toán bí mật dùng chung (Shared Secret) bằng thuật toán ECDH.
     */
    fun computeSharedSecret(privateKey: PrivateKey, peerPublicKey: PublicKey): ByteArray {
        val keyAgreement = KeyAgreement.getInstance("ECDH")
        keyAgreement.init(privateKey)
        keyAgreement.doPhase(peerPublicKey, true)
        return keyAgreement.generateSecret()
    }

    /**
     * Dẫn xuất khóa HKDF-SHA256 theo RFC 5869 (Extract-and-Expand).
     *
     * @param ikm Khóa đầu vào (Input Keying Material)
     * @param salt Muối ngẫu nhiên (tùy chọn, nếu null sẽ dùng chuỗi 32 byte 0x00)
     * @param info Ngữ cảnh ứng dụng (Context and Application specific information)
     * @param length Độ dài khóa đầu ra tính theo bytes (mặc định 32 bytes = 256 bits)
     */
    fun computeHkdf(
        ikm: ByteArray,
        salt: ByteArray? = null,
        info: ByteArray = ByteArray(0),
        length: Int = AES_KEY_SIZE_BYTES
    ): ByteArray {
        val prk = hkdfExtract(salt, ikm)
        return hkdfExpand(prk, info, length)
    }

    /**
     * Giai đoạn HKDF-Extract: PRK = HMAC-Hash(salt, IKM)
     */
    fun hkdfExtract(salt: ByteArray?, ikm: ByteArray): ByteArray {
        val actualSalt = if (salt == null || salt.isEmpty()) {
            ByteArray(32) // Chuỗi 32 bytes 0x00 theo mục 2.2 RFC 5869
        } else {
            salt
        }
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(actualSalt, HMAC_ALGORITHM))
        return mac.doFinal(ikm)
    }

    /**
     * Giai đoạn HKDF-Expand: OKM = T(1) || T(2) || ... || T(N)
     */
    fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val hashLen = 32 // SHA-256 đầu ra 32 bytes
        require(length <= 255 * hashLen) { "Độ dài yêu cầu ($length) vượt quá giới hạn RFC 5869" }

        val n = ceil(length.toDouble() / hashLen).toInt()
        val okm = ByteArray(length)
        var previousT = ByteArray(0)
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(prk, HMAC_ALGORITHM))

        var bytesWritten = 0
        for (i in 1..n) {
            mac.reset()
            mac.update(previousT)
            mac.update(info)
            mac.update(i.toByte())
            previousT = mac.doFinal()

            val toCopy = minOf(hashLen, length - bytesWritten)
            System.arraycopy(previousT, 0, okm, bytesWritten, toCopy)
            bytesWritten += toCopy
        }

        return okm
    }

    /**
     * Mã hóa dữ liệu bằng thuật toán AES-256-GCM.
     * Đầu ra bao gồm: Bản mã (Ciphertext) nối tiếp Thẻ xác thực (Auth Tag 128-bit).
     */
    fun encryptAesGcm(
        plaintext: ByteArray,
        keyBytes: ByteArray,
        iv: ByteArray,
        aad: ByteArray? = null
    ): ByteArray {
        require(keyBytes.size == AES_KEY_SIZE_BYTES) { "Khóa AES bắt buộc phải có độ dài 32 bytes (256 bits)" }
        require(iv.size == GCM_IV_LENGTH_BYTES) { "Vector khởi tạo IV bắt buộc phải có độ dài 12 bytes" }

        val secretKey = SecretKeySpec(keyBytes, "AES")
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmSpec)

        if (aad != null && aad.isNotEmpty()) {
            cipher.updateAAD(aad)
        }

        return cipher.doFinal(plaintext)
    }

    /**
     * Giải mã dữ liệu bản mã bằng thuật toán AES-256-GCM.
     * Ném ra [javax.crypto.AEADBadTagException] nếu thẻ xác thực hoặc dữ liệu bị can thiệp.
     */
    fun decryptAesGcm(
        ciphertextWithTag: ByteArray,
        keyBytes: ByteArray,
        iv: ByteArray,
        aad: ByteArray? = null
    ): ByteArray {
        require(keyBytes.size == AES_KEY_SIZE_BYTES) { "Khóa AES bắt buộc phải có độ dài 32 bytes (256 bits)" }
        require(iv.size == GCM_IV_LENGTH_BYTES) { "Vector khởi tạo IV bắt buộc phải có độ dài 12 bytes" }

        val secretKey = SecretKeySpec(keyBytes, "AES")
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)

        if (aad != null && aad.isNotEmpty()) {
            cipher.updateAAD(aad)
        }

        return cipher.doFinal(ciphertextWithTag)
    }
}

