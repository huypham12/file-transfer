package com.teamshare.app.domain.model

/**
 * Cấu trúc dữ liệu mã QR động có chữ ký số của Owner để mời thành viên vào nhóm.
 */
data class QrInvitePayload(
    val teamId: String,
    val invitationToken: String,
    val expiresAt: Long,
    val ownerPublicKey: String,
    val signature: String,
    val teamName: String = "",
    val ownerHost: String? = null,
    val ownerPort: Int = 8988,
    val relayUrl: String? = null
) {
    fun isExpired(currentEpochSeconds: Long = System.currentTimeMillis() / 1000L): Boolean {
        return expiresAt > 0L && currentEpochSeconds >= expiresAt
    }
}

