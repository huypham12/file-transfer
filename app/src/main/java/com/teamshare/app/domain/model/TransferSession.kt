package com.teamshare.app.domain.model

/**
 * Quản lý trạng thái và tiến trình của một phiên truyền tải tài liệu P2P.
 */
data class TransferSession(
    val sessionId: String,
    val docId: String,
    val peerMemberId: String,
    val direction: TransferDirection,
    val channel: TransferChannel = TransferChannel.DIRECT_LAN,
    val totalBytes: Long,
    val bytesTransferred: Long = 0L,
    val confirmedOffset: Long = 0L,
    val tempFilePath: String = "",
    val status: SessionStatus = SessionStatus.TRANSFERRING,
    val errorCode: String? = null,
    val startedAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null
) {
    val progressPercent: Float
        get() = when {
            isCompleted -> 1.0f
            totalBytes > 0L -> (confirmedOffset.toFloat() / totalBytes).coerceIn(0f, 1f)
            else -> 0.0f
        }

    val isCompleted: Boolean get() = status == SessionStatus.COMPLETED
    val canResume: Boolean get() = status == SessionStatus.PAUSED || status == SessionStatus.FAILED
    val canRetry: Boolean get() = status == SessionStatus.FAILED
}
