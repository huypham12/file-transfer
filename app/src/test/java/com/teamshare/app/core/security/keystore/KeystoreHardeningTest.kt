package com.teamshare.app.core.security.keystore

import com.teamshare.app.domain.usecase.security.InitializeKeystoreUseCase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.security.interfaces.ECPublicKey

class KeystoreHardeningTest {

    private lateinit var keyManager: SoftwareKeyManager

    @Before
    fun setUp() {
        keyManager = SoftwareKeyManager()
    }

    @Test
    fun testDeviceIdentityKeyGenerationAndParameters() {
        val publicKey = keyManager.generateDeviceKeyPair()

        assertNotNull(publicKey)
        assertEquals("EC", publicKey.algorithm)
        assertTrue(keyManager.hasKey(IKeyManager.DEVICE_IDENTITY_KEY_ALIAS))
        assertEquals(publicKey, keyManager.getPublicKey(IKeyManager.DEVICE_IDENTITY_KEY_ALIAS))

        if (publicKey is ECPublicKey) {
            val bitLength = publicKey.params.order.bitLength()
            assertEquals("Độ dài khóa NIST P-256 (secp256r1) phải là 256 bits", 256, bitLength)
        }
    }

    @Test
    fun testSignAndVerifySessionToken() {
        val publicKey = keyManager.generateDeviceKeyPair()
        val sessionToken = "session_token_sec_999_xyz"

        // 1. Ký số token
        val signatureBase64 = keyManager.signSessionToken(sessionToken)
        assertNotNull(signatureBase64)
        assertTrue(signatureBase64.isNotBlank())

        // 2. Thẩm định chữ ký hợp lệ
        val isVerified = keyManager.verifySessionToken(sessionToken, signatureBase64, publicKey)
        assertTrue("Chữ ký token phiên hợp lệ phải được xác thực thành công", isVerified)

        // 3. Chống giả mạo: Token bị sửa đổi
        val isTamperedVerified = keyManager.verifySessionToken("attacker_forged_token", signatureBase64, publicKey)
        assertFalse("Token bị sửa đổi phải bị từ chối xác thực", isTamperedVerified)
    }

    @Test
    fun testInitializeKeystoreUseCaseLifecycle() = runBlocking {
        val useCase = InitializeKeystoreUseCase(keyManager)

        assertFalse(keyManager.hasKey(IKeyManager.DEVICE_IDENTITY_KEY_ALIAS))

        // 1. Khởi tạo lần đầu khi chưa có khóa
        val res1 = useCase.execute()
        assertTrue(res1.isSuccess)
        val initialKey = res1.getOrThrow()
        assertTrue(keyManager.hasKey(IKeyManager.DEVICE_IDENTITY_KEY_ALIAS))

        // 2. Khởi tạo lần sau: phải tái sử dụng khóa cũ đã có
        val res2 = useCase.execute()
        assertTrue(res2.isSuccess)
        val reusedKey = res2.getOrThrow()
        assertEquals(initialKey, reusedKey)
    }
}

