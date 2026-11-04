package com.teamshare.app.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.teamshare.app.core.transfer.TransferCoordinator
import com.teamshare.app.core.transfer.lan.TransferEngineState
import com.teamshare.app.domain.model.SessionCmd
import com.teamshare.app.domain.model.TransferProgress
import com.teamshare.app.domain.usecase.transfer.ControlSessionUseCase
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
    fun observeProgress(progressFlow: Flow<TransferProgress>, fileName: String = "") {
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
            }
        }
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
