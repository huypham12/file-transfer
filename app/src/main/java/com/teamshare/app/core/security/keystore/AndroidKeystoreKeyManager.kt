package com.teamshare.app.core.security.keystore

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * Hiện thực hóa [IKeyManager] kết nối trực tiếp với phần cứng Android Keystore (TEE / StrongBox).
 * Tuân thủ đặc tả SDD mục 9.1 và NFR-SEC-01.
 */
class AndroidKeystoreKeyManager(
    private val context: Context
) : IKeyManager {

    companion object {
        private const val ANDROID_KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val EC_CURVE = "secp256r1"
        private const val PREFS_FILE = "teamshare_keystore_secure_prefs"
        private const val PREF_DB_PASSPHRASE_HEX = "encrypted_db_passphrase_hex"
    }

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE_PROVIDER).apply { load(null) }
    }

    private val secureRandom = SecureRandom()

    @Volatile
    private var cachedPassphrase: ByteArray? = null

    override fun generateOwnerMasterKey(teamId: String): PublicKey {
        val alias = IKeyManager.MASTER_KEY_PREFIX + teamId
        return generateHardwareBackedEcKeyPair(alias)
    }

    override fun generateMemberIdentityKey(): PublicKey {
        val alias = IKeyManager.MEMBER_IDENTITY_KEY_ALIAS
        return generateHardwareBackedEcKeyPair(alias)
    }

    override fun generateDeviceKeyPair(): PublicKey {
        val alias = IKeyManager.DEVICE_IDENTITY_KEY_ALIAS
        return generateHardwareBackedEcKeyPair(alias)
    }

    override fun getPublicKey(keyAlias: String): PublicKey? {
        return keyStore.getCertificate(keyAlias)?.publicKey
    }

    override fun signData(keyAlias: String, data: ByteArray): ByteArray {
        val privateKey = keyStore.getKey(keyAlias, null) as? PrivateKey
            ?: throw IllegalStateException("Không tìm thấy khóa riêng tư với alias: $keyAlias trong AndroidKeystore")

        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(privateKey)
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
        cachedPassphrase?.let { return it.copyOf() }

        return synchronized(this) {
            cachedPassphrase?.let { return@synchronized it.copyOf() }

            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            val sharedPreferences = EncryptedSharedPreferences.create(
                context,
                PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )

            var passphraseHex = sharedPreferences.getString(PREF_DB_PASSPHRASE_HEX, null)
            if (passphraseHex == null) {
                val newPassphrase = ByteArray(32)
                secureRandom.nextBytes(newPassphrase)
                passphraseHex = newPassphrase.joinToString("") { "%02x".format(it) }
                sharedPreferences.edit().putString(PREF_DB_PASSPHRASE_HEX, passphraseHex).commit()
            }

            val key = hexToBytes(passphraseHex)
            cachedPassphrase = key
            key.copyOf()
        }
    }

    override fun deleteKey(keyAlias: String): Boolean {
        return try {
            if (keyStore.containsAlias(keyAlias)) {
                keyStore.deleteEntry(keyAlias)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    override fun hasKey(keyAlias: String): Boolean {
        return try {
            keyStore.containsAlias(keyAlias)
        } catch (e: Exception) {
            false
        }
    }

    private fun generateHardwareBackedEcKeyPair(keyAlias: String): PublicKey {
        val isStrongBox = isStrongBoxSupported()

        val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE_PROVIDER)
        try {
            val specBuilder = KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            ).apply {
                setAlgorithmParameterSpec(ECGenParameterSpec(EC_CURVE))
                setDigests(KeyProperties.DIGEST_SHA256)
                setUserAuthenticationRequired(false) // Cho phép ký trong background foreground service
                if (isStrongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    setIsStrongBoxBacked(true)
                }
            }
            kpg.initialize(specBuilder.build())
            val keyPair = kpg.generateKeyPair()
            return keyPair.public
        } catch (e: Exception) {
            // Fallback về TEE tiêu chuẩn nếu StrongBox sinh lỗi
            if (isStrongBox) {
                val fallbackSpec = KeyGenParameterSpec.Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
                ).apply {
                    setAlgorithmParameterSpec(ECGenParameterSpec(EC_CURVE))
                    setDigests(KeyProperties.DIGEST_SHA256)
                    setUserAuthenticationRequired(false)
                }.build()
                kpg.initialize(fallbackSpec)
                return kpg.generateKeyPair().public
            }
            throw e
        }
    }

    private fun isStrongBoxSupported(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
        } else {
            false
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val result = ByteArray(hex.length / 2)
        for (i in result.indices) {
            val index = i * 2
            result[i] = hex.substring(index, index + 2).toInt(16).toByte()
        }
        return result
    }
}

