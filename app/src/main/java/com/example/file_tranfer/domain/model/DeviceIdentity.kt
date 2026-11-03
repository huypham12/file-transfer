package com.example.file_tranfer.domain.model

/**
 * Định danh phần cứng và chứng chỉ mật mã của nút mạng (Device Identity).
 */
data class DeviceIdentity(
    val deviceId: String,
    val deviceName: String,
    val fingerprint: String, // Hex 64 ký tự: SHA-256 của PublicKey.encoded
    val publicKeyBytes: ByteArray,
    val lastSeenAt: Long = System.currentTimeMillis(),
    val isTrusted: Boolean = false
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as DeviceIdentity

        if (deviceId != other.deviceId) return false
        if (deviceName != other.deviceName) return false
        if (fingerprint != other.fingerprint) return false
        if (!publicKeyBytes.contentEquals(other.publicKeyBytes)) return false
        if (lastSeenAt != other.lastSeenAt) return false
        if (isTrusted != other.isTrusted) return false

        return true
    }

    override fun hashCode(): Int {
        var result = deviceId.hashCode()
        result = 31 * result + deviceName.hashCode()
        result = 31 * result + fingerprint.hashCode()
        result = 31 * result + publicKeyBytes.contentHashCode()
        result = 31 * result + lastSeenAt.hashCode()
        result = 31 * result + isTrusted.hashCode()
        return result
    }
}

