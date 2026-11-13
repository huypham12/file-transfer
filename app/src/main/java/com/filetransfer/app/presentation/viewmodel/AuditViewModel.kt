package com.filetransfer.app.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filetransfer.app.domain.model.AuditLog
import com.filetransfer.app.domain.usecase.audit.QuerySecurityAuditUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel quản lý truy vấn và hiển thị nhật ký kiểm toán an ninh cục bộ (FR-AUD-01).
 * Hỗ trợ bộ lọc mức độ nghiêm trọng và tìm kiếm sự kiện thời gian thực.
 */
class AuditViewModel(
    private val querySecurityAuditUseCase: QuerySecurityAuditUseCase,
    private val customScope: CoroutineScope? = null
) : ViewModel() {

    private val scope: CoroutineScope get() = customScope ?: viewModelScope

    private val _uiState = MutableStateFlow(AuditUiState())
    val uiState: StateFlow<AuditUiState> = _uiState.asStateFlow()

    private var auditJob: Job? = null

    /**
     * Tải và liên tục quan sát nhật ký kiểm toán an ninh sắp xếp theo thời gian mới nhất.
     */
    fun loadLogs(teamId: String = "") {
        auditJob?.cancel()
        auditJob = scope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            querySecurityAuditUseCase(teamId).collect { logs ->
                _uiState.update { current ->
                    current.copy(
                        logs = logs,
                        filteredLogs = applyFilter(logs, current.filterSeverity, current.searchQuery),
                        isLoading = false,
                        errorMessage = null
                    )
                }
            }
        }
    }

    /**
     * Lọc danh sách theo mức độ nghiêm trọng (ALL, INFO, WARNING, CRITICAL).
     */
    fun filterBySeverity(severity: String) {
        _uiState.update { current ->
            current.copy(
                filterSeverity = severity,
                filteredLogs = applyFilter(current.logs, severity, current.searchQuery)
            )
        }
    }

    /**
     * Tìm kiếm sự kiện theo từ khóa (chi tiết, actor, loại sự kiện).
     */
    fun search(query: String) {
        _uiState.update { current ->
            current.copy(
                searchQuery = query,
                filteredLogs = applyFilter(current.logs, current.filterSeverity, query)
            )
        }
    }

    private fun applyFilter(logs: List<AuditLog>, severity: String, query: String): List<AuditLog> {
        return logs.filter { log ->
            val matchSeverity = if (severity == "ALL") true else log.severity.name.equals(severity, ignoreCase = true)
            val matchQuery = if (query.isBlank()) true else {
                log.details.contains(query, ignoreCase = true) ||
                    log.actorDevice.contains(query, ignoreCase = true) ||
                    log.eventType.name.contains(query, ignoreCase = true) ||
                    (log.sessionId?.contains(query, ignoreCase = true) == true)
            }
            matchSeverity && matchQuery
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
