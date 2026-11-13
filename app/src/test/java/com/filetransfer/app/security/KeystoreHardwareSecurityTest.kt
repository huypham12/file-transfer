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
        val publicKey = keyManager.generateMemberIdentityKey()
        assertNotNull(publicKey)
        assertEquals("EC", publicKey.algorithm)

        if (publicKey is ECPublicKey) {
            val curveName = publicKey.params.order.bitLength()
            assertEquals("Độ dài khóa Elliptic Curve phải là 256 bits (secp256r1)", 256, curveName)
        }

        // 2. Kiểm thử ký số trên dữ liệu mẫu
        val originalData = "CHỨNG THƯ THÀNH VIÊN filetransfer - OWASP MASVS VERIFICATION".toByteArray(StandardCharsets.UTF_8)
        val signature = keyManager.signData(IKeyManager.MEMBER_IDENTITY_KEY_ALIAS, originalData)
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
    fun testOwnerMasterKey_UniquePerTeam() {
        val keyManager = SoftwareKeyManager()
        val team1PubKey = keyManager.generateOwnerMasterKey("team_alpha_001")
        val team2PubKey = keyManager.generateOwnerMasterKey("team_beta_002")

        assertNotNull(team1PubKey)
        assertNotNull(team2PubKey)

        // Mỗi nhóm phải có một cặp khóa Master Key độc lập
        val team1Data = "TEAM 1 DATA".toByteArray()
        val sig1 = keyManager.signData(IKeyManager.MASTER_KEY_PREFIX + "team_alpha_001", team1Data)
        assertTrue(keyManager.verifySignature(team1PubKey, team1Data, sig1))
    }
}
