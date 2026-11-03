package com.teamshare.app.core.security.crypto

import org.junit.Assert.*
import org.junit.Test
import javax.crypto.AEADBadTagException

class CryptoEngineTest {

    @Test
    fun testHkdfRfc5869TestCase1() {
        // Vector kiểm thử chính thức từ RFC 5869 - Test Case 1
        val ikm = hexToBytes("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b")
        val salt = hexToBytes("000102030405060708090a0b0c")
        val info = hexToBytes("f0f1f2f3f4f5f6f7f8f9")
        val l = 42

        val expectedPrkHex = "077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5"
        val expectedOkmHex = "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"

        val prk = CryptoEngine.hkdfExtract(salt, ikm)
        assertEquals(expectedPrkHex, bytesToHex(prk))

        val okm = CryptoEngine.hkdfExpand(prk, info, l)
        assertEquals(expectedOkmHex, bytesToHex(okm))

        // Kiểm tra hàm tổng hợp computeHkdf
        val directOkm = CryptoEngine.computeHkdf(ikm, salt, info, l)
        assertEquals(expectedOkmHex, bytesToHex(directOkm))
    }

    @Test
    fun testHkdfWithEmptySalt() {
        val ikm = "TeamShareSecretInputKeyMaterial1234".toByteArray()
        val info = "teamshare-test-session".toByteArray()

        val okm1 = CryptoEngine.computeHkdf(ikm, null, info, 32)
        val okm2 = CryptoEngine.computeHkdf(ikm, ByteArray(0), info, 32)

        assertEquals(32, okm1.size)
        assertArrayEquals(okm1, okm2)
    }

    @Test
    fun testAesGcmEncryptionDecryptionRoundtrip() {
        val key = CryptoEngine.generateRandomBytes(32)
        val iv = CryptoEngine.generateIv()
        val plaintext = "Tài liệu bảo mật cao cấp của TeamShare: Báo cáo đồ án 2026.".toByteArray()
        val aad = "team_id_123_metadata".toByteArray()

        // 1. Mã hóa
        val ciphertext = CryptoEngine.encryptAesGcm(plaintext, key, iv, aad)
        assertNotEquals(0, ciphertext.size)
        // Chiều dài bản mã = Chiều dài plaintext + 16 bytes Auth Tag
        assertEquals(plaintext.size + 16, ciphertext.size)

        // 2. Giải mã đúng
        val decrypted = CryptoEngine.decryptAesGcm(ciphertext, key, iv, aad)
        assertArrayEquals(plaintext, decrypted)
    }

    @Test(expected = AEADBadTagException::class)
    fun testAesGcmTamperedCiphertextThrowsException() {
        val key = CryptoEngine.generateRandomBytes(32)
        val iv = CryptoEngine.generateIv()
        val plaintext = "Dữ liệu thử nghiệm tính toàn vẹn".toByteArray()

        val ciphertext = CryptoEngine.encryptAesGcm(plaintext, key, iv)
        // Cố tình sửa đổi 1 byte trong bản mã
        ciphertext[0] = (ciphertext[0].toInt() xor 0xFF).toByte()

        CryptoEngine.decryptAesGcm(ciphertext, key, iv)
    }

    @Test(expected = AEADBadTagException::class)
    fun testAesGcmTamperedAadThrowsException() {
        val key = CryptoEngine.generateRandomBytes(32)
        val iv = CryptoEngine.generateIv()
        val plaintext = "Dữ liệu kiểm tra AAD".toByteArray()
        val aad = "valid_aad".toByteArray()

        val ciphertext = CryptoEngine.encryptAesGcm(plaintext, key, iv, aad)
        val tamperedAad = "invalid_aad".toByteArray()

        CryptoEngine.decryptAesGcm(ciphertext, key, iv, tamperedAad)
    }

    @Test
    fun testEcdhKeyAgreementSymmetry() {
        // 1. Máy A sinh cặp khóa
        val keyPairA = CryptoEngine.generateEcdhKeyPair()
        // 2. Máy B sinh cặp khóa
        val keyPairB = CryptoEngine.generateEcdhKeyPair()

        // 3. Tính bí mật dùng chung từ hai đầu
        val secretA = CryptoEngine.computeSharedSecret(keyPairA.private, keyPairB.public)
        val secretB = CryptoEngine.computeSharedSecret(keyPairB.private, keyPairA.public)

        assertNotNull(secretA)
        assertNotNull(secretB)
        assertEquals(secretA.size, secretB.size)
        assertArrayEquals(secretA, secretB)
    }

    private fun hexToBytes(hex: String): ByteArray {
        val result = ByteArray(hex.length / 2)
        for (i in result.indices) {
            val index = i * 2
            result[i] = hex.substring(index, index + 2).toInt(16).toByte()
        }
        return result
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder()
        for (b in bytes) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }
}
