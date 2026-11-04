package com.teamshare.app.presentation.viewmodel

import com.teamshare.app.domain.model.AuditEventType
import com.teamshare.app.domain.model.AuditLog
import com.teamshare.app.domain.model.AuditSeverity
import com.teamshare.app.domain.repository.IAuditRepository
import com.teamshare.app.domain.usecase.audit.QuerySecurityAuditUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class AuditViewModelTest {

    private class FakeAuditRepository : IAuditRepository {
        val logs = CopyOnWriteArrayList<AuditLog>()

        override suspend fun logEvent(log: AuditLog) {
            logs.add(log)
        }

        override fun getLogsByTeam(teamId: String): Flow<List<AuditLog>> =
            flowOf(logs.filter { it.teamId == teamId })

        override fun getRecentLogs(limit: Int): Flow<List<AuditLog>> =
            flowOf(logs.take(limit))
    }

    private lateinit var auditRepo: FakeAuditRepository
    private lateinit var auditViewModel: AuditViewModel

    @Before
    fun setup() {
        auditRepo = FakeAuditRepository()
        val queryUseCase = QuerySecurityAuditUseCase(auditRepo)
        auditViewModel = AuditViewModel(
            querySecurityAuditUseCase = queryUseCase,
            customScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        )
    }

    @Test
    fun `loadLogs populates logs in uiState`() = runBlocking {
        auditRepo.logEvent(
            AuditLog(
                teamId = "t_audit",
                actorId = "act_1",
                eventType = AuditEventType.TEAM_CREATED,
                severity = AuditSeverity.INFO,
                details = "Tạo nhóm thành công"
            )
        )
        auditRepo.logEvent(
            AuditLog(
                teamId = "t_audit",
                actorId = "act_2",
                eventType = AuditEventType.CHECKSUM_FAILED,
                severity = AuditSeverity.SECURITY_ALERT,
                details = "Cảnh báo sai lệch SHA-256"
            )
        )

        auditViewModel.loadLogs("t_audit")

        val state = auditViewModel.uiState.value
        assertFalse(state.isLoading)
        assertNull(state.errorMessage)
        assertEquals(2, state.logs.size)
        assertEquals(AuditEventType.TEAM_CREATED, state.logs[0].eventType)
        assertEquals(AuditEventType.CHECKSUM_FAILED, state.logs[1].eventType)
    }
}
