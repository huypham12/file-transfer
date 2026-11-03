package com.example.file_tranfer.domain.model

/**
 * Thực thể quản lý toàn diện một phiên truyền dữ liệu P2P (Transfer Session).
 */
data class TransferSession(
    val sessionId: String,
    val peerDevice: DeviceIdentity,
    val direction: TransferDirection,
    val policy: SharePolicy? = null,
    val files: List<TransferFile> = emptyList(),
    val totalBytes: Long = 0L,
    val bytesTransferred: Long = 0L,
    val confirmedOffset: Long = 0L, // Tọa độ Checkpoint an toàn do bên nhận xác nhận
    val currentFileIndex: Int = 0,
    val status: SessionStatus = SessionStatus.PENDING,
    val startedAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null
) {
    val totalFiles: Int
        get() = files.size

    val overallProgress: Float
        get() = when {
            totalBytes > 0L -> (bytesTransferred.toFloat() / totalBytes).coerceIn(0f, 1f)
            isCompleted -> 1.0f
            else -> 0.0f
        }

    val isCompleted: Boolean
        get() = status == SessionStatus.COMPLETED

    val canResume: Boolean
        get() = status == SessionStatus.PAUSED || status == SessionStatus.FAILED
}

