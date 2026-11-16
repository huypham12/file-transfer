package com.filetransfer.app.core.security.keystore

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SoftwareKeyManagerTest {

    private lateinit var keyManager: SoftwareKeyManager

    @Before
    fun setUp() {
        keyManager = SoftwareKeyManager()
    }

    @Test
    fun testGenerateDeviceKeyPairAndSignVerify() {
        val devicePubKey = keyManager.generateDeviceKeyPair()
        val alias = IKeyManager.DEVICE_IDENTITY_KEY_ALIAS

        assertNotNull(devicePubKey)
        assertTrue(keyManager.hasKey(alias))
        assertEquals(devicePubKey, keyManager.getPublicKey(alias))

        val dataToSign = "Dữ liệu xác thực phiên truyền tải Secure Drop".toByteArray()
        val signature = keyManager.signData(alias, dataToSign)

        assertNotNull(signature)
        assertTrue(signature.isNotEmpty())

        // Xác minh chữ ký đúng
        val isVerified = keyManager.verifySignature(devicePubKey, dataToSign, signature)
        assertTrue("Chữ ký hợp lệ phải được xác thực thành công", isVerified)

        // Xác minh chữ ký khi dữ liệu bị giả mạo
        val tamperedData = "Dữ liệu phiên truyền tải đã bị kẻ xấu can thiệp".toByteArray()
        val isTamperedVerified = keyManager.verifySignature(devicePubKey, tamperedData, signature)
        assertFalse("Chữ ký trên dữ liệu giả mạo phải bị từ chối", isTamperedVerified)
    }

    @Test
    fun testSignAndVerifySessionToken() {
        val devicePubKey = keyManager.generateDeviceKeyPair()
        val sessionToken = "session_token_abc_12345"

        val signatureBase64 = keyManager.signSessionToken(sessionToken)
        assertNotNull(signatureBase64)
        assertTrue(signatureBase64.isNotBlank())

        val isValid = keyManager.verifySessionToken(sessionToken, signatureBase64, devicePubKey)
        assertTrue("Chữ ký số session token phải hợp lệ", isValid)

        val isTamperedValid = keyManager.verifySessionToken("wrong_token", signatureBase64, devicePubKey)
        assertFalse("Chữ ký với token sai phải bị từ chối", isTamperedValid)
    }

    @Test
    fun testDatabasePassphraseGenerationAndIdempotence() {
        val passphrase1 = keyManager.getOrCreateDatabasePassphrase()
        val passphrase2 = keyManager.getOrCreateDatabasePassphrase()

        assertNotNull(passphrase1)
        assertEquals(32, passphrase1.size) // 256-bit passphrase
        assertArrayEquals("Gọi nhiều lần phải trả về cùng một passphrase", passphrase1, passphrase2)
    }

    @Test
    fun testDeleteKey() {
        val alias = IKeyManager.DEVICE_IDENTITY_KEY_ALIAS

        keyManager.generateDeviceKeyPair()
        assertTrue(keyManager.hasKey(alias))

        val deleted = keyManager.deleteKey(alias)
        assertTrue(deleted)
        assertFalse(keyManager.hasKey(alias))
        assertNull(keyManager.getPublicKey(alias))
    }
}
