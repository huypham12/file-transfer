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
    fun testGenerateOwnerMasterKeyAndSignVerify() {
        val teamId = "team_security_999"
        val masterKey = keyManager.generateOwnerMasterKey(teamId)
        val alias = IKeyManager.MASTER_KEY_PREFIX + teamId

        assertNotNull(masterKey)
        assertTrue(keyManager.hasKey(alias))
        assertEquals(masterKey, keyManager.getPublicKey(alias))

        val dataToSign = "Lời mời tham gia nhóm nghiên cứu bảo mật".toByteArray()
        val signature = keyManager.signData(alias, dataToSign)

        assertNotNull(signature)
        assertTrue(signature.isNotEmpty())

        // Xác minh chữ ký đúng
        val isVerified = keyManager.verifySignature(masterKey, dataToSign, signature)
        assertTrue("Chữ ký hợp lệ phải được xác thực thành công", isVerified)

        // Xác minh chữ ký khi dữ liệu bị giả mạo
        val tamperedData = "Lời mời tham gia nhóm đã bị kẻ xấu can thiệp".toByteArray()
        val isTamperedVerified = keyManager.verifySignature(masterKey, tamperedData, signature)
        assertFalse("Chữ ký trên dữ liệu giả mạo phải bị từ chối", isTamperedVerified)
    }

    @Test
    fun testGenerateMemberIdentityKey() {
        val memberPubKey = keyManager.generateMemberIdentityKey()
        assertNotNull(memberPubKey)
        assertTrue(keyManager.hasKey(IKeyManager.MEMBER_IDENTITY_KEY_ALIAS))

        val credentialPayload = "member_id_001;device_fp_abc".toByteArray()
        val signature = keyManager.signData(IKeyManager.MEMBER_IDENTITY_KEY_ALIAS, credentialPayload)

        assertTrue(keyManager.verifySignature(memberPubKey, credentialPayload, signature))
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
        val teamId = "team_to_disband"
        val alias = IKeyManager.MASTER_KEY_PREFIX + teamId

        keyManager.generateOwnerMasterKey(teamId)
        assertTrue(keyManager.hasKey(alias))

        val deleted = keyManager.deleteKey(alias)
        assertTrue(deleted)
        assertFalse(keyManager.hasKey(alias))
        assertNull(keyManager.getPublicKey(alias))
    }
}

