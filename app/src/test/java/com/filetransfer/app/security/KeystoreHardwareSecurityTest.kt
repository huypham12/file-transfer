package com.filetransfer.app.security

import com.filetransfer.app.core.security.keystore.IKeyManager
import com.filetransfer.app.core.security.keystore.SoftwareKeyManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.security.interfaces.ECPublicKey

/**
 * Kiểm thử An toàn Mật mã và Tính toàn vẹn Khóa Keystore (NFR-SEC-01, MASVS-CRYPTO).
 * Thẩm định các thuộc tính toán học của cặp khóa ECDSA secp256r1,
 * tính bất biến của chữ ký số và khả năng phát hiện sửa đổi dữ liệu dù chỉ 1 bit.
 */
class KeystoreHardwareSecurityTest {

    @Test
    fun testEcdsaSecp256r1_KeyParameters_And_SignVerify() {
        val keyManager = SoftwareKeyManager()

        // 1. Kiểm tra cặp khóa được sinh
        val publicKey = keyManager.generateDeviceKeyPair()
        assertNotNull(publicKey)
        assertEquals("EC", publicKey.algorithm)

        if (publicKey is ECPublicKey) {
            val curveName = publicKey.params.order.bitLength()
            assertEquals("Độ dài khóa Elliptic Curve phải là 256 bits (secp256r1)", 256, curveName)
        }

        // 2. Kiểm thử ký số trên dữ liệu mẫu
        val originalData = "ĐỊNH DANH THIẾT BỊ filetransfer - OWASP MASVS VERIFICATION".toByteArray(StandardCharsets.UTF_8)
        val signature = keyManager.signData(IKeyManager.DEVICE_IDENTITY_KEY_ALIAS, originalData)
        assertNotNull(signature)
        assertTrue("Chữ ký ECDSA DER/P1363 phải có độ dài hợp lệ (> 64 bytes)", signature.size >= 64)

        // 3. Thẩm định chữ ký hợp lệ
        val isValid = keyManager.verifySignature(publicKey, originalData, signature)
        assertTrue("Chữ ký số tạo bởi Private Key phải xác thực thành công bằng Public Key", isValid)

        // 4. Kiểm thử chống giả mạo (Tamper Resistance): Sửa đổi 1 bit trong payload
        val tamperedData = originalData.copyOf()
        tamperedData[0] = (tamperedData[0].toInt() xor 0x01).toByte()

        val isTamperedValid = keyManager.verifySignature(publicKey, tamperedData, signature)
        assertFalse("Hệ thống bắt buộc phải phát hiện và từ chối chữ ký khi payload bị sửa đổi dù chỉ 1 bit", isTamperedValid)

        // 5. Kiểm thử chữ ký bị hỏng (Corrupted Signature)
        val corruptedSignature = signature.copyOf()
        corruptedSignature[corruptedSignature.size - 1] = (corruptedSignature[corruptedSignature.size - 1].toInt() xor 0xFF).toByte()

        val isCorruptedSigValid = keyManager.verifySignature(publicKey, originalData, corruptedSignature)
        assertFalse("Chữ ký bị sửa đổi bit bắt buộc phải bị từ chối", isCorruptedSigValid)
    }

    @Test
    fun testDeviceIdentityKey_SessionTokenSigning() {
        val keyManager1 = SoftwareKeyManager()
        val keyManager2 = SoftwareKeyManager()

        val pubKey1 = keyManager1.generateDeviceKeyPair()
        val pubKey2 = keyManager2.generateDeviceKeyPair()

        val token = "session_token_xyz_98765"
        val sig1 = keyManager1.signSessionToken(token)

        assertTrue(keyManager1.verifySessionToken(token, sig1, pubKey1))
        assertFalse("Khóa thiết bị khác không được xác thực thành công chữ ký của thiết bị 1", keyManager1.verifySessionToken(token, sig1, pubKey2))
    }
}
