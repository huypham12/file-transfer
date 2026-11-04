package com.teamshare.app.presentation

import android.net.FakeUri
import com.teamshare.app.core.platform.network.NetworkState
import com.teamshare.app.core.platform.network.NetworkType
import com.teamshare.app.core.security.keystore.SoftwareKeyManager
import com.teamshare.app.core.transfer.FakeNetworkMonitor
import com.teamshare.app.core.transfer.FakeServiceController
import com.teamshare.app.core.transfer.FakeTransferRepository
import com.teamshare.app.core.transfer.TransferCoordinator
import com.teamshare.app.core.transfer.lan.LanTransferEngine
import com.teamshare.app.core.transfer.lan.TransferEngineState
import com.teamshare.app.core.transfer.relay.RelayClientEngine
import com.teamshare.app.domain.model.DownloadStatus
import com.teamshare.app.domain.model.MemberRole
import com.teamshare.app.domain.model.SharedDocument
import com.teamshare.app.domain.model.Team
import com.teamshare.app.domain.model.TeamMember
import com.teamshare.app.domain.model.TeamStatus
import com.teamshare.app.domain.model.TransferChannel
import com.teamshare.app.domain.model.TransferProgress
import com.teamshare.app.domain.repository.IDocumentRepository
import com.teamshare.app.domain.repository.ITeamRepository
import com.teamshare.app.domain.usecase.audit.QuerySecurityAuditUseCase
import com.teamshare.app.domain.usecase.document.DeleteDocumentUseCase
import com.teamshare.app.domain.usecase.document.GetDocumentCatalogUseCase
import com.teamshare.app.domain.usecase.document.OpenSecureDocumentUseCase
import com.teamshare.app.domain.usecase.document.PublishDocumentUseCase
import com.teamshare.app.domain.usecase.member.GenerateQrInviteUseCase
import com.teamshare.app.domain.usecase.member.GetTeamMembersUseCase
import com.teamshare.app.domain.usecase.member.JoinTeamViaQrUseCase
import com.teamshare.app.domain.usecase.member.RevokeMemberUseCase
import com.teamshare.app.domain.usecase.team.CreateTeamUseCase
import com.teamshare.app.domain.usecase.team.DisbandTeamUseCase
import com.teamshare.app.domain.usecase.team.FakeAuditRepository
import com.teamshare.app.domain.usecase.transfer.ControlSessionUseCase
import com.teamshare.app.presentation.ui.team.qr.parseQrPayload
import com.teamshare.app.presentation.ui.team.qr.serializeQrPayload
import com.teamshare.app.presentation.viewmodel.AuditViewModel
import com.teamshare.app.presentation.viewmodel.DocumentViewModel
import com.teamshare.app.presentation.viewmodel.TeamViewModel
import com.teamshare.app.presentation.viewmodel.TransferViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Kiểm thử tích hợp toàn diện luồng nghiệp vụ trên Tầng Trình diễn (Presentation Layer - Stage 7).
 * Kiểm chứng sự phối hợp nhịp nhàng giữa 4 ViewModels, mã hóa/giải mã QR Protocol,
 * công bố & mở tệp an toàn, và điều tiết tiến trình truyền tải 100ms.
 */
class Stage7PresentationIntegrationTest {

    private class IntegrationTeamRepo : ITeamRepository {
        val teams = ConcurrentHashMap<String, Team>()
        val members = ConcurrentHashMap<String, MutableList<TeamMember>>()

        override suspend fun createTeam(team: Team): Result<Team> {
            teams[team.teamId] = team
            return Result.success(team)
        }

        override fun getTeamById(teamId: String): Flow<Team?> = flowOf(teams[teamId])
        override fun getAllTeams(): Flow<List<Team>> = flowOf(teams.values.toList())

        override suspend fun disbandTeam(teamId: String): Result<Unit> {
            val current = teams[teamId] ?: return Result.failure(NoSuchElementException())
            teams[teamId] = current.copy(status = TeamStatus.DISBANDED)
            return Result.success(Unit)
        }

        override suspend fun addMember(member: TeamMember): Result<Unit> {
            members.getOrPut(member.teamId) { mutableListOf() }.add(member)
            return Result.success(Unit)
        }

        override suspend fun revokeMember(teamId: String, memberId: String): Result<Unit> {
            val list = members[teamId] ?: return Result.failure(NoSuchElementException())
            val index = list.indexOfFirst { it.memberId == memberId }
            if (index >= 0) {
                list[index] = list[index].copy(isActive = false)
            }
            return Result.success(Unit)
        }

        override fun getMembersByTeam(teamId: String): Flow<List<TeamMember>> =
            flowOf(members[teamId]?.toList() ?: emptyList())

