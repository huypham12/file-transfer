package com.teamshare.app.domain.usecase.platform

import android.net.FakeUri
import androidx.fragment.app.FragmentActivity
import com.teamshare.app.core.platform.network.NetworkState
import com.teamshare.app.core.platform.network.NetworkType
import com.teamshare.app.core.security.biometric.IBiometricAuthManager
import com.teamshare.app.core.transfer.FakeNetworkMonitor
import com.teamshare.app.core.transfer.FakeServiceController
import com.teamshare.app.domain.model.AuditLog
import com.teamshare.app.domain.model.AuditEventType
import com.teamshare.app.domain.model.AuditSeverity
import com.teamshare.app.domain.model.ServiceAction
import com.teamshare.app.domain.model.TransferChannel
import com.teamshare.app.domain.usecase.audit.QuerySecurityAuditUseCase
import com.teamshare.app.domain.usecase.document.FakeDocumentRepository
import com.teamshare.app.domain.usecase.document.PublishDocumentUseCase
import com.teamshare.app.domain.usecase.team.FakeAuditRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream

class FakeBiometricAuthManager(var isAvailable: Boolean = true) : IBiometricAuthManager {
    override fun canAuthenticate(): Boolean = isAvailable
    override fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        negativeButtonText: String,
        onSuccess: () -> Unit,
        onError: (errorCode: Int, errString: String) -> Unit,
        onFailed: () -> Unit
    ) {
        onSuccess()
    }
}

class PlatformAndAuditUseCasesTest {

    private lateinit var documentRepository: FakeDocumentRepository
    private lateinit var auditRepository: FakeAuditRepository
    private lateinit var serviceController: FakeServiceController
    private lateinit var networkMonitor: FakeNetworkMonitor
    private lateinit var biometricAuthManager: FakeBiometricAuthManager

    private lateinit var publishDocumentUseCase: PublishDocumentUseCase
    private lateinit var handleSendIntentUseCase: HandleSendIntentUseCase
    private lateinit var authenticateBiometricUseCase: AuthenticateBiometricUseCase
    private lateinit var manageForegroundServiceUseCase: ManageForegroundServiceUseCase
    private lateinit var monitorNetworkAdaptiveUseCase: MonitorNetworkAdaptiveUseCase
    private lateinit var querySecurityAuditUseCase: QuerySecurityAuditUseCase

    private val testTeamId = "team_platform_01"

    @Before
    fun setUp() {
        documentRepository = FakeDocumentRepository()
        auditRepository = FakeAuditRepository()
        serviceController = FakeServiceController()
        networkMonitor = FakeNetworkMonitor()
        biometricAuthManager = FakeBiometricAuthManager()

        publishDocumentUseCase = PublishDocumentUseCase(documentRepository, null, auditRepository)
        handleSendIntentUseCase = HandleSendIntentUseCase(publishDocumentUseCase, auditRepository)
        authenticateBiometricUseCase = AuthenticateBiometricUseCase(biometricAuthManager)
        manageForegroundServiceUseCase = ManageForegroundServiceUseCase(serviceController)
        monitorNetworkAdaptiveUseCase = MonitorNetworkAdaptiveUseCase(networkMonitor)
        querySecurityAuditUseCase = QuerySecurityAuditUseCase(auditRepository)
    }

    @Test
    fun testHandleSendIntentSuccess() = runBlocking {
        val uri1 = FakeUri("content", "media/image1.jpg")
        val uri2 = FakeUri("content", "media/doc2.pdf")

        val streams: Map<android.net.Uri, java.io.InputStream> = mapOf(
            uri1 to ByteArrayInputStream("Image Content".toByteArray()),
            uri2 to ByteArrayInputStream("PDF Document Content".toByteArray())
        )

        val result = handleSendIntentUseCase(
            intentUris = listOf(uri1, uri2),
            targetTeamId = testTeamId,
            publisherId = "user_share",
            customStreams = streams
        ).first()

        assertTrue(result.isSuccess)
        assertEquals(2, result.getOrThrow())
        assertEquals(2, documentRepository.docs.size)
    }

    @Test
    fun testAuthenticateBiometricSuccess() = runBlocking {
        val result = authenticateBiometricUseCase().first()
        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow())
    }

    @Test
    fun testAuthenticateBiometricFailsWhenHardwareUnavailable() = runBlocking {
        biometricAuthManager.isAvailable = false
        val result = authenticateBiometricUseCase().first()
        assertTrue(result.isFailure)
    }

    @Test
    fun testManageForegroundServiceActions() {
        manageForegroundServiceUseCase(
            action = ServiceAction.START,
            sessionId = "sess_fgs",
            docName = "document.pdf",
            totalBytes = 5000L,
            isLan = true
        )
        assertTrue(serviceController.startSessionCalled)

        manageForegroundServiceUseCase(action = ServiceAction.PAUSE)
        assertTrue(serviceController.requestPauseCalled)

        manageForegroundServiceUseCase(action = ServiceAction.RESUME)
        assertTrue(serviceController.requestResumeCalled)

        manageForegroundServiceUseCase(action = ServiceAction.CANCEL)
        assertTrue(serviceController.requestCancelCalled)
    }

    @Test
    fun testMonitorNetworkAdaptiveRecommendsLanAndRelay() = runBlocking {
        networkMonitor.updateState(
            NetworkState(type = NetworkType.WIFI_SAME_LAN, isConnected = true)
        )
        val stateLan = monitorNetworkAdaptiveUseCase().first()
        assertTrue(stateLan.isOnline)
        assertEquals(TransferChannel.DIRECT_LAN, stateLan.recommendedChannel)

        networkMonitor.updateState(
            NetworkState(type = NetworkType.CELLULAR, isConnected = true)
        )
        val stateRelay = monitorNetworkAdaptiveUseCase().first()
        assertTrue(stateRelay.isOnline)
        assertEquals(TransferChannel.ZERO_KNOWLEDGE_RELAY, stateRelay.recommendedChannel)

        networkMonitor.updateState(
            NetworkState(type = NetworkType.DISCONNECTED, isConnected = false)
        )
        val stateOff = monitorNetworkAdaptiveUseCase().first()
        assertFalse(stateOff.isOnline)
    }

    @Test
    fun testQuerySecurityAuditLogs() = runBlocking {
        auditRepository.logEvent(
            AuditLog(
                teamId = testTeamId,
                actorId = "actor_1",
                eventType = AuditEventType.TEAM_CREATED,
                severity = AuditSeverity.INFO,
                details = "Log 1"
            )
        )
        auditRepository.logEvent(
            AuditLog(
                teamId = "other_team",
                actorId = "actor_2",
                eventType = AuditEventType.MEMBER_JOINED,
                severity = AuditSeverity.INFO,
                details = "Log 2"
            )
        )

        val teamLogs = querySecurityAuditUseCase(teamId = testTeamId).first()
        assertEquals(1, teamLogs.size)
        assertEquals(testTeamId, teamLogs[0].teamId)

        val allLogs = querySecurityAuditUseCase(teamId = null).first()
        assertEquals(2, allLogs.size)
    }
}
