package com.teamshare.app.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.teamshare.app.core.network.sync.PeerSyncManager
import com.teamshare.app.core.platform.network.NetworkAddressHelper
import com.teamshare.app.core.transfer.TransferCoordinator
import com.teamshare.app.core.transfer.lan.TransferEngineState
import com.teamshare.app.domain.model.SessionCmd
import com.teamshare.app.domain.model.SessionStatus
import com.teamshare.app.domain.model.TransferChannel
import com.teamshare.app.domain.model.TransferDirection
import com.teamshare.app.domain.model.TransferProgress
import com.teamshare.app.domain.model.TransferSession
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
 */
class TransferViewModel(
    private val controlSessionUseCase: ControlSessionUseCase,
    private val transferCoordinator: TransferCoordinator? = null,
    private val peerSyncManager: PeerSyncManager? = null,
    private val customScope: CoroutineScope? = null
) : ViewModel() {

    private val scope: CoroutineScope get() = customScope ?: viewModelScope

    private val _uiState = MutableStateFlow(TransferUiState())
    val uiState: StateFlow<TransferUiState> = _uiState.asStateFlow()

    private var progressJob: Job? = null
    private var lastEmitTime = 0L

    companion object {
        const val THROTTLE_INTERVAL_MS = 100L
    }

    /**
     * Tự động kết nối và nạp trạng thái phiên truyền tải từ TransferCoordinator (P3).
     */
    fun attachSession(sessionId: String, fileName: String = "") {
        val activeSession = transferCoordinator?.getActiveSession(sessionId)
        if (activeSession != null) {
            _uiState.update {
                it.copy(
                    sessionId = sessionId,
                    fileName = if (fileName.isNotBlank()) fileName else it.fileName,
                    transferredBytes = activeSession.bytesTransferred,
                    totalBytes = activeSession.totalBytes,
                    progressPercent = if (activeSession.totalBytes > 0)
                        (activeSession.bytesTransferred.toFloat() / activeSession.totalBytes.toFloat())
                    else 0f,
                    channel = activeSession.channel,
                    state = TransferEngineState.TRANSFERRING
                )
            }
        } else if (_uiState.value.sessionId != sessionId) {
            _uiState.update {
                it.copy(
                    sessionId = sessionId,
                    fileName = if (fileName.isNotBlank()) fileName else it.fileName
                )
            }
        }
    }

    /**
     * Lắng nghe dòng dữ liệu tiến độ thời gian thực với cơ chế điều tiết 100ms.
     */
    fun observeProgress(
        progressFlow: Flow<TransferProgress>,
        fileName: String = "",
        onCompleted: (() -> Unit)? = null
    ) {
        progressJob?.cancel()
        lastEmitTime = 0L
        progressJob = scope.launch {
            progressFlow.collect { progress ->
                val now = System.currentTimeMillis()
                val isTerminalOrCritical = progress.isCompleted ||
                        progress.isFailed ||
                        progress.isPaused ||
                        progress.state == TransferEngineState.CANCELLED ||
                        progress.state == TransferEngineState.IDLE

                if (isTerminalOrCritical || (now - lastEmitTime >= THROTTLE_INTERVAL_MS)) {
                    lastEmitTime = now
                    _uiState.update {
                        TransferUiState.fromProgress(
                            progress = progress,
                            fileName = if (fileName.isNotBlank()) fileName else it.fileName
                        )
                    }
                }

                if (progress.isCompleted) {
                    onCompleted?.invoke()
                }
            }
        }
    }

    /**
     * Khởi chạy phiên tải tệp từ một thành viên trong nhóm (UC-11, UC-12, UC-13).
     */
    fun startDownload(
        docId: String,
        fileName: String,
        fileSize: Long,
        teamId: String,
        requesterId: String = "",
        requesterName: String = "",
        publisherId: String = "",
        peerHost: String? = null,
        peerPort: Int = 0,
        relayUrl: String? = null,
        expectedSha256: String? = null,
        onCompleted: (() -> Unit)? = null
    ): String {
        val coordinator = transferCoordinator ?: return "session_dl_$docId"
        val cleanRequesterId = requesterId.ifBlank { "client_${System.currentTimeMillis() % 100000}" }
        val cleanRequesterName = requesterName.ifBlank { peerSyncManager?.myMemberName ?: "Thành viên" }
        val sessionId = "session_dl_${docId}_$cleanRequesterId"
        val relayStreamUrl = relayUrl
            ?: peerSyncManager?.getRelayUrlForTeam(teamId)?.replace("v1/sync", "v1/stream")
            ?: NetworkAddressHelper.getDefaultRelayUrl(endpoint = "v1/stream")
        val targetPeer = publisherId.ifBlank { peerHost ?: "" }
        val peerInfo = peerSyncManager?.getPeerHostAndPort(targetPeer)
        val effectivePeerHost = peerInfo?.first ?: peerHost
        val effectivePeerPort = peerInfo?.second ?: 0
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
                totalBytes = fileSize,
                transferredBytes = 0L,
                progressPercent = 0f,
                channel = channel,
                state = TransferEngineState.CONNECTING,
                errorMessage = null
            )
        }

        // Bắt đầu luồng kiểm tra Probe và Bắt tay xác nhận ACK trước khi mở Relay Bridge hoặc LAN Socket
        scope.launch {
            val targetPeer = publisherId.ifBlank { peerHost ?: "" }
            val isLan = channel == TransferChannel.DIRECT_LAN
            val lanPort = if (isLan) {
                com.teamshare.app.core.network.tls.TlsSocketFactory.findAvailablePort()
            } else {
                0
            }
            val localIp = if (isLan) NetworkAddressHelper.getLocalIpv4Address() else null
            val preferredChannelStr = if (isLan) "DIRECT_LAN" else "RELAY"

            if (targetPeer.isNotBlank() && peerSyncManager != null) {
                // 1. Pre-flight Probe: Thăm dò tính khả dụng của file nguồn
                val probeResult = peerSyncManager.probeFileAvailability(
                    teamId = teamId,
                    docId = docId,
                    targetPeerId = targetPeer,
                    requesterId = cleanRequesterId,
                    timeoutMs = 3000L
                )
                val probe = probeResult.getOrNull()
                if (probe?.status == "FILE_MISSING") {
                    _uiState.update {
                        it.copy(
                            state = TransferEngineState.FAILED,
                            errorMessage = "Tệp nguồn không còn tồn tại trên máy người chia sẻ."
                        )
                    }
                    return@launch
                }

                // 2. Bắt tay 2 pha có ACK: Gửi FILE_REQUEST và chờ FILE_REQUEST_ACK
                val ackResult = peerSyncManager.requestFileDownloadWithAck(
                    teamId = teamId,
                    docId = docId,
                    targetPeerId = targetPeer,
                    streamId = sessionId,
                    requesterId = cleanRequesterId,
                    requesterName = cleanRequesterName,
                    preferredChannel = preferredChannelStr,
                    requesterHost = localIp,
                    requesterPort = lanPort
                )
                if (ackResult.isFailure) {
                    _uiState.update {
                        it.copy(
                            state = TransferEngineState.FAILED,
                            errorMessage = ackResult.exceptionOrNull()?.message
                                ?: "Không nhận được phản hồi xác nhận từ người chia sẻ tệp"
                        )
                    }
                    return@launch
                }
            } else {
                // Dự phòng nếu không có publisherId: phát FILE_REQUEST broadcast thông thường
                peerSyncManager?.requestFileDownload(
                    teamId = teamId,
                    docId = docId,
                    streamId = sessionId,
                    requesterId = cleanRequesterId,
                    requesterName = cleanRequesterName,
                    preferredChannel = preferredChannelStr,
                    requesterHost = localIp,
                    requesterPort = lanPort,
                    relayUrl = peerSyncManager.getRelayUrlForTeam(teamId)
                        ?: NetworkAddressHelper.getDefaultRelayUrl(endpoint = "v1/sync")
                )
            }

            // 3. Khởi chạy luồng truyền tải thực tế sau khi bắt tay thành công
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
     * Tạm dừng phiên truyền tải (UC-15 - FR-TRF-05).
     */
    fun pauseSession(sessionId: String) {
        scope.launch {
            controlSessionUseCase(sessionId, SessionCmd.PAUSE).collect { result ->
                result.onFailure { error ->
                    _uiState.update { it.copy(errorMessage = error.message ?: "Không thể tạm dừng phiên") }
                }
            }
        }
    }

    /**
     * Tiếp tục phiên truyền tải từ Checkpoint đã lưu (UC-15 - FR-TRF-05).
     */
    fun resumeSession(sessionId: String) {
        scope.launch {
            controlSessionUseCase(sessionId, SessionCmd.RESUME).collect { result ->
                result.onFailure { error ->
                    _uiState.update { it.copy(errorMessage = error.message ?: "Không thể tiếp tục phiên") }
                }
            }
        }
    }

    /**
     * Hủy bỏ phiên truyền tải (UC-15 - FR-TRF-05).
     */
    fun cancelSession(sessionId: String) {
        scope.launch {
            controlSessionUseCase(sessionId, SessionCmd.CANCEL).collect { result ->
                result.onFailure { error ->
                    _uiState.update { it.copy(errorMessage = error.message ?: "Không thể hủy phiên") }
                }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun reset() {
        progressJob?.cancel()
        _uiState.value = TransferUiState()
    }

    override fun onCleared() {
        super.onCleared()
        progressJob?.cancel()
    }
}
