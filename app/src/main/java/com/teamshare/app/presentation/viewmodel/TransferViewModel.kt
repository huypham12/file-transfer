package com.teamshare.app.presentation.viewmodel

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.teamshare.app.core.platform.network.NetworkAddressHelper
import com.teamshare.app.core.transfer.TransferCoordinator
import com.teamshare.app.core.transfer.lan.TransferEngineState
import com.teamshare.app.domain.model.SessionCmd
import com.teamshare.app.domain.model.SessionStatus
import com.teamshare.app.domain.model.TransferChannel
import com.teamshare.app.domain.model.TransferDirection
import com.teamshare.app.domain.model.TransferProgress
import com.teamshare.app.domain.model.TransferSession
import com.teamshare.app.domain.usecase.platform.OpenFileSecurelyUseCase
import com.teamshare.app.domain.usecase.transfer.ControlSessionUseCase
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.teamshare.app.core.transfer.lan.TransferProgressCallback
import com.teamshare.app.core.transfer.relay.RelayClientEngine
import com.teamshare.app.domain.model.ErrorCode

/**
 * ViewModel điều khiển phiên truyền tải tệp P2P (LAN / Relay).
 * Tích hợp động cơ điều tiết (Throttling) ở chu kỳ tối thiểu 100ms (10 FPS)
 * theo chuẩn NFR-PERF-03 để bảo vệ luồng đồ họa Compose khỏi quá tải Recomposition.
 * Tích hợp xác thực sinh trắc học vân tay Class 3 mở tệp an toàn (SRS FR-MOB-02).
 */
