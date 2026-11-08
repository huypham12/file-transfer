package com.teamshare.app.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.teamshare.app.domain.model.ApprovalMode
import com.teamshare.app.domain.model.DeviceProfile
import com.teamshare.app.domain.usecase.transfer.CancelTransferSessionUseCase
import com.teamshare.app.domain.usecase.transfer.CreateSenderSessionUseCase
import com.teamshare.app.domain.usecase.transfer.ManageApprovalModeUseCase
import com.teamshare.app.presentation.ui.qr.QrCodeBitmapGenerator
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel quản lý phiên phát chia sẻ 1-N phía Người gửi (FR-SND-01..04).
 * Tuân thủ SDD Mục 4.2.3 (SenderViewModel).
 */
class SenderViewModel(
    private val createSenderSessionUseCase: CreateSenderSessionUseCase,
    private val manageApprovalModeUseCase: ManageApprovalModeUseCase,
    private val cancelTransferSessionUseCase: CancelTransferSessionUseCase,
    private val customScope: CoroutineScope? = null
) : ViewModel() {

    private val scope: CoroutineScope get() = customScope ?: viewModelScope

    private val _uiState = MutableStateFlow(SenderSessionUiState())
    val uiState: StateFlow<SenderSessionUiState> = _uiState.asStateFlow()

    private var ttlTimerJob: Job? = null

    /**
     * Cập nhật danh sách tệp hoặc thư mục được người dùng chọn qua SAF.
     */
    fun onFilesSelected(files: List<File>) {
        val totalBytes = files.sumOf { calculateTotalBytes(it) }
        _uiState.update {
            it.copy(
                selectedFiles = files,
                totalBytes = totalBytes,
                errorMessage = null
            )
        }
    }

    /**
     * Tiếp nhận danh sách Uri từ Android SAF (Sharesheet hoặc OpenMultipleDocuments).
     * Sao chép an toàn vào cache sandbox để đảm bảo luồng I/O không bị ngắt quãng.
     */
    fun onUrisSelected(uris: List<android.net.Uri>, context: android.content.Context) {
        scope.launch {
            val safGateway = com.teamshare.app.core.storage.saf.SafStorageGateway(context)
            val cacheFolder = File(context.cacheDir, "outgoing_shares").apply { mkdirs() }
            val resolvedFiles = mutableListOf<File>()

            for (uri in uris) {
                val metadata = safGateway.queryMetadata(uri) ?: continue
                val targetFile = File(cacheFolder, metadata.fileName)
                try {
                    safGateway.openInputStream(uri)?.use { input ->
                        targetFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    if (targetFile.exists() && targetFile.length() > 0) {
                        resolvedFiles.add(targetFile)
                    }
                } catch (_: Throwable) {
                }
            }

            if (resolvedFiles.isNotEmpty()) {
                val current = _uiState.value.selectedFiles.toMutableList()
                current.addAll(resolvedFiles)
                val distinct = current.distinctBy { it.absolutePath }
                onFilesSelected(distinct)
            }
        }
    }

    /**
     * Xóa một tệp khỏi danh sách đã chọn.
     */
    fun removeSelectedFile(file: File) {
        val updatedFiles = _uiState.value.selectedFiles.filterNot { it.absolutePath == file.absolutePath }
        val totalBytes = updatedFiles.sumOf { calculateTotalBytes(it) }
        _uiState.update {
            it.copy(
                selectedFiles = updatedFiles,
                totalBytes = totalBytes
            )
        }
    }

    /**
     * Dọn sạch toàn bộ danh sách tệp đã chọn.
     */
    fun clearSelectedFiles() {
        _uiState.update {
            it.copy(
                selectedFiles = emptyList(),
                totalBytes = 0L
            )
        }
    }

    /**
     * Thay đổi chế độ phê duyệt thiết bị (Tự động hoặc Phê duyệt thủ công).
     */
    fun setApprovalMode(mode: ApprovalMode) {
        _uiState.update { it.copy(approvalMode = mode) }
        val currentSessionId = _uiState.value.sessionId
        if (currentSessionId.isNotBlank() && _uiState.value.isSharing) {
            scope.launch {
                manageApprovalModeUseCase(currentSessionId, mode)
            }
        }
    }

    /**
     * Khởi tạo phiên phát chia sẻ 1-N và tạo mã QR động.
     */
    fun startSharingSession(
        senderIp: String = "127.0.0.1",
        senderPort: Int = 8889,
        onSessionCreated: ((String) -> Unit)? = null
    ): Job {
        val files = _uiState.value.selectedFiles
        if (files.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Vui lòng chọn ít nhất một tệp hoặc thư mục để chia sẻ") }
            return Job().apply { complete() }
        }

        return scope.launch {
            val result = createSenderSessionUseCase.execute(
                files = files,
                senderIp = senderIp,
                senderPort = senderPort,
                approvalMode = _uiState.value.approvalMode
            )

            result.onSuccess { sessionData ->
                val rawQrText = sessionData.qrPayload.toQrString()
                val qrBitmap = try {
                    QrCodeBitmapGenerator.generateQrBitmap(rawQrText, 512)
                } catch (_: Throwable) {
                    null
                }

                _uiState.update {
                    it.copy(
                        isSharing = true,
                        sessionId = sessionData.session.sessionId,
                        qrPayload = sessionData.qrPayload,
                        qrBitmap = qrBitmap,
                        remainingTtlSeconds = 300L,
                        errorMessage = null
                    )
                }

                startTtlTimer()
                onSessionCreated?.invoke(sessionData.session.sessionId)
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isSharing = false,
                        errorMessage = error.message ?: "Không thể khởi tạo phiên chia sẻ"
                    )
                }
            }
        }
    }

    /**
     * Bắt đầu đồng hồ đếm ngược hiệu lực mã QR (TTL 5 phút).
     */
    private fun startTtlTimer() {
        ttlTimerJob?.cancel()
        ttlTimerJob = scope.launch {
            var timeLeft = 300L
            while (timeLeft > 0 && _uiState.value.isSharing) {
                delay(1000L)
                timeLeft--
                _uiState.update { it.copy(remainingTtlSeconds = timeLeft) }
            }
            if (timeLeft <= 0) {
                _uiState.update {
                    it.copy(errorMessage = "Mã QR đã hết hạn hiệu lực (5 phút). Vui lòng tạo phiên mới.")
                }
            }
        }
    }

    /**
     * Ghi nhận yêu cầu kết nối từ thiết bị nhận.
     */
    fun onDeviceConnectionRequested(device: DeviceProfile) {
        if (_uiState.value.approvalMode == ApprovalMode.MANUAL_APPROVAL) {
            _uiState.update { it.copy(pendingApprovalDevice = device) }
        } else {
            // Tự động phê duyệt
            val updatedList = _uiState.value.connectedDevices.toMutableList()
            if (updatedList.none { it.fingerprint == device.fingerprint }) {
                updatedList.add(device)
            }
            _uiState.update { it.copy(connectedDevices = updatedList) }
        }
    }

    /**
     * Phê duyệt hoặc Từ chối thiết bị nhận đang chờ kết nối.
     */
    fun approvePendingDevice(device: DeviceProfile, accept: Boolean) {
        val currentPending = _uiState.value.pendingApprovalDevice
        if (currentPending?.fingerprint == device.fingerprint) {
            val updatedList = _uiState.value.connectedDevices.toMutableList()
            if (accept && updatedList.none { it.fingerprint == device.fingerprint }) {
                updatedList.add(device)
            }
            _uiState.update {
                it.copy(
                    pendingApprovalDevice = null,
                    connectedDevices = updatedList
                )
            }
        }
    }

    /**
     * Dừng và đóng phiên phát tệp.
     */
    fun stopSharingSession(onStopped: (() -> Unit)? = null): Job {
        val sessionId = _uiState.value.sessionId
        ttlTimerJob?.cancel()
        _uiState.update {
            it.copy(
                isSharing = false,
                sessionId = "",
                qrBitmap = null,
                qrPayload = null,
                pendingApprovalDevice = null,
                connectedDevices = emptyList()
            )
        }
        onStopped?.invoke()
        return scope.launch {
            if (sessionId.isNotBlank()) {
                cancelTransferSessionUseCase(sessionId)
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    private fun calculateTotalBytes(file: File): Long {
        if (!file.exists()) return 0L
        if (file.isFile) return file.length()
        var size = 0L
        file.listFiles()?.forEach { child ->
            size += calculateTotalBytes(child)
        }
        return size
    }

    override fun onCleared() {
        super.onCleared()
        ttlTimerJob?.cancel()
    }
}
