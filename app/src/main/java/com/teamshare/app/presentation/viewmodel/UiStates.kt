package com.teamshare.app.presentation.viewmodel

import android.graphics.Bitmap
import com.teamshare.app.core.constant.AppConstants
import com.teamshare.app.core.transfer.lan.TransferEngineState
import com.teamshare.app.domain.model.ApprovalMode
import com.teamshare.app.domain.model.AuditLog
import com.teamshare.app.domain.model.DeviceProfile
import com.teamshare.app.domain.model.SessionQrPayload
import com.teamshare.app.domain.model.TransferChannel
import com.teamshare.app.domain.model.TransferProgress
import java.io.File

/**
 * Trạng thái truyền tải của từng máy nhận kết nối tới máy phát (1-N).
 */
enum class ReceiverTransferStatus {
    CONNECTING,
    TRANSFERRING,
    COMPLETED,
    FAILED
}

/**
 * Thông tin tiến trình và tốc độ truyền tải thời gian thực của một máy nhận cụ thể.
 */
data class ReceiverProgressInfo(
    val device: DeviceProfile,
    val bytesTransferred: Long = 0L,
    val totalBytes: Long = 0L,
    val speedBytesPerSec: Long = 0L,
    val status: ReceiverTransferStatus = ReceiverTransferStatus.CONNECTING,
    val errorMessage: String? = null
) {
    val progressPercent: Float
        get() = if (totalBytes > 0L) {
            (bytesTransferred.toFloat() / totalBytes.toFloat()).coerceIn(0.0f, 1.0f)
        } else {
            0.0f
        }
}

/**
 * Trạng thái giao diện Màn hình Người phát tệp (SenderHomeScreen & QrDisplayScreen).
 * Tuân thủ SDD Mục 4.2.2 (SenderSessionUiState).
 */
data class SenderSessionUiState(
    val selectedFiles: List<File> = emptyList(),
    val totalBytes: Long = 0L,
    val approvalMode: ApprovalMode = ApprovalMode.MANUAL_APPROVAL,
    val selectedTtlSeconds: Long = AppConstants.Timeout.QR_TTL_SECONDS,
    val isSharing: Boolean = false,
    val sessionId: String = "",
    val qrBitmap: Bitmap? = null,
    val qrPayload: SessionQrPayload? = null,
    val remainingTtlSeconds: Long = AppConstants.Timeout.QR_TTL_SECONDS,
    val pendingApprovalDevice: DeviceProfile? = null,
    val connectedDevices: List<DeviceProfile> = emptyList(),
    val receiverProgressMap: Map<String, ReceiverProgressInfo> = emptyMap(),
    val maxReceivers: Int = AppConstants.FlowControl.MAX_SESSION_RECEIVERS,
    val errorMessage: String? = null,
    val errorResId: Int? = null
)

/**
 * Trạng thái kết nối của Người nhận (Receiver).
 */
enum class ReceiverConnectionState {
    IDLE,
    CONNECTING,
    WAITING_APPROVAL,
    APPROVED,
    REJECTED,
    FAILED
}

/**
 * Trạng thái giao diện Màn hình Người nhận (ScannerScreen & ReceiverHomeScreen).
 */
data class ReceiverUiState(
    val isScanning: Boolean = true,
    val qrPayload: SessionQrPayload? = null,
    val availableBytes: Long = 0L,
    val isStorageSufficient: Boolean = true,
    val shortfallBytes: Long = 0L,
    val connectionState: ReceiverConnectionState = ReceiverConnectionState.IDLE,
    val pendingSessionId: String? = null,
    val errorMessage: String? = null,
    val errorResId: Int? = null
)

/**
 * Trạng thái lưu trữ của tệp tin theo Kiến trúc Lưu trữ 2 Tầng.
 */
enum class FileStorageState {
    SANDBOX_ONLY,       // Tệp mới nhận, chỉ có trong Sandbox riêng
    EXPORTING,          // Đang ghi vào MediaStore Downloads
    SAVED_TO_PUBLIC,    // Đã lưu an toàn ra Downloads (Đang tồn tại cả 2 bản)
    SANDBOX_CLEANED     // Đã dọn dẹp bản tạm Sandbox, chỉ còn ở Downloads
}

/**
 * Thông tin tệp tin con sau khi nhận và giải nén (phục vụ duyệt danh sách nhiều tệp / thư mục).
 */
data class ReceivedFileInfo(
    val name: String,
    val sizeBytes: Long,
    val absolutePath: String,
    val isDirectory: Boolean = false,
    val storageState: FileStorageState = FileStorageState.SANDBOX_ONLY
)

/**
 * Trạng thái giao diện Màn hình Tiến trình Truyền tải (TransferProgressScreen).
 * Được điều tiết chu kỳ tối thiểu 100ms để bảo vệ luồng đồ họa Compose (NFR-PERF-03).
 */
data class TransferUiState(
    val sessionId: String = "",
    val fileName: String = "",
    val totalBytes: Long = 0L,
    val transferredBytes: Long = 0L,
    val speedBytesPerSec: Long = 0L,
    val progressPercent: Float = 0.0f,
    val peerDeviceModel: String = "",
    val channel: TransferChannel = TransferChannel.DIRECT_LAN,
    val state: TransferEngineState = TransferEngineState.IDLE,
    val sasCode: String? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val etaSeconds: Long = 0L,
    val durationText: String = "",
    val averageSpeedBytesPerSec: Long = 0L,
    val activeDurationSeconds: Double = 0.0,
    val completedFilePath: String? = null,
    val isDirectoryOrBatch: Boolean = false,
    val receivedFileList: List<ReceivedFileInfo> = emptyList(),
    val singleFileStorageState: FileStorageState = FileStorageState.SANDBOX_ONLY,
    val duplicateFileToConfirm: ReceivedFileInfo? = null,
    val showSingleDuplicateDialog: Boolean = false,
    val showBatchDuplicateDialog: Boolean = false,
    val pendingBatchDuplicates: List<ReceivedFileInfo> = emptyList(),
    val showCleanupSuggestionDialog: Boolean = false,
    val cleanableBytes: Long = 0L,
    val exportFeedbackMessage: String? = null
) {
    companion object {
        fun fromProgress(
            progress: TransferProgress,
            fileName: String = "",
            peerDeviceModel: String = "",
            durationText: String = "",
            completedFilePath: String? = null
        ): TransferUiState {
            val remainingBytes = (progress.totalBytes - progress.bytesTransferred).coerceAtLeast(0L)
            val eta = if (progress.speedBytesPerSec > 0L) remainingBytes / progress.speedBytesPerSec else 0L
            return TransferUiState(
                sessionId = progress.sessionId,
                fileName = fileName,
                totalBytes = progress.totalBytes,
                transferredBytes = progress.bytesTransferred,
                speedBytesPerSec = progress.speedBytesPerSec,
                progressPercent = progress.progressPercent,
                peerDeviceModel = peerDeviceModel,
                channel = progress.channel,
                state = progress.state,
                sasCode = progress.sasCode,
                errorCode = progress.errorCode,
                errorMessage = progress.errorMessage,
                etaSeconds = eta,
                durationText = durationText,
                completedFilePath = completedFilePath
            )
        }
    }
}

/**
 * Trạng thái giao diện Màn hình Nhật ký Hoạt động & Kiểm toán An ninh (AuditScreen).
 */
data class AuditUiState(
    val logs: List<AuditLog> = emptyList(),
    val filteredLogs: List<AuditLog> = emptyList(),
    val filterSeverity: String = "ALL",
    val searchQuery: String = "",
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)