class TransferViewModel(
    private val controlSessionUseCase: ControlSessionUseCase,
    private val transferCoordinator: TransferCoordinator? = null,
    private val openFileSecurelyUseCase: OpenFileSecurelyUseCase? = null,
    private val customScope: CoroutineScope? = null
) : ViewModel() {

    private val scope: CoroutineScope get() = customScope ?: viewModelScope

    private val _uiState = MutableStateFlow(TransferUiState())
    val uiState: StateFlow<TransferUiState> = _uiState.asStateFlow()

    private var progressJob: Job? = null
    private var lastEmitTime = 0L
    private var sessionStartTime = System.currentTimeMillis()

    companion object {
        const val THROTTLE_INTERVAL_MS = 100L
    }

    /**
     * Tự động kết nối và nạp trạng thái phiên truyền tải từ TransferCoordinator.
     */
    fun attachSession(sessionId: String, fileName: String = "", peerDeviceModel: String = "") {
        val activeSession = transferCoordinator?.getActiveSession(sessionId)
        if (activeSession != null) {
            _uiState.update {
                it.copy(
                    sessionId = sessionId,
                    fileName = if (fileName.isNotBlank()) fileName else it.fileName,
                    peerDeviceModel = if (peerDeviceModel.isNotBlank()) peerDeviceModel else it.peerDeviceModel,
                    transferredBytes = activeSession.bytesTransferred,
                    totalBytes = activeSession.totalBytes,
                    progressPercent = if (activeSession.totalBytes > 0)
                        (activeSession.bytesTransferred.toFloat() / activeSession.totalBytes.toFloat())
                    else 0f,
                    channel = activeSession.channel,
                    state = TransferEngineState.TRANSFERRING
                )
            }
        } else {
            _uiState.update {
                it.copy(
                    sessionId = sessionId,
                    fileName = if (fileName.isNotBlank()) fileName else it.fileName,
                    peerDeviceModel = if (peerDeviceModel.isNotBlank()) peerDeviceModel else it.peerDeviceModel
                )
            }
        }
    }

    fun setPeerDeviceModel(model: String) {
        _uiState.update { it.copy(peerDeviceModel = model) }
    }

    fun setCompletedFilePath(path: String) {
        _uiState.update { it.copy(completedFilePath = path) }
    }

    /**
     * Khởi tạo trạng thái phiên nhận tệp từ thông tin mã QR trước khi bắt đầu tải.
     */
    fun initTransferSession(
        sessionId: String,
        fileName: String,
        totalBytes: Long,
        peerDeviceModel: String,
        channel: TransferChannel
    ) {
        sessionStartTime = System.currentTimeMillis()
        lastEmitTime = 0L
        _uiState.update {
            it.copy(
                sessionId = sessionId,
                fileName = fileName,
                peerDeviceModel = peerDeviceModel,
                totalBytes = totalBytes,
                transferredBytes = 0L,
                progressPercent = 0f,
                speedBytesPerSec = 0L,
                channel = channel,
                state = TransferEngineState.CONNECTING,
                errorMessage = null,
                errorCode = null
            )
        }
    }

    /**
     * Tạo TransferProgressCallback liên kết trực tiếp với động cơ truyền tải mạng LAN/Relay
     * để cập nhật thanh tiến trình theo thời gian thực (điều tiết 100ms NFR-PERF-03).
     */
    fun createTransferCallback(
        sessionId: String,
        fileName: String = ""
    ): TransferProgressCallback {
        sessionStartTime = System.currentTimeMillis()
        lastEmitTime = 0L

        return object : TransferProgressCallback {
            override fun onProgress(bytesTransferred: Long, totalBytes: Long, speedBytesPerSec: Long) {
                val now = System.currentTimeMillis()
                if ((now - lastEmitTime) >= THROTTLE_INTERVAL_MS || (totalBytes > 0L && bytesTransferred >= totalBytes)) {
                    lastEmitTime = now
                    val elapsedSec = (now - sessionStartTime) / 1000.0
                    val speed = if (speedBytesPerSec > 0L) {
                        speedBytesPerSec
                    } else if (elapsedSec > 0 && bytesTransferred > 0) {
                        (bytesTransferred / elapsedSec).toLong()
                    } else {
                        0L
                    }

                    val safeTotal = if (totalBytes > 0L) totalBytes else _uiState.value.totalBytes
                    val remainingBytes = (safeTotal - bytesTransferred).coerceAtLeast(0L)
                    val eta = if (speed > 0L) remainingBytes / speed else 0L
                    val progressPercent = if (safeTotal > 0L) {
                        (bytesTransferred.toFloat() / safeTotal.toFloat()).coerceIn(0f, 1f)
                    } else {
                        0f
                    }

                    _uiState.update { current ->
                        current.copy(
                            sessionId = sessionId,
                            fileName = fileName.ifBlank { current.fileName },
                            transferredBytes = bytesTransferred,
                            totalBytes = safeTotal,
                            progressPercent = progressPercent,
                            speedBytesPerSec = speed,
                            state = TransferEngineState.TRANSFERRING,
                            etaSeconds = eta
                        )
                    }
                }
            }

            override fun onStateChanged(newState: TransferEngineState) {
                _uiState.update { current ->
                    current.copy(
                        sessionId = sessionId,
                        fileName = fileName.ifBlank { current.fileName },
                        state = newState
                    )
                }
            }

            override fun onSasGenerated(sasCode: String) {
                _uiState.update { it.copy(sasCode = sasCode) }
            }

            override fun onCompleted(docId: String, finalSha256: String) {
                _uiState.update { current ->
                    current.copy(
                        sessionId = sessionId,
                        fileName = fileName.ifBlank { current.fileName },
                        transferredBytes = current.totalBytes,
                        progressPercent = 1f,
                        state = TransferEngineState.COMPLETED
                    )
                }
            }

            override fun onError(errorCode: String, message: String) {
                val friendlyMessage = when {
                    message.contains("CANCELED", ignoreCase = true) || errorCode == "0x102" || message.contains("CANCEL", ignoreCase = true) ->
                        "Người gửi đã chủ động dừng phiên truyền này."
                    errorCode == ErrorCode.ERR_RELAY_UNAVAILABLE || message.contains("Relay", ignoreCase = true) ->
                        "Mất kết nối với máy chủ chuyển tiếp Relay."
                    else -> message
                }
                _uiState.update { current ->
                    current.copy(
                        sessionId = sessionId,
                        state = TransferEngineState.FAILED,
                        errorCode = errorCode,
                        errorMessage = friendlyMessage
                    )
                }
            }
        }
    }

    /**
     * Thông báo phiên tải tệp đã hoàn tất thành công và lưu đường dẫn tệp.
     */
    fun onTransferFinished(file: File) {
        _uiState.update { current ->
            current.copy(
                state = TransferEngineState.COMPLETED,
                fileName = file.name,
                transferredBytes = if (current.totalBytes > 0L) current.totalBytes else file.length(),
                totalBytes = if (current.totalBytes > 0L) current.totalBytes else file.length(),
                progressPercent = 1f,
                completedFilePath = file.absolutePath
            )
        }
    }

    /**
     * Bắt đầu phiên tải dữ liệu tệp từ thiết bị đối tác qua Coordinator.
     */
    fun startDownload(
        docId: String,
        fileName: String,
        fileSize: Long,
        teamId: String = "",
        requesterId: String = "",
        requesterName: String = "",
        publisherId: String = "",
        peerHost: String? = null,
        peerPort: Int = 0,
        relayUrl: String? = null,
        expectedSha256: String? = null,
        peerDeviceModel: String = "",
        onCompleted: (() -> Unit)? = null
    ): String {
        val coordinator = transferCoordinator ?: return "session_dl_$docId"
        val cleanRequesterId = requesterId.ifBlank { "client_${System.currentTimeMillis() % 100000}" }
        val sessionId = "session_dl_${docId}_$cleanRequesterId"
        val relayStreamUrl = relayUrl ?: NetworkAddressHelper.getDefaultRelayUrl(endpoint = "v1/stream")
        val effectivePeerHost = peerHost
        val effectivePeerPort = peerPort
        val channel = coordinator.selectTransferChannel(effectivePeerHost, effectivePeerPort)

        val session = TransferSession(
            sessionId = sessionId,
            docId = docId,
            peerMemberId = publisherId.ifBlank { peerHost ?: "peer_$docId" },
            direction = TransferDirection.INCOMING,
            channel = channel,
            status = SessionStatus.TRANSFERRING,
            totalBytes = fileSize,
            bytesTransferred = 0L
        )

        _uiState.update {
            it.copy(
                sessionId = sessionId,
                fileName = fileName,
                peerDeviceModel = peerDeviceModel,
                totalBytes = fileSize,
                transferredBytes = 0L,
                progressPercent = 0f,
                channel = channel,
                state = TransferEngineState.CONNECTING,
                errorMessage = null
            )
        }

        scope.launch {
            val isLan = channel == TransferChannel.DIRECT_LAN
            val lanPort = if (isLan) {
                com.teamshare.app.core.network.tls.TlsSocketFactory.findAvailablePort()
            } else {
                0
            }

            val progressFlow = coordinator.startTransfer(
                session = session,
                peerIp = peerHost,
                port = if (isLan) lanPort else peerPort,
                relayUrl = relayStreamUrl,
                streamId = sessionId,
                documentName = fileName,
                teamId = teamId,
                expectedSha256 = expectedSha256
            )

            observeProgress(progressFlow, fileName, onCompleted)
        }

        return sessionId
    }

    /**
     * Bắt đầu phiên gửi tệp dữ liệu.
     */
    fun startUpload(
        file: File,
        docId: String,
        fileName: String = file.name,
        targetPeerIp: String? = null,
        targetPort: Int = 0,
        relayUrl: String? = null,
        teamId: String = "",
        expectedSha256: String? = null,
        peerDeviceModel: String = "",
        onCompleted: (() -> Unit)? = null
    ): String {
        val coordinator = transferCoordinator ?: return "session_ul_$docId"
        val channel = coordinator.selectTransferChannel(targetPeerIp, targetPort)
        val sessionId = "session_ul_${docId}_${System.currentTimeMillis() % 100000}"
        val relayStreamUrl = relayUrl ?: NetworkAddressHelper.getDefaultRelayUrl(endpoint = "v1/stream")

        val session = TransferSession(
            sessionId = sessionId,
            docId = docId,
            peerMemberId = targetPeerIp ?: "relay",
            direction = TransferDirection.OUTGOING,
            channel = channel,
            status = SessionStatus.TRANSFERRING,
            totalBytes = file.length(),
            bytesTransferred = 0L
        )

        _uiState.update {
            it.copy(
                sessionId = sessionId,
                fileName = fileName,
                peerDeviceModel = peerDeviceModel,
                totalBytes = file.length(),
                transferredBytes = 0L,
                progressPercent = 0f,
                channel = channel,
                state = TransferEngineState.CONNECTING,
                errorMessage = null
            )
        }

        scope.launch {
            val progressFlow = coordinator.startTransfer(
                session = session,
                file = file,
                peerIp = targetPeerIp,
                port = targetPort,
                relayUrl = relayStreamUrl,
                streamId = sessionId,
                documentName = fileName,
                teamId = teamId,
                expectedSha256 = expectedSha256
            )

            observeProgress(progressFlow, fileName, onCompleted)
        }

        return sessionId
    }

    fun observeProgress(
        progressFlow: Flow<TransferProgress>,
        fileName: String,
        onCompleted: (() -> Unit)? = null
    ) {
        progressJob?.cancel()
        sessionStartTime = System.currentTimeMillis()
        lastEmitTime = 0L

        progressJob = scope.launch {
            progressFlow.collect { progress ->
                val now = System.currentTimeMillis()
                val isTerminal = progress.isCompleted || progress.isFailed || progress.state == TransferEngineState.CANCELLED

                if (isTerminal || (now - lastEmitTime) >= THROTTLE_INTERVAL_MS) {
                    lastEmitTime = now
                    val elapsedSec = (now - sessionStartTime) / 1000.0
                    val speed = if (elapsedSec > 0 && progress.bytesTransferred > 0) {
                        (progress.bytesTransferred / elapsedSec).toLong()
                    } else {
                        0L
                    }

                    val remainingBytes = (progress.totalBytes - progress.bytesTransferred).coerceAtLeast(0L)
                    val eta = if (speed > 0L) remainingBytes / speed else 0L

                    _uiState.update { current ->
                        current.copy(
                            sessionId = progress.sessionId,
                            fileName = fileName,
                            transferredBytes = progress.bytesTransferred,
                            totalBytes = progress.totalBytes,
                            progressPercent = progress.progressPercent,
                            speedBytesPerSec = if (progress.speedBytesPerSec > 0L) progress.speedBytesPerSec else speed,
                            channel = progress.channel,
                            state = progress.state,
                            sasCode = progress.sasCode,
                            errorCode = progress.errorCode,
                            errorMessage = progress.errorMessage,
                            etaSeconds = eta
                        )
                    }

                    if (progress.isCompleted) {
                        onCompleted?.invoke()
                    }
                }
            }
        }
    }

    /**
     * Mở tệp an toàn qua FileProvider sau khi xác thực sinh trắc học vân tay Class 3 thành công.
     */
    fun openFileSecurely(
        activity: FragmentActivity,
        file: File,
        onResult: (Boolean, String?) -> Unit
    ) {
        val useCase = openFileSecurelyUseCase
        if (useCase == null) {
            onResult(false, "Tính năng xác thực bảo mật chưa được cấu hình")
            return
        }

        scope.launch {
            try {
                val result = useCase.execute(file = file, activity = activity)
                result.onSuccess { intent ->
                    activity.startActivity(intent)
                    onResult(true, null)
                }.onFailure { err ->
                    onResult(false, err.message ?: "Xác thực sinh trắc học thất bại")
                }
            } catch (e: Exception) {
                onResult(false, e.message ?: "Xác thực sinh trắc học thất bại")
            }
        }
    }

    /**
     * Mở tệp trực tiếp bằng ứng dụng mặc định qua FileProvider (không bắt buộc vân tay).
     */
    fun openFileDirectly(
        activity: FragmentActivity,
        file: File,
        onResult: (Boolean, String?) -> Unit
    ) {
        val useCase = openFileSecurelyUseCase
        if (useCase == null) {
            onResult(false, "Tính năng mở tệp chưa được cấu hình")
            return
        }

        scope.launch {
            try {
                val result = useCase.execute(file = file, requireBiometric = false, activity = activity)
                result.onSuccess { intent ->
                    activity.startActivity(intent)
                    onResult(true, null)
                }.onFailure { err ->
                    onResult(false, err.message ?: "Không thể mở tệp")
                }
            } catch (e: Exception) {
                onResult(false, e.message ?: "Không thể mở tệp")
            }
        }
    }

    /**
     * Xuất tệp từ Sandbox ra thư mục Tải về (Downloads/TeamShare/) của thiết bị.
     */
    fun exportFileToDownloads(
        context: android.content.Context,
        file: File,
        onResult: (Boolean, String?) -> Unit
    ) {
        scope.launch(Dispatchers.IO) {
            try {
                if (!file.exists()) {
                    withContext(Dispatchers.Main) { onResult(false, "Tệp nguồn không tồn tại") }
                    return@launch
                }

                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    val contentValues = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                        put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "${android.os.Environment.DIRECTORY_DOWNLOADS}/TeamShare")
                    }
                    val resolver = context.contentResolver
                    val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                    if (uri != null) {
                        resolver.openOutputStream(uri)?.use { out ->
                            file.inputStream().use { input -> input.copyTo(out) }
                        }
                        withContext(Dispatchers.Main) {
                            onResult(true, "Đã lưu vào thư mục Downloads/TeamShare/${file.name}")
                        }
                    } else {
                        withContext(Dispatchers.Main) { onResult(false, "Không thể tạo tệp trong thư mục Downloads") }
                    }
                } else {
                    val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                    val teamShareDir = File(downloadsDir, "TeamShare").apply { mkdirs() }
                    val destFile = File(teamShareDir, file.name)
                    file.copyTo(destFile, overwrite = true)
                    withContext(Dispatchers.Main) {
                        onResult(true, "Đã lưu vào ${destFile.absolutePath}")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onResult(false, "Lỗi khi lưu tệp: ${e.message}")
                }
            }
        }
    }

    fun pauseTransfer(sessionId: String) {
        scope.launch {
            controlSessionUseCase(sessionId, SessionCmd.PAUSE)
            _uiState.update { it.copy(state = TransferEngineState.PAUSED) }
        }
    }

    fun resumeTransfer(sessionId: String) {
        scope.launch {
            controlSessionUseCase(sessionId, SessionCmd.RESUME)
            _uiState.update { it.copy(state = TransferEngineState.TRANSFERRING) }
        }
    }

    fun cancelTransfer(sessionId: String) {
        scope.launch {
            controlSessionUseCase(sessionId, SessionCmd.CANCEL)
            _uiState.update { it.copy(state = TransferEngineState.CANCELLED) }
            progressJob?.cancel()
        }
    }

    fun pauseSession(sessionId: String) = pauseTransfer(sessionId)
    fun resumeSession(sessionId: String) = resumeTransfer(sessionId)
    fun cancelSession(sessionId: String) = cancelTransfer(sessionId)

    override fun onCleared() {
        super.onCleared()
        progressJob?.cancel()
    }
}
