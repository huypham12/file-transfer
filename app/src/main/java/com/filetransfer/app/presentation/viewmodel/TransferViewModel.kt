package com.filetransfer.app.presentation.viewmodel

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filetransfer.app.core.platform.network.NetworkAddressHelper
import com.filetransfer.app.core.transfer.TransferCoordinator
import com.filetransfer.app.core.transfer.lan.TransferEngineState
import com.filetransfer.app.domain.model.SessionCmd
import com.filetransfer.app.domain.model.SessionStatus
import com.filetransfer.app.domain.model.TransferChannel
import com.filetransfer.app.domain.model.TransferDirection
import com.filetransfer.app.domain.model.TransferProgress
import com.filetransfer.app.domain.model.TransferSession
import com.filetransfer.app.domain.usecase.platform.OpenFileSecurelyUseCase
import com.filetransfer.app.domain.usecase.transfer.ControlSessionUseCase
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
import com.filetransfer.app.core.storage.saf.ExportOverwriteMode
import com.filetransfer.app.core.storage.saf.PublicStorageExporter
import com.filetransfer.app.core.storage.saf.SafeFileDeleter
import com.filetransfer.app.core.transfer.lan.TransferProgressCallback
import com.filetransfer.app.core.transfer.relay.RelayClientEngine
import com.filetransfer.app.domain.model.ErrorCode

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
    private val biometricAuthManager: com.filetransfer.app.core.security.biometric.IBiometricAuthManager? = null,
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
                com.filetransfer.app.core.network.tls.TlsSocketFactory.findAvailablePort()
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

        val prefs = com.filetransfer.app.core.platform.preference.AppPreferencesManager.getInstance(activity)
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

    fun dismissDuplicateDialog() {
        _uiState.update { it.copy(showSingleDuplicateDialog = false, duplicateFileToConfirm = null, showBatchDuplicateDialog = false) }
    }

    fun dismissCleanupDialog() {
        _uiState.update { it.copy(showCleanupSuggestionDialog = false) }
    }

    fun clearFeedbackMessage() {
        _uiState.update { it.copy(exportFeedbackMessage = null) }
    }

    /**
     * Xuất tệp đơn lẻ có kiểm tra chống trùng lặp (Smart Idempotency Check).
     * Nếu tệp đã có và chưa xác nhận forceOverwrite, kích hoạt Dialog hỏi người dùng.
     */
    fun exportSingleFileSmart(
        context: android.content.Context,
        file: File,
        forceOverwrite: Boolean = false
    ) {
        scope.launch(Dispatchers.IO) {
            val exporter = PublicStorageExporter(context)
            if (!forceOverwrite && exporter.checkFileExistsInDownloads(file.name)) {
                withContext(Dispatchers.Main) {
                    _uiState.update {
                        it.copy(
                            duplicateFileToConfirm = ReceivedFileInfo(file.name, file.length(), file.absolutePath),
                            showSingleDuplicateDialog = true
                        )
                    }
                }
                return@launch
            }

            withContext(Dispatchers.Main) {
                _uiState.update { it.copy(showSingleDuplicateDialog = false, singleFileStorageState = FileStorageState.EXPORTING) }
            }

            val mode = if (forceOverwrite) ExportOverwriteMode.OVERWRITE else ExportOverwriteMode.CREATE_NEW_COPY
            val result = exporter.exportSingleFile(file, overwriteMode = mode)

            withContext(Dispatchers.Main) {
                result.onSuccess { path ->
                    _uiState.update {
                        it.copy(
                            singleFileStorageState = FileStorageState.SAVED_TO_PUBLIC,
                            cleanableBytes = file.length(),
                            showCleanupSuggestionDialog = true,
                            exportFeedbackMessage = "Đã lưu an toàn vào $path"
                        )
                    }
                }.onFailure { err ->
                    _uiState.update {
                        it.copy(
                            singleFileStorageState = FileStorageState.SANDBOX_ONLY,
                            exportFeedbackMessage = "Lỗi khi lưu tệp: ${err.message}"
                        )
                    }
                }
            }
        }
    }

    /**
     * Xuất 1 tệp con trong danh sách nhiều tệp có kiểm tra trùng lặp.
     */
    fun exportChildFileSmart(
        context: android.content.Context,
        item: ReceivedFileInfo,
        forceOverwrite: Boolean = false
    ) {
        scope.launch(Dispatchers.IO) {
            val exporter = PublicStorageExporter(context)
            if (!forceOverwrite && exporter.checkFileExistsInDownloads(item.name)) {
                withContext(Dispatchers.Main) {
                    _uiState.update {
                        it.copy(
                            duplicateFileToConfirm = item,
                            showSingleDuplicateDialog = true
                        )
                    }
                }
                return@launch
            }

            val file = File(item.absolutePath)
            if (!file.exists()) {
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(exportFeedbackMessage = "Tệp nguồn không tồn tại") }
                }
                return@launch
            }

            val mode = if (forceOverwrite) ExportOverwriteMode.OVERWRITE else ExportOverwriteMode.CREATE_NEW_COPY
            val result = exporter.exportSingleFile(file, overwriteMode = mode)

            withContext(Dispatchers.Main) {
                _uiState.update { state ->
                    val updatedList = state.receivedFileList.map {
                        if (it.absolutePath == item.absolutePath) {
                            it.copy(storageState = if (result.isSuccess) FileStorageState.SAVED_TO_PUBLIC else FileStorageState.SANDBOX_ONLY)
                        } else it
                    }
                    state.copy(
                        receivedFileList = updatedList,
                        showSingleDuplicateDialog = false,
                        duplicateFileToConfirm = null,
                        exportFeedbackMessage = result.fold(
                            onSuccess = { "Đã lưu ${item.name} vào Downloads" },
                            onFailure = { "Lỗi lưu ${item.name}: ${it.message}" }
                        )
                    )
                }
            }
        }
    }

    /**
     * Xuất tất cả các tệp trong danh sách nhiều tệp (Batch):
     * Tự động phát hiện các tệp đã có trong Downloads.
     */
    fun exportAllBatchSmart(
        context: android.content.Context,
        targetFolder: File,
        forceOverwriteAll: Boolean = false,
        onlyNewFiles: Boolean = false
    ) {
        scope.launch(Dispatchers.IO) {
            val exporter = PublicStorageExporter(context)
            val currentList = _uiState.value.receivedFileList

            val duplicates = currentList.filter { exporter.checkFileExistsInDownloads(it.name) }
            if (duplicates.isNotEmpty() && !forceOverwriteAll && !onlyNewFiles) {
                withContext(Dispatchers.Main) {
                    _uiState.update {
                        it.copy(
                            showBatchDuplicateDialog = true,
                            pendingBatchDuplicates = duplicates
                        )
                    }
                }
                return@launch
            }

            val filesToExport = if (onlyNewFiles) {
                currentList.filterNot { exporter.checkFileExistsInDownloads(it.name) }
            } else {
                currentList
            }

            var successCount = 0
            var totalExportedBytes = 0L

            for (item in filesToExport) {
                val f = File(item.absolutePath)
                if (f.exists()) {
                    val mode = if (forceOverwriteAll) ExportOverwriteMode.OVERWRITE else ExportOverwriteMode.CREATE_NEW_COPY
                    val res = exporter.exportSingleFile(f, overwriteMode = mode)
                    if (res.isSuccess) {
                        successCount++
                        totalExportedBytes += f.length()
                        withContext(Dispatchers.Main) {
                            _uiState.update { state ->
                                val updated = state.receivedFileList.map {
                                    if (it.absolutePath == item.absolutePath) it.copy(storageState = FileStorageState.SAVED_TO_PUBLIC) else it
                                }
                                state.copy(receivedFileList = updated)
                            }
                        }
                    }
                }
            }

            withContext(Dispatchers.Main) {
                _uiState.update {
                    it.copy(
                        showBatchDuplicateDialog = false,
                        pendingBatchDuplicates = emptyList(),
                        cleanableBytes = totalExportedBytes,
                        showCleanupSuggestionDialog = (successCount > 0),
                        exportFeedbackMessage = "Đã lưu thành công $successCount/${filesToExport.size} tệp vào Downloads"
                    )
                }
            }
        }
    }

    /**
     * Dọn dẹp an toàn các tệp Sandbox đã được xuất ra Downloads để giải phóng bộ nhớ.
     */
    fun cleanupExportedSandboxFiles(context: android.content.Context) {
        scope.launch(Dispatchers.IO) {
            val state = _uiState.value
            var cleanedCount = 0

            if (state.isDirectoryOrBatch) {
                val savedItems = state.receivedFileList.filter { it.storageState == FileStorageState.SAVED_TO_PUBLIC }
                for (item in savedItems) {
                    val f = File(item.absolutePath)
                    if (f.exists()) {
                        SafeFileDeleter.wipeAndSanitizeFile(f)
                        cleanedCount++
                    }
                }
                withContext(Dispatchers.Main) {
                    _uiState.update { s ->
                        val updated = s.receivedFileList.map {
                            if (it.storageState == FileStorageState.SAVED_TO_PUBLIC) it.copy(storageState = FileStorageState.SANDBOX_CLEANED) else it
                        }
                        s.copy(
                            receivedFileList = updated,
                            showCleanupSuggestionDialog = false,
                            cleanableBytes = 0L,
                            exportFeedbackMessage = "Đã giải phóng bộ nhớ cho $cleanedCount tệp trong ứng dụng"
                        )
                    }
                }
            } else {
                val filePath = state.completedFilePath
                val targetFile = if (!filePath.isNullOrBlank()) File(filePath) else File(context.filesDir, "${com.filetransfer.app.core.constant.AppConstants.StorageDirectory.TRANSFERS}/${com.filetransfer.app.core.constant.AppConstants.StorageDirectory.COMPLETED}/${state.fileName}")
                if (targetFile.exists()) {
                    SafeFileDeleter.wipeAndSanitizeFile(targetFile)
                }
                withContext(Dispatchers.Main) {
                    _uiState.update {
                        it.copy(
                            singleFileStorageState = FileStorageState.SANDBOX_CLEANED,
                            showCleanupSuggestionDialog = false,
                            cleanableBytes = 0L,
                            exportFeedbackMessage = "Đã giải phóng bộ nhớ bản sao tạm trong ứng dụng"
                        )
                    }
                }
            }
        }
    }

    /**
     * Xuất tệp từ Sandbox ra thư mục Tải về (Downloads/filetransfer/) của thiết bị.
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

                val exporter = com.filetransfer.app.core.storage.saf.PublicStorageExporter(context)
                if (file.isDirectory) {
                    val result = exporter.exportFolder(file)
                    withContext(Dispatchers.Main) {
                        result.onSuccess { count ->
                            onResult(true, "Đã lưu $count tệp vào Downloads/filetransfer/${file.name}")
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
