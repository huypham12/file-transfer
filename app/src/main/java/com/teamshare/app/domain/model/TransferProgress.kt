package com.teamshare.app.domain.model

import com.teamshare.app.core.transfer.lan.TransferEngineState

/**
 * Mô hình dữ liệu thông báo tiến trình truyền tải tệp P2P thời gian thực.
 * Cung cấp dữ liệu phản ứng cho TransferCoordinator, UseCases và tầng Presentation (Stage 7).
 */
data class TransferProgress(
    val sessionId: String,
    val bytesTransferred: Long = 0L,
    val totalBytes: Long = 0L,
    val speedBytesPerSec: Long = 0L,
    val progressPercent: Float = if (totalBytes > 0L) (bytesTransferred.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f,
    val channel: TransferChannel = TransferChannel.DIRECT_LAN,
    val state: TransferEngineState = TransferEngineState.IDLE,
    val sasCode: String? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null
) {
    val isCompleted: Boolean get() = state == TransferEngineState.COMPLETED
    val isFailed: Boolean get() = state == TransferEngineState.FAILED
    val isPaused: Boolean get() = state == TransferEngineState.PAUSED
    val isTransferring: Boolean get() = state == TransferEngineState.TRANSFERRING
}

