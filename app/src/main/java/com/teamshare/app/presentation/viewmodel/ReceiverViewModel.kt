package com.teamshare.app.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.teamshare.app.core.transfer.lan.LanTransferEngine
import com.teamshare.app.core.transfer.relay.RelayClientEngine
import com.teamshare.app.domain.model.DeviceProfile
import com.teamshare.app.domain.model.SessionQrPayload
import com.teamshare.app.domain.usecase.device.GetDeviceIdentityUseCase
import com.teamshare.app.domain.usecase.transfer.CheckReceiverStorageUseCase
import com.teamshare.app.domain.usecase.transfer.RequestConnectionUseCase
import com.teamshare.app.domain.usecase.transfer.ScanQrTokenUseCase
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel quản lý quét mã QR, đối chiếu bộ nhớ và kết nối tải tệp phía Người nhận (FR-RCV-01..03).
 * Tuân thủ SDD Mục 4.2.3 (ReceiverViewModel).
 */
class ReceiverViewModel(
    private val scanQrTokenUseCase: ScanQrTokenUseCase,
    private val checkReceiverStorageUseCase: CheckReceiverStorageUseCase,
    private val requestConnectionUseCase: RequestConnectionUseCase? = null,
    private val getDeviceIdentityUseCase: GetDeviceIdentityUseCase? = null,
    private val lanEngine: LanTransferEngine? = null,
    private val relayEngine: RelayClientEngine? = null,
    private val customScope: CoroutineScope? = null
) : ViewModel() {

    private val scope: CoroutineScope get() = customScope ?: viewModelScope

    private val _uiState = MutableStateFlow(ReceiverUiState())
    val uiState: StateFlow<ReceiverUiState> = _uiState.asStateFlow()

    /**
     * Xử lý chuỗi văn bản mã QR quét được từ CameraX Scanner.
     */
    fun onQrCodeScanned(
        qrRawText: String,
        targetDir: File? = null,
        onValidQr: ((SessionQrPayload) -> Unit)? = null
    ): Job {
        if (!_uiState.value.isScanning) return Job().apply { complete() }

        return scope.launch {
            try {
                val result = scanQrTokenUseCase.execute(qrRawText)
                result.onSuccess { payload ->
                    // Kiểm tra dung lượng đĩa trống thực tế (StatFs)
                    val storageResult = checkReceiverStorageUseCase.execute(
                        requiredBytes = payload.totalBytes,
                        targetDir = targetDir,
                        sessionId = payload.sessionId
                    )

                    _uiState.update {
                        it.copy(
                            isScanning = false,
                            qrPayload = payload,
                            availableBytes = storageResult.availableBytes,
                            isStorageSufficient = storageResult.isSufficient,
                            shortfallBytes = storageResult.shortfallBytes,
                            pendingSessionId = payload.sessionId,
                            errorMessage = null
                        )
                    }

                    onValidQr?.invoke(payload)
                }.onFailure { error ->
                    _uiState.update {
                        it.copy(
                            errorMessage = error.message ?: "Mã QR không hợp lệ"
                        )
                    }
                }
            } catch (e: Throwable) {
                _uiState.update {
                    it.copy(
                        errorMessage = e.message ?: "Lỗi xử lý mã chia sẻ"
                    )
                }
            }
        }
    }

    /**
     * Xác thực nhanh chuỗi mã chia sẻ (phục vụ xem trước Live Preview trên UI nhập mã).
     */
    fun validateShareCode(codeText: String): Result<SessionQrPayload> {
        return try {
            val payload = SessionQrPayload.parse(codeText)
            if (payload.isExpired) {
                Result.failure(SecurityException("0x103: Mã chia sẻ đã hết hạn hiệu lực (5 phút)"))
            } else {
                Result.success(payload)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Xử lý khi người dùng nhập hoặc dán mã chia sẻ thủ công.
     */
    fun onManualCodeEntered(
        codeText: String,
        targetDir: File? = null,
        onValidCode: ((SessionQrPayload) -> Unit)? = null
    ): Job {
        _uiState.update { it.copy(isScanning = true, errorMessage = null) }
        return onQrCodeScanned(
            qrRawText = codeText,
            targetDir = targetDir,
            onValidQr = onValidCode
        )
    }

    /**
     * Bật lại chế độ quét Camera khi người dùng muốn quét lại.
     */
    fun resetScanner() {
        _uiState.update {
            it.copy(
                isScanning = true,
                errorMessage = null,
                connectionState = ReceiverConnectionState.IDLE
            )
        }
    }

    /**
     * Gửi yêu cầu kết nối tới Người gửi để bắt đầu tải tệp.
     */
    fun requestConnection(
        targetDir: File,
        onConnected: ((File) -> Unit)? = null
    ) {
        val payload = _uiState.value.qrPayload ?: return
        if (!_uiState.value.isStorageSufficient) {
            _uiState.update { it.copy(errorMessage = "Thiết bị không đủ bộ nhớ trống để tải tệp") }
            return
        }

        val connUseCase = requestConnectionUseCase
        val engine = lanEngine
        if (connUseCase == null || (engine == null && relayEngine == null)) {
            // Trường hợp chạy UI mockup hoặc chưa khởi tạo động cơ mạng
            _uiState.update { it.copy(connectionState = ReceiverConnectionState.APPROVED) }
            return
        }

        scope.launch {
            _uiState.update { it.copy(connectionState = ReceiverConnectionState.CONNECTING) }
            val profile = getDeviceIdentityUseCase?.execute() ?: DeviceProfile(
                manufacturer = "Android",
                model = "Device",
                fingerprint = "fp_${System.currentTimeMillis()}"
            )

            val downloadResult = connUseCase.execute(
                payload = payload,
                myProfile = profile,
                targetDirectory = targetDir,
                lanEngine = engine ?: LanTransferEngine(),
                relayEngineOverride = relayEngine
            )

            downloadResult.onSuccess { downloadedFile ->
                _uiState.update { it.copy(connectionState = ReceiverConnectionState.APPROVED) }
                onConnected?.invoke(downloadedFile)
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        connectionState = ReceiverConnectionState.FAILED,
                        errorMessage = error.message ?: "Kết nối tải tệp thất bại"
                    )
                }
            }
        }
    }

    /**
     * Hủy bỏ quá trình nhận tệp và quay lại trạng thái ban đầu.
     */
    fun cancelDownload() {
        _uiState.update {
            it.copy(
                isScanning = true,
                qrPayload = null,
                connectionState = ReceiverConnectionState.IDLE,
                errorMessage = null
            )
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}
