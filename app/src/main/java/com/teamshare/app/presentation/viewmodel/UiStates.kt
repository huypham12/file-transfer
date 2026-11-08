package com.teamshare.app.presentation.viewmodel

import com.teamshare.app.core.transfer.lan.TransferEngineState
import com.teamshare.app.domain.model.AuditLog
import com.teamshare.app.domain.model.TransferChannel
import com.teamshare.app.domain.model.TransferProgress

/**
 * Trạng thái giao diện Màn hình Tiến trình Truyền tải (TransferProgressScreen).
 * Được điều tiết chu kỳ tối thiểu 100ms để bảo vệ luồng đồ họa Compose.
 */
data class TransferUiState(
    val sessionId: String = "",
    val fileName: String = "",
    val totalBytes: Long = 0L,
    val transferredBytes: Long = 0L,
    val speedBytesPerSec: Long = 0L,
    val progressPercent: Float = 0.0f,
    val channel: TransferChannel = TransferChannel.DIRECT_LAN,
    val state: TransferEngineState = TransferEngineState.IDLE,
    val sasCode: String? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val etaSeconds: Long = 0L,
    val durationText: String = ""
) {
    companion object {
        fun fromProgress(progress: TransferProgress, fileName: String = "", durationText: String = ""): TransferUiState {
            val remainingBytes = (progress.totalBytes - progress.bytesTransferred).coerceAtLeast(0L)
            val eta = if (progress.speedBytesPerSec > 0L) remainingBytes / progress.speedBytesPerSec else 0L
            return TransferUiState(
                sessionId = progress.sessionId,
                fileName = fileName,
                totalBytes = progress.totalBytes,
                transferredBytes = progress.bytesTransferred,
                speedBytesPerSec = progress.speedBytesPerSec,
                progressPercent = progress.progressPercent,
                channel = progress.channel,
                state = progress.state,
                sasCode = progress.sasCode,
                errorCode = progress.errorCode,
                errorMessage = progress.errorMessage,
                etaSeconds = eta,
                durationText = durationText
            )
        }
    }
}

/**
 * Trạng thái giao diện Màn hình Nhật ký Hoạt động & Kiểm toán An ninh (AuditScreen).
 */
data class AuditUiState(
    val logs: List<AuditLog> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)
