package com.filetransfer.app.core.security.keystore

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.ConcurrentHashMap

/**
 * Hiện thực hóa thuần phần mềm của [IKeyManager] trong bộ nhớ RAM.
 * Phục vụ chạy các bài Unit Tests độc lập trên JVM không có phần cứng Android Keystore.
 */
class SoftwareKeyManager : IKeyManager {

    private val keyStoreMap = ConcurrentHashMap<String, KeyPair>()
    private val secureRandom = SecureRandom()
    private var cachedDatabasePassphrase: ByteArray? = null

    override fun generateOwnerMasterKey(teamId: String): PublicKey {
        val alias = IKeyManager.MASTER_KEY_PREFIX + teamId
        val keyPair = generateEcKeyPair()
        keyStoreMap[alias] = keyPair
        return keyPair.public
    }

    override fun generateMemberIdentityKey(): PublicKey {
        val alias = IKeyManager.MEMBER_IDENTITY_KEY_ALIAS
        val keyPair = generateEcKeyPair()
        keyStoreMap[alias] = keyPair
        return keyPair.public
    }

    override fun generateDeviceKeyPair(): PublicKey {
        val alias = IKeyManager.DEVICE_IDENTITY_KEY_ALIAS
        val keyPair = generateEcKeyPair()
        keyStoreMap[alias] = keyPair
        return keyPair.public
    }

    override fun getPublicKey(keyAlias: String): PublicKey? {
        return keyStoreMap[keyAlias]?.public
    }

    override fun signData(keyAlias: String, data: ByteArray): ByteArray {
        val keyPair = keyStoreMap[keyAlias]
            ?: throw IllegalStateException("Không tìm thấy khóa riêng tư với alias: $keyAlias")
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(keyPair.private)
        signature.update(data)
        return signature.sign()
    }

    override fun verifySignature(publicKey: PublicKey, data: ByteArray, signature: ByteArray): Boolean {
        return try {
            val sig = Signature.getInstance("SHA256withECDSA")
            sig.initVerify(publicKey)
            sig.update(data)
            sig.verify(signature)
        } catch (e: Exception) {
            false
        }
    }

    override fun getOrCreateDatabasePassphrase(): ByteArray {
        synchronized(this) {
            if (cachedDatabasePassphrase == null) {
                val passphrase = ByteArray(32)
                secureRandom.nextBytes(passphrase)
                cachedDatabasePassphrase = passphrase
            }
            return cachedDatabasePassphrase!!.clone()
        }
    }

    override fun deleteKey(keyAlias: String): Boolean {
        return keyStoreMap.remove(keyAlias) != null
    }

    override fun hasKey(keyAlias: String): Boolean {
        return keyStoreMap.containsKey(keyAlias)
    }

    private fun generateEcKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"), secureRandom)
        return kpg.generateKeyPair()
    }
}

