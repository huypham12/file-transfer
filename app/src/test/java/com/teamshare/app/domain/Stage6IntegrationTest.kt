package com.teamshare.app.domain

import android.net.FakeUri
import com.teamshare.app.core.platform.network.NetworkState
import com.teamshare.app.core.platform.network.NetworkType
import com.teamshare.app.core.security.crypto.Sha256StreamCalculator
import com.teamshare.app.core.security.keystore.SoftwareKeyManager
import com.teamshare.app.core.transfer.FakeNetworkMonitor
import com.teamshare.app.core.transfer.FakeServiceController
import com.teamshare.app.core.transfer.FakeTransferRepository
import com.teamshare.app.core.transfer.TransferCoordinator
import com.teamshare.app.core.transfer.lan.LanTransferEngine
import com.teamshare.app.core.transfer.relay.RelayClientEngine
import com.teamshare.app.domain.model.DownloadStatus
import com.teamshare.app.domain.model.MemberRole
import com.teamshare.app.domain.model.SessionCmd
import com.teamshare.app.domain.model.SessionStatus
import com.teamshare.app.domain.model.TeamStatus
import com.teamshare.app.domain.model.TransferChannel
import com.teamshare.app.domain.model.TransferDirection
import com.teamshare.app.domain.model.TransferSession
import com.teamshare.app.domain.usecase.audit.QuerySecurityAuditUseCase
import com.teamshare.app.domain.usecase.document.DeleteDocumentUseCase
import com.teamshare.app.domain.usecase.document.FakeDocumentRepository
import com.teamshare.app.domain.usecase.document.GetDocumentCatalogUseCase
import com.teamshare.app.domain.usecase.document.OpenSecureDocumentUseCase
import com.teamshare.app.domain.usecase.document.PublishDocumentUseCase
import com.teamshare.app.domain.usecase.member.GenerateQrInviteUseCase
import com.teamshare.app.domain.usecase.member.GetTeamMembersUseCase
import com.teamshare.app.domain.usecase.member.JoinTeamViaQrUseCase
import com.teamshare.app.domain.usecase.member.RevokeMemberUseCase
import com.teamshare.app.domain.usecase.platform.AuthenticateBiometricUseCase
import com.teamshare.app.domain.usecase.platform.FakeBiometricAuthManager
import com.teamshare.app.domain.usecase.platform.HandleSendIntentUseCase
import com.teamshare.app.domain.usecase.platform.ManageForegroundServiceUseCase
import com.teamshare.app.domain.usecase.platform.MonitorNetworkAdaptiveUseCase
import com.teamshare.app.domain.usecase.team.CreateTeamUseCase
import com.teamshare.app.domain.usecase.team.DisbandTeamUseCase
import com.teamshare.app.domain.usecase.team.FakeAuditRepository
import com.teamshare.app.domain.usecase.team.FakeTeamRepository
import com.teamshare.app.domain.usecase.transfer.AuthenticatePeerUseCase
import com.teamshare.app.domain.usecase.transfer.ControlSessionUseCase
import com.teamshare.app.domain.usecase.transfer.ExecuteLanTransferUseCase
import com.teamshare.app.domain.usecase.transfer.ExecuteRelayTransferUseCase
import com.teamshare.app.domain.usecase.transfer.VerifyChecksumUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import kotlin.system.measureTimeMillis

