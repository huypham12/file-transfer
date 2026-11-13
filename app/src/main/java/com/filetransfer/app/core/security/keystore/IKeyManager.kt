package com.filetransfer.app.core.security.keystore

import java.security.PublicKey

/**
 * Hợp đồng giao diện quản lý cặp khóa an toàn và mật mã danh tính (Key Management Contract).
 * Áp dụng nguyên lý Dependency Inversion (DIP) của Clean Architecture:
 * - Tầng ứng dụng phụ thuộc vào [IKeyManager].
 * - [AndroidKeystoreKeyManager] hiện thực hóa phần cứng an toàn TEE/StrongBox trên thiết bị Android.
 * - [SoftwareKeyManager] hiện thực hóa thuần bộ nhớ phục vụ kiểm thử tự động trên JVM độc lập.
 */
interface IKeyManager {

    companion object {
        const val MASTER_KEY_PREFIX = "filetransfer_master_key_"
        const val MEMBER_IDENTITY_KEY_ALIAS = "filetransfer_member_identity_key"
        const val DEVICE_IDENTITY_KEY_ALIAS = "filetransfer_device_identity_key"
        const val DATABASE_PASSPHRASE_ALIAS = "filetransfer_db_passphrase_key"
    }

    /**
     * Sinh cặp khóa Định danh Thiết bị ECDSA secp256r1 trong TEE / StrongBox (SDD Mục 9.1).
     */
    fun generateDeviceKeyPair(): PublicKey = generateMemberIdentityKey()

    /**
     * Sinh cặp khóa ECDSA secp256r1 cho Trưởng nhóm (Owner Master Key) gắn với một [teamId].
     */
    fun generateOwnerMasterKey(teamId: String): PublicKey

    /**
     * Sinh cặp khóa Danh tính ECDSA secp256r1 cho Thành viên (Member Identity Key).
     */
    fun generateMemberIdentityKey(): PublicKey

    /**
     * Lấy khóa công khai theo alias.
     */
    fun getPublicKey(keyAlias: String): PublicKey?

    /**
     * Ký số dữ liệu bằng khóa riêng tư (Private Key) được bảo vệ bởi Keystore.
     * Thuật toán: SHA256withECDSA.
     */
    fun signData(keyAlias: String, data: ByteArray): ByteArray

    /**
     * Xác minh chữ ký số bằng khóa công khai (Public Key).
     * Thuật toán: SHA256withECDSA.
     */
    fun verifySignature(publicKey: PublicKey, data: ByteArray, signature: ByteArray): Boolean

    /**
     * Ký số mã token phiên chia sẻ (Session Token) phục vụ xác thực tính toàn vẹn (Non-Repudiation).
     */
    fun signSessionToken(token: String): String {
        val data = token.toByteArray(Charsets.UTF_8)
        val alias = if (hasKey(DEVICE_IDENTITY_KEY_ALIAS)) DEVICE_IDENTITY_KEY_ALIAS else MEMBER_IDENTITY_KEY_ALIAS
        val sigBytes = signData(alias, data)
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(sigBytes)
    }

    /**
     * Xác thực chữ ký số của mã token phiên chia sẻ.
     */
    fun verifySessionToken(token: String, signatureBase64: String, publicKey: PublicKey): Boolean {
        return try {
            val data = token.toByteArray(Charsets.UTF_8)
            val sigBytes = java.util.Base64.getUrlDecoder().decode(signatureBase64)
            verifySignature(publicKey, data, sigBytes)
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Lấy hoặc sinh mới chuỗi mật khẩu 32 bytes (256-bit) cho CSDL SQLCipher.
     */
    fun getOrCreateDatabasePassphrase(): ByteArray

    /**
     * Xóa bỏ cặp khóa khỏi kho khóa theo alias (dùng khi giải tán nhóm hoặc thu hồi quyền).
     */
    fun deleteKey(keyAlias: String): Boolean

    /**
     * Kiểm tra sự tồn tại của khóa theo alias.
     */
    fun hasKey(keyAlias: String): Boolean
}

