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
    private val biometricAuthManager: com.teamshare.app.core.security.biometric.IBiometricAuthManager? = null,
    private val customScope: CoroutineScope? = null
) : ViewModel() {

    private val scope: CoroutineScope get() = customScope ?: viewModelScope

    private val _uiState = MutableStateFlow(TransferUiState())
    val uiState: StateFlow<TransferUiState> = _uiState.asStateFlow()

    private var progressJob: Job? = null
    private var lastEmitTime = 0L
    private var sessionStartTime = System.currentTimeMillis()
    private var pausedAccumulatedMs = 0L
    private var pauseStartTime = 0L

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
        pausedAccumulatedMs = 0L
        pauseStartTime = 0L
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
                averageSpeedBytesPerSec = 0L,
                activeDurationSeconds = 0.0,
                durationText = "",
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
                if (newState == TransferEngineState.PAUSED && pauseStartTime == 0L) {
                    pauseStartTime = System.currentTimeMillis()
                } else if (newState == TransferEngineState.TRANSFERRING && pauseStartTime > 0L) {
                    pausedAccumulatedMs += (System.currentTimeMillis() - pauseStartTime)
                    pauseStartTime = 0L
                }
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
                val safeTotal = _uiState.value.totalBytes
                val (activeSec, avgSpeed, durationFormatted) = calculateCompletionMetrics(safeTotal)
                _uiState.update { current ->
                    current.copy(
                        sessionId = sessionId,
                        fileName = fileName.ifBlank { current.fileName },
                        transferredBytes = current.totalBytes,
                        progressPercent = 1f,
                        state = TransferEngineState.COMPLETED,
                        durationText = durationFormatted,
                        activeDurationSeconds = activeSec,
                        averageSpeedBytesPerSec = avgSpeed
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
        val isDir = file.isDirectory
        val childFiles = if (isDir) {
            val list = mutableListOf<ReceivedFileInfo>()
            fun collectFiles(dir: File) {
                dir.listFiles()?.filterNot { it.name.startsWith(".") }?.forEach { f ->
                    if (f.isDirectory) {
                        collectFiles(f)
                    } else {
                        list.add(
                            ReceivedFileInfo(
                                name = f.name,
                                sizeBytes = f.length(),
                                absolutePath = f.absolutePath,
                                isDirectory = false
                            )
                        )
                    }
                }
            }
            collectFiles(file)
            list.sortedBy { it.name }
        } else {
            emptyList()
        }

        val safeTotal = if (_uiState.value.totalBytes > 0L) _uiState.value.totalBytes else file.length()
        val (activeSec, avgSpeed, durationFormatted) = calculateCompletionMetrics(safeTotal)

        _uiState.update { current ->
            current.copy(
                state = TransferEngineState.COMPLETED,
                fileName = file.name,
                transferredBytes = safeTotal,
                totalBytes = safeTotal,
                progressPercent = 1f,
                completedFilePath = file.absolutePath,
                isDirectoryOrBatch = isDir,
                receivedFileList = childFiles,
                durationText = durationFormatted,
                activeDurationSeconds = activeSec,
                averageSpeedBytesPerSec = avgSpeed
            )
        }
    }

    private fun calculateCompletionMetrics(currentTotalBytes: Long): Triple<Double, Long, String> {
        val now = System.currentTimeMillis()
        var accumulated = pausedAccumulatedMs
        if (pauseStartTime > 0L) {
            accumulated += (now - pauseStartTime)
        }
        val totalActiveMs = (now - sessionStartTime - accumulated).coerceAtLeast(100L)
        val activeSec = totalActiveMs / 1000.0
        val avgSpeed = if (activeSec > 0 && currentTotalBytes > 0) {
            (currentTotalBytes / activeSec).toLong()
        } else {
            0L
        }
        val durationFormatted = if (activeSec < 1.0) {
            String.format(java.util.Locale.US, "%.1f giây", activeSec)
        } else if (activeSec < 60) {
            String.format(java.util.Locale.US, "%.0f giây", activeSec)
        } else {
            val mins = (activeSec / 60).toLong()
            val secs = (activeSec % 60).toLong()
            "$mins phút $secs giây"
        }
        return Triple(activeSec, avgSpeed, durationFormatted)
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

        val prefs = com.teamshare.app.core.platform.preference.AppPreferencesManager.getInstance(activity)
        val isBiometricEnforced = prefs.requireBiometricForOpen.value
        val canAuth = biometricAuthManager?.canAuthenticate() ?: true
        val requireBiometric = isBiometricEnforced && canAuth

        scope.launch {
            try {
                val result = useCase.execute(file = file, requireBiometric = requireBiometric, activity = activity)
                result.onSuccess { intent ->
                    activity.startActivity(intent)
                    onResult(true, null)
                }.onFailure { err ->
                    val userFriendlyMsg = if (err.message?.contains("0x401") == true) {
                        "Xác thực danh tính không thành công hoặc đã bị hủy"
                    } else {
                        err.message ?: "Không thể mở tệp tin an toàn"
                    }
                    onResult(false, userFriendlyMsg)
                }
            } catch (e: Exception) {
                val userFriendlyMsg = if (e.message?.contains("0x401") == true) {
                    "Xác thực danh tính không thành công hoặc đã bị hủy"
                } else {
                    e.message ?: "Không thể mở tệp tin an toàn"
                }
                onResult(false, userFriendlyMsg)
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

                val exporter = com.teamshare.app.core.storage.saf.PublicStorageExporter(context)
                if (file.isDirectory) {
                    val result = exporter.exportFolder(file)
                    withContext(Dispatchers.Main) {
                        result.onSuccess { count ->
                            onResult(true, "Đã lưu $count tệp vào Downloads/TeamShare/${file.name}")
                        }.onFailure { err ->
                            onResult(false, "Lỗi khi lưu tệp vào Downloads: ${err.message}")
                        }
                    }
                } else {
                    val result = exporter.exportSingleFile(file)
                    withContext(Dispatchers.Main) {
                        result.onSuccess { path ->
                            onResult(true, "Đã lưu vào $path")
                        }.onFailure { err ->
                            onResult(false, "Lỗi khi lưu tệp: ${err.message}")
                        }
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