class Stage6IntegrationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // Repositories & Managers
    private lateinit var teamRepository: FakeTeamRepository
    private lateinit var documentRepository: FakeDocumentRepository
    private lateinit var transferRepository: FakeTransferRepository
    private lateinit var auditRepository: FakeAuditRepository
    private lateinit var keyManager: SoftwareKeyManager
    private lateinit var networkMonitor: FakeNetworkMonitor
    private lateinit var serviceController: FakeServiceController
    private lateinit var biometricAuthManager: FakeBiometricAuthManager

    // Engines & Coordinator
    private lateinit var lanEngine: LanTransferEngine
    private lateinit var relayEngine: RelayClientEngine
    private lateinit var coordinator: TransferCoordinator

    // All 20 UseCases
    private lateinit var createTeamUseCase: CreateTeamUseCase
    private lateinit var disbandTeamUseCase: DisbandTeamUseCase
    private lateinit var generateQrInviteUseCase: GenerateQrInviteUseCase
    private lateinit var joinTeamViaQrUseCase: JoinTeamViaQrUseCase
    private lateinit var getTeamMembersUseCase: GetTeamMembersUseCase
    private lateinit var revokeMemberUseCase: RevokeMemberUseCase
    private lateinit var publishDocumentUseCase: PublishDocumentUseCase
    private lateinit var getDocumentCatalogUseCase: GetDocumentCatalogUseCase
    private lateinit var openSecureDocumentUseCase: OpenSecureDocumentUseCase
    private lateinit var deleteDocumentUseCase: DeleteDocumentUseCase
    private lateinit var authenticatePeerUseCase: AuthenticatePeerUseCase
    private lateinit var executeLanTransferUseCase: ExecuteLanTransferUseCase
    private lateinit var executeRelayTransferUseCase: ExecuteRelayTransferUseCase
    private lateinit var verifyChecksumUseCase: VerifyChecksumUseCase
    private lateinit var controlSessionUseCase: ControlSessionUseCase
    private lateinit var handleSendIntentUseCase: HandleSendIntentUseCase
    private lateinit var authenticateBiometricUseCase: AuthenticateBiometricUseCase
    private lateinit var manageForegroundServiceUseCase: ManageForegroundServiceUseCase
    private lateinit var monitorNetworkAdaptiveUseCase: MonitorNetworkAdaptiveUseCase
    private lateinit var querySecurityAuditUseCase: QuerySecurityAuditUseCase

    @Before
    fun setUp() {
        teamRepository = FakeTeamRepository()
        documentRepository = FakeDocumentRepository()
        transferRepository = FakeTransferRepository()
        auditRepository = FakeAuditRepository()
        keyManager = SoftwareKeyManager()
        networkMonitor = FakeNetworkMonitor()
        serviceController = FakeServiceController()
        biometricAuthManager = FakeBiometricAuthManager()

        lanEngine = LanTransferEngine()
        relayEngine = RelayClientEngine()

        coordinator = TransferCoordinator(
            lanEngine = lanEngine,
            relayEngine = relayEngine,
            networkMonitor = networkMonitor,
            transferRepository = transferRepository,
            serviceController = serviceController,
            auditRepository = auditRepository,
            keyManager = keyManager,
            ioDispatcher = Dispatchers.Unconfined
        )

        // Wire 20 UseCases
        createTeamUseCase = CreateTeamUseCase(teamRepository, keyManager, auditRepository)
        disbandTeamUseCase = DisbandTeamUseCase(teamRepository, keyManager, auditRepository, coordinator)
        generateQrInviteUseCase = GenerateQrInviteUseCase(teamRepository, keyManager, auditRepository)
        joinTeamViaQrUseCase = JoinTeamViaQrUseCase(teamRepository, keyManager, auditRepository)
        getTeamMembersUseCase = GetTeamMembersUseCase(teamRepository)
        revokeMemberUseCase = RevokeMemberUseCase(teamRepository, coordinator, auditRepository)
        publishDocumentUseCase = PublishDocumentUseCase(documentRepository, null, auditRepository)
        getDocumentCatalogUseCase = GetDocumentCatalogUseCase(documentRepository)
        openSecureDocumentUseCase = OpenSecureDocumentUseCase(
            documentRepository = documentRepository,
            fileProviderGateway = null,
            directoryManager = null,
            uriConverter = { file -> FakeUri("file", file.absolutePath) }
        )
        deleteDocumentUseCase = DeleteDocumentUseCase(documentRepository, null, auditRepository, teamRepository)
        authenticatePeerUseCase = AuthenticatePeerUseCase(teamRepository, keyManager)
        executeLanTransferUseCase = ExecuteLanTransferUseCase(coordinator)
        executeRelayTransferUseCase = ExecuteRelayTransferUseCase(coordinator)
        verifyChecksumUseCase = VerifyChecksumUseCase(auditRepository)
        controlSessionUseCase = ControlSessionUseCase(coordinator, transferRepository, auditRepository)
        handleSendIntentUseCase = HandleSendIntentUseCase(publishDocumentUseCase, auditRepository)
        authenticateBiometricUseCase = AuthenticateBiometricUseCase(biometricAuthManager)
        manageForegroundServiceUseCase = ManageForegroundServiceUseCase(serviceController)
        monitorNetworkAdaptiveUseCase = MonitorNetworkAdaptiveUseCase(networkMonitor)
        querySecurityAuditUseCase = QuerySecurityAuditUseCase(auditRepository)
    }

    @Test
    fun testCompleteCollaborationAndTransferLifecycle() = runBlocking {
        // Step 1: Owner creates Team Alpha
        val createTeamResult = createTeamUseCase("Team Alpha", "Nhom bao mat cao").first()
        assertTrue(createTeamResult.isSuccess)
        val team = createTeamResult.getOrThrow()
        val teamId = team.teamId
        assertEquals(TeamStatus.ACTIVE, team.status)

        // Step 2: Owner generates dynamic QR invite
        val qrResult = generateQrInviteUseCase(teamId, ttlSec = 600L).first()
        assertTrue(qrResult.isSuccess)
        val qrPayload = qrResult.getOrThrow()

        // Step 3: Member Bob joins via QR code
        val joinResult = joinTeamViaQrUseCase(qrPayload, "Bob").first()
        assertTrue(joinResult.isSuccess)
        val bob = joinResult.getOrThrow()
        assertEquals("Bob", bob.memberName)

        val members = getTeamMembersUseCase(teamId).first()
        assertEquals(2, members.size) // Owner + Bob

        // Step 4: Bob publishes a document into Team Alpha
        val fileContent = "Tai lieu bao mat nghien cuu Stage 6"
        val fakeUri = FakeUri("content", "media/doc_stage6.pdf")
        val stream = ByteArrayInputStream(fileContent.toByteArray(Charsets.UTF_8))

        val publishResult = publishDocumentUseCase(
            teamId = teamId,
            publisherId = bob.memberId,
            uri = fakeUri,
            requireBiometric = true,
            overrideName = "stage6_report.pdf",
            overrideSize = fileContent.length.toLong(),
            overrideMime = "application/pdf",
            customInputStream = stream
        ).first()

        assertTrue(publishResult.isSuccess)
        val doc = publishResult.getOrThrow()
        assertEquals("stage6_report.pdf", doc.fileName)

        val catalog = getDocumentCatalogUseCase(teamId).first()
        assertEquals(1, catalog.size)

        // Step 5: Transfer Coordinator channel selection & Adaptive network
        networkMonitor.updateState(
            NetworkState(type = NetworkType.WIFI_SAME_LAN, isConnected = true)
        )
        val adaptiveState = monitorNetworkAdaptiveUseCase().first()
        assertEquals(TransferChannel.DIRECT_LAN, adaptiveState.recommendedChannel)

        val session = TransferSession(
            sessionId = "sess_e2e_01",
            docId = doc.docId,
            peerMemberId = bob.memberId,
            direction = TransferDirection.OUTGOING,
            totalBytes = doc.fileSize,
            status = SessionStatus.TRANSFERRING
        )
        transferRepository.createSession(session)

        // Step 6: Session pause & resume controls
        val pauseResult = controlSessionUseCase("sess_e2e_01", SessionCmd.PAUSE).first()
        assertTrue(pauseResult.isSuccess)
        assertEquals(SessionStatus.PAUSED, transferRepository.getSessionById("sess_e2e_01")?.status)

        // Network changes to CELLULAR
        networkMonitor.updateState(
            NetworkState(type = NetworkType.CELLULAR, isConnected = true)
        )
        val cellularAdaptive = monitorNetworkAdaptiveUseCase().first()
        assertEquals(TransferChannel.ZERO_KNOWLEDGE_RELAY, cellularAdaptive.recommendedChannel)

        val resumeResult = controlSessionUseCase("sess_e2e_01", SessionCmd.RESUME).first()
        assertTrue(resumeResult.isSuccess)
        assertEquals(SessionStatus.TRANSFERRING, transferRepository.getSessionById("sess_e2e_01")?.status)

        // Step 7: End-to-end file checksum verification
        val tempTestFile = tempFolder.newFile("transferred_file.pdf")
        tempTestFile.writeText(fileContent)
        val expectedSha = Sha256StreamCalculator.calculateFileSha256(tempTestFile)

        val isHashValid = verifyChecksumUseCase.execute(tempTestFile, expectedSha, doc.docId, teamId)
        assertTrue(isHashValid)

        // Step 8: Verify Biometric check for opening secure document
        val bioResult = authenticateBiometricUseCase().first()
        assertTrue(bioResult.isSuccess)

        val openResult = openSecureDocumentUseCase(doc.docId).first()
        assertTrue(openResult.isSuccess)

        // Step 9: Query security audit logs
        val auditLogs = querySecurityAuditUseCase(teamId = teamId).first()
        assertTrue(auditLogs.isNotEmpty())

        // Step 10: Revoke member Bob with rapid cutoff (< 200 ms)
        val durationMs = measureTimeMillis {
            val revokeResult = revokeMemberUseCase(teamId, bob.memberId).first()
            assertTrue(revokeResult.isSuccess)
        }
        assertTrue("Thu hồi thành viên phải ngắt phiên trong < 200ms (thực tế: ${durationMs}ms)", durationMs < 200)
        assertFalse(teamRepository.isMemberActive(teamId, bob.memberId))
        assertEquals(SessionStatus.CANCELLED, transferRepository.getSessionById("sess_e2e_01")?.status)

        // Step 11: Document deletion RBAC validation
        // Attacker member cannot delete Bob's document
        val unauthorizedDeleteResult = deleteDocumentUseCase(
            docId = doc.docId,
            requesterId = "malicious_actor",
            requesterRole = MemberRole.MEMBER,
            wipeLocally = false
        ).first()
        assertTrue(unauthorizedDeleteResult.isFailure)
        assertTrue(unauthorizedDeleteResult.exceptionOrNull() is SecurityException)

        // Team Owner can delete document
        val ownerDeleteResult = deleteDocumentUseCase(
            docId = doc.docId,
            requesterId = team.ownerFingerprint,
            requesterRole = MemberRole.OWNER,
            wipeLocally = false
        ).first()
        assertTrue(ownerDeleteResult.isSuccess)
        assertEquals(null, documentRepository.getDocumentById(doc.docId))

        // Step 12: Disband team and verify key cleanup
        val disbandResult = disbandTeamUseCase(teamId).first()
        assertTrue(disbandResult.isSuccess)
        val disbandedTeam = teamRepository.getTeamById(teamId).first()
        assertEquals(TeamStatus.DISBANDED, disbandedTeam?.status)
    }
}

