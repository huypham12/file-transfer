package com.filetransfer.app.presentation.viewmodel

import com.filetransfer.app.data.local.db.dao.InMemoryAuditLogDao
import com.filetransfer.app.data.repository.AuditRepositoryImpl
import com.filetransfer.app.domain.model.AuditEventType
import com.filetransfer.app.domain.model.AuditLog
import com.filetransfer.app.domain.model.AuditSeverity
import com.filetransfer.app.domain.usecase.audit.QuerySecurityAuditUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuditViewModelTest {

    private lateinit var auditDao: InMemoryAuditLogDao
    private lateinit var auditRepository: AuditRepositoryImpl
    private lateinit var queryUseCase: QuerySecurityAuditUseCase
    private lateinit var viewModel: AuditViewModel

    @Before
    fun setUp() {
        auditDao = InMemoryAuditLogDao()
        auditRepository = AuditRepositoryImpl(auditDao)
        queryUseCase = QuerySecurityAuditUseCase(auditRepository)

        viewModel = AuditViewModel(
            querySecurityAuditUseCase = queryUseCase,
            customScope = CoroutineScope(Dispatchers.Unconfined)
        )
    }

    private suspend fun insertSampleLogs() {
        auditRepository.logEvent(
            AuditLog(
                sessionId = "sess_001",
                eventType = AuditEventType.SESSION_CREATED,
                actorDevice = "Xiaomi Redmi Turbo 4 Pro",
                severity = AuditSeverity.INFO,
                details = "Đã khởi tạo phiên phát chia sẻ 1-N"
            )
        )
        auditRepository.logEvent(
            AuditLog(
                sessionId = "sess_001",
                eventType = AuditEventType.SECURITY_ALERT,
                actorDevice = "Unknown Scanner",
                severity = AuditSeverity.SECURITY_ALERT,
                details = "0x103: Quét mã QR đã hết hạn hiệu lực"
            )
        )
        auditRepository.logEvent(
            AuditLog(
                sessionId = "sess_002",
                eventType = AuditEventType.TRANSFER_COMPLETED,
                actorDevice = "Samsung Galaxy S23",
                severity = AuditSeverity.WARNING,
                details = "Dung lượng bộ nhớ máy nhận gần đầy (còn dưới 500MB)"
            )
        )
    }

    @Test
    fun testLoadLogs_populatesLogsList() = runBlocking {
        insertSampleLogs()

        viewModel.loadLogs()

        val state = viewModel.uiState.value
        assertEquals(3, state.logs.size)
        assertEquals(3, state.filteredLogs.size)
        assertFalse(state.isLoading)
        assertNull(state.errorMessage)
    }

    @Test
    fun testFilterBySeverity_filtersCorrectly() = runBlocking {
        insertSampleLogs()
        viewModel.loadLogs()

        // Lọc WARNING
        viewModel.filterBySeverity("WARNING")
        var state = viewModel.uiState.value
        assertEquals("WARNING", state.filterSeverity)
        assertEquals(1, state.filteredLogs.size)
        assertEquals(AuditSeverity.WARNING, state.filteredLogs[0].severity)

        // Lọc SECURITY_ALERT
        viewModel.filterBySeverity("SECURITY_ALERT")
        state = viewModel.uiState.value
        assertEquals(1, state.filteredLogs.size)
        assertEquals(AuditSeverity.SECURITY_ALERT, state.filteredLogs[0].severity)

        // Lọc ALL
        viewModel.filterBySeverity("ALL")
        state = viewModel.uiState.value
        assertEquals(3, state.filteredLogs.size)
    }

    @Test
    fun testSearch_filtersByKeywordInDetailsOrDevice() = runBlocking {
        insertSampleLogs()
        viewModel.loadLogs()

        // Tìm kiếm theo tên thiết bị
        viewModel.search("Redmi")
        var state = viewModel.uiState.value
        assertEquals(1, state.filteredLogs.size)
        assertTrue(state.filteredLogs[0].actorDevice.contains("Redmi"))

        // Tìm kiếm theo mã lỗi trong details
        viewModel.search("0x103")
        state = viewModel.uiState.value
        assertEquals(1, state.filteredLogs.size)
        assertTrue(state.filteredLogs[0].details.contains("0x103"))

        // Xóa tìm kiếm
        viewModel.search("")
        state = viewModel.uiState.value
        assertEquals(3, state.filteredLogs.size)
    }
}

