package com.teamshare.app.domain.usecase.security

import com.teamshare.app.core.security.keystore.IKeyManager
import java.security.PublicKey

/**
 * FR-DEV-01 & SDD Bảng 4.6 & 12.1: Khởi tạo định danh thiết bị & Neo giữ cặp khóa trong Keystore TEE / StrongBox.
 * Sinh mới hoặc tái sử dụng cặp khóa bất đối xứng ECDSA secp256r1 neo giữ trong phân vùng phần cứng an toàn.
 */
class InitializeKeystoreUseCase(
    private val keyManager: IKeyManager
) {
    /**
     * Khởi tạo hoặc truy xuất khóa công khai định danh thiết bị.
     *
     * @return Result chứa PublicKey định danh thiết bị
     */
    suspend fun execute(): Result<PublicKey> {
        return try {
            val alias = IKeyManager.DEVICE_IDENTITY_KEY_ALIAS
            val existingKey = keyManager.getPublicKey(alias)
                ?: keyManager.getPublicKey(IKeyManager.MEMBER_IDENTITY_KEY_ALIAS)

            val publicKey = existingKey ?: keyManager.generateDeviceKeyPair()
            Result.success(publicKey)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend operator fun invoke(): Result<PublicKey> = execute()
}