        override suspend fun isMemberActive(teamId: String, memberId: String): Boolean {
            return members[teamId]?.find { it.memberId == memberId }?.isActive ?: false
        }
    }

    private class IntegrationDocRepo : IDocumentRepository {
        val docs = ConcurrentHashMap<String, SharedDocument>()

        override suspend fun publishDocument(document: SharedDocument): Result<SharedDocument> {
            docs[document.docId] = document
            return Result.success(document)
        }

        override fun getDocumentsByTeam(teamId: String): Flow<List<SharedDocument>> =
            flowOf(docs.values.filter { it.teamId == teamId })

        override suspend fun getDocumentById(docId: String): SharedDocument? = docs[docId]

        override suspend fun updateDownloadStatus(docId: String, status: DownloadStatus, localUri: String?) {
            docs[docId]?.let { current ->
                docs[docId] = current.copy(downloadStatus = status, localUri = localUri ?: current.localUri)
            }
        }

        override suspend fun deleteDocument(docId: String): Result<Unit> {
            docs.remove(docId)
            return Result.success(Unit)
        }
    }

    private lateinit var teamRepo: IntegrationTeamRepo
    private lateinit var docRepo: IntegrationDocRepo
    private lateinit var auditRepo: FakeAuditRepository
    private lateinit var keyManager: SoftwareKeyManager

    private lateinit var teamViewModel: TeamViewModel
    private lateinit var documentViewModel: DocumentViewModel
    private lateinit var transferViewModel: TransferViewModel
    private lateinit var auditViewModel: AuditViewModel

    private lateinit var tempFile: File

    @Before
    fun setup() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "teamshare_stage7_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        tempFile = File(tempDir, "e2e_sample.pdf")
        tempFile.writeText("E2E Presentation Layer Integration Test Content")

        val testScope = CoroutineScope(Dispatchers.Unconfined)

        teamRepo = IntegrationTeamRepo()
        docRepo = IntegrationDocRepo()
        auditRepo = FakeAuditRepository()
        keyManager = SoftwareKeyManager()

        val transferRepo = FakeTransferRepository()
        val serviceController = FakeServiceController()
        val networkMonitor = FakeNetworkMonitor(NetworkState(isConnected = true, type = NetworkType.WIFI_SAME_LAN))

        val transferCoordinator = TransferCoordinator(
            lanEngine = LanTransferEngine(),
            relayEngine = RelayClientEngine(),
            networkMonitor = networkMonitor,
            transferRepository = transferRepo,
            serviceController = serviceController,
            auditRepository = auditRepo
        )

        // ViewModels
        teamViewModel = TeamViewModel(
            teamRepository = teamRepo,
            createTeamUseCase = CreateTeamUseCase(teamRepo, keyManager, auditRepo),
            disbandTeamUseCase = DisbandTeamUseCase(teamRepo, keyManager, auditRepo, transferCoordinator),
            generateQrInviteUseCase = GenerateQrInviteUseCase(teamRepo, keyManager, auditRepo),
            joinTeamViaQrUseCase = JoinTeamViaQrUseCase(teamRepo, keyManager, auditRepo),
            getTeamMembersUseCase = GetTeamMembersUseCase(teamRepo),
            revokeMemberUseCase = RevokeMemberUseCase(teamRepo, transferCoordinator, auditRepo),
            keyManager = keyManager,
            customScope = testScope
        )

        documentViewModel = DocumentViewModel(
            publishDocumentUseCase = PublishDocumentUseCase(docRepo, null, auditRepo),
            getDocumentCatalogUseCase = GetDocumentCatalogUseCase(docRepo),
            openSecureDocumentUseCase = OpenSecureDocumentUseCase(
                documentRepository = docRepo,
                fileProviderGateway = null,
                directoryManager = null,
                uriConverter = { file -> FakeUri("file", file.absolutePath) }
            ),
            deleteDocumentUseCase = DeleteDocumentUseCase(docRepo, null, auditRepo, teamRepo),
            customScope = testScope
        )

        transferViewModel = TransferViewModel(
            controlSessionUseCase = ControlSessionUseCase(transferCoordinator, transferRepo, auditRepo),
            transferCoordinator = transferCoordinator,
            customScope = testScope
        )

