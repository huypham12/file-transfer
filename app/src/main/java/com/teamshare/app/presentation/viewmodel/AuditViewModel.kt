package com.teamshare.app.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.teamshare.app.domain.usecase.audit.QuerySecurityAuditUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel quản lý truy vấn và hiển thị dòng thời gian nhật ký kiểm toán nhóm (UC-20 - FR-AUD-01).
 */
class AuditViewModel(
    private val querySecurityAuditUseCase: QuerySecurityAuditUseCase,
    private val customScope: CoroutineScope? = null,
    private val peerSyncManager: com.teamshare.app.core.network.sync.PeerSyncManager? = null
) : ViewModel() {

    private val scope: CoroutineScope get() = customScope ?: viewModelScope

    private val _uiState = MutableStateFlow(AuditUiState())
    val uiState: StateFlow<AuditUiState> = _uiState.asStateFlow()

    private var auditJob: Job? = null

    /**
     * Tải và liên tục quan sát nhật ký kiểm toán của nhóm sắp xếp theo thời gian mới nhất.
     */
    fun loadLogs(teamId: String) {
        auditJob?.cancel()
        auditJob = scope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            launch {
                try {
                    val maxTimestamp = _uiState.value.logs.maxOfOrNull { it.timestamp } ?: 0L
                    peerSyncManager?.queryAuditLogs(teamId, maxTimestamp)
                } catch (_: Exception) {}
            }
            querySecurityAuditUseCase(teamId).collect { logs ->
                _uiState.update {
                    it.copy(logs = logs, isLoading = false, errorMessage = null)
                }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    override fun onCleared() {
        super.onCleared()
        auditJob?.cancel()
    }
}
