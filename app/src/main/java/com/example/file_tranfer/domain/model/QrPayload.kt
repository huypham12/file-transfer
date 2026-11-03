package com.example.file_tranfer.domain.model

/**
 * Cấu trúc dữ liệu mã QR trao đổi qua kênh quang học (Optical Channel) để ghép đôi ban đầu.
 */
data class QrPayload(
    val version: Int = 1,
    val deviceId: String,
    val ipAddress: String,
    val port: Int,
    val fingerprint: String, // Hex 64 ký tự SHA-256 Public Key của máy nhận
    val token: String,       // Pairing token 128-bit dùng một lần
    val expiresAt: Long      // Unix Epoch tính theo giây (TTL thông thường là 180s)
) {
    fun isExpired(currentEpochSeconds: Long = System.currentTimeMillis() / 1000L): Boolean {
        return expiresAt > 0L && currentEpochSeconds >= expiresAt
    }
}