        auditViewModel = AuditViewModel(
            querySecurityAuditUseCase = QuerySecurityAuditUseCase(auditRepo),
            customScope = testScope
        )
    }

    @Test
    fun `full presentation end-to-end flow succeeds`() = runBlocking {
        // Bước 1: Tạo nhóm qua TeamViewModel
        var createdTeamId: String? = null
        teamViewModel.createTeam("Nhóm An Ninh Mạng", "Phòng thí nghiệm KMA") { id ->
            createdTeamId = id
        }

        assertNotNull(createdTeamId)
        val teamId = createdTeamId!!
        val listState = teamViewModel.teamListUiState.value
        assertEquals("Nhóm An Ninh Mạng", listState.newlyCreatedTeam?.teamName)

        // Bước 2: Tải Workspace
        teamViewModel.loadWorkspace(teamId, currentMemberId = "owner_member_id")
        val wsState = teamViewModel.workspaceUiState.value
        assertEquals(teamId, wsState.team?.teamId)

        // Bước 3: Trưởng nhóm sinh mã QR lời mời có chữ ký số
        teamViewModel.generateInviteQr(teamId, ttlSec = 600L)
        val qrPayload = teamViewModel.qrInviteUiState.value.payload
        assertNotNull(qrPayload)
        assertEquals(teamId, qrPayload?.teamId)
        assertFalse(teamViewModel.qrInviteUiState.value.isExpired)

        // Bước 4: Đóng gói và bóc tách chuỗi mã QR
        val qrString = serializeQrPayload(qrPayload!!)
        val parsedPayload = parseQrPayload(qrString)
        assertNotNull(parsedPayload)
        assertEquals(qrPayload.teamId, parsedPayload?.teamId)

        // Bước 5: Thành viên mới quét QR gia nhập nhóm
        var joinedMember: TeamMember? = null
        teamViewModel.joinTeamViaQr(parsedPayload!!, "Bob Member") { member ->
            joinedMember = member
        }
        assertNotNull(joinedMember)
        assertEquals("Bob Member", joinedMember?.memberName)
        assertEquals(MemberRole.MEMBER, joinedMember?.role)

        // Bước 6: Công bố tài liệu vào nhóm qua DocumentViewModel
        val fakeUri = FakeUri("content", "media/external/file/102")
        val testContent = "Confidential Security Analysis"
        val inStream = ByteArrayInputStream(testContent.toByteArray())

        var publishSuccess = false
        documentViewModel.publishDocument(
            teamId = teamId,
            publisherId = joinedMember!!.memberId,
            uri = fakeUri,
            requireBiometric = true,
            overrideName = "security_report.pdf",
            overrideSize = testContent.length.toLong(),
            overrideMime = "application/pdf",
            customInputStream = inStream
        ) {
            publishSuccess = true
        }

        assertTrue(publishSuccess)
        documentViewModel.loadDocuments(teamId)
        val docCatalog = documentViewModel.catalogUiState.value.documents
        assertEquals(1, docCatalog.size)
        assertEquals("security_report.pdf", docCatalog[0].fileName)
        assertTrue(docCatalog[0].requireBiometric)

        // Bước 7: Mở tài liệu an toàn (đã tải xong)
        val publishedDoc = docCatalog[0].copy(
            downloadStatus = DownloadStatus.COMPLETED,
            localUri = tempFile.absolutePath
        )
        docRepo.publishDocument(publishedDoc)

        var openedUri: String? = null
        documentViewModel.openDocument(publishedDoc.docId) { uri ->
            openedUri = uri.toString()
        }
        assertNotNull(openedUri)

        // Bước 8: Điều phối tiến trình truyền tải qua TransferViewModel
        val progressStream = flow {
            emit(
                TransferProgress(
                    sessionId = "sess_integration",
                    bytesTransferred = 5000L,
                    totalBytes = 10000L,
                    speedBytesPerSec = 30_000_000L,
                    channel = TransferChannel.DIRECT_LAN,
                    state = TransferEngineState.TRANSFERRING
                )
            )
            emit(
                TransferProgress(
                    sessionId = "sess_integration",
                    bytesTransferred = 10000L,
                    totalBytes = 10000L,
                    speedBytesPerSec = 35_000_000L,
                    channel = TransferChannel.DIRECT_LAN,
                    state = TransferEngineState.COMPLETED
                )
            )
        }

        transferViewModel.observeProgress(progressStream, fileName = "security_report.pdf")
        val transferState = transferViewModel.uiState.value
        assertEquals("sess_integration", transferState.sessionId)
        assertEquals(TransferEngineState.COMPLETED, transferState.state)
        assertEquals(1.0f, transferState.progressPercent, 0.001f)

        // Bước 9: Tra cứu nhật ký kiểm toán qua AuditViewModel
        auditViewModel.loadLogs(teamId)
        val auditState = auditViewModel.uiState.value
        assertNotNull(auditState.logs)
        assertTrue(auditState.logs.isNotEmpty())
    }
}

