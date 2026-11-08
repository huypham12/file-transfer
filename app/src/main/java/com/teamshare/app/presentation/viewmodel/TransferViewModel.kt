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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
