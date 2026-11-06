package com.teamshare.app.e2e

import android.net.FakeUri
import com.teamshare.app.core.security.keystore.IKeyManager
import com.teamshare.app.core.security.keystore.SoftwareKeyManager
import com.teamshare.app.core.transfer.FakeNetworkMonitor
import com.teamshare.app.core.transfer.FakeServiceController
import com.teamshare.app.core.transfer.FakeTransferRepository
import com.teamshare.app.core.transfer.TransferCoordinator
import com.teamshare.app.core.transfer.lan.LanTransferEngine
import com.teamshare.app.core.transfer.relay.RelayClientEngine
import com.teamshare.app.domain.model.Team
import com.teamshare.app.domain.model.TeamMember
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
import java.io.File
import java.io.FileOutputStream
import java.security.PublicKey

/**
 * Môi trường kiểm thử tích hợp đầu cuối (E2E Test Harness) cho Stage 8.
 * Cung cấp mô phỏng đầy đủ của hai thiết bị di động (Owner và Member) với
 * đầy đủ 20 UseCases, cơ sở dữ liệu riêng biệt và cầu nối mạng truyền thông.
 */
class E2ETestHarness(
    val baseWorkingDir: File
) {

    /**
     * Đại diện cho một nút thiết bị di động (Peer Node) hoàn chỉnh trong kịch bản E2E.
     */
    class PeerNode(
        val peerId: String,
        val peerName: String,
        val workingDir: File
    ) {
        val keyManager = SoftwareKeyManager()
        val teamRepository: FakeTeamRepository = FakeTeamRepository()
        val documentRepository: FakeDocumentRepository = FakeDocumentRepository()
        val transferRepository: FakeTransferRepository = FakeTransferRepository()
        val auditRepository: FakeAuditRepository = FakeAuditRepository()
        val networkMonitor: FakeNetworkMonitor = FakeNetworkMonitor()
        val serviceController: FakeServiceController = FakeServiceController()
        val biometricAuthManager: FakeBiometricAuthManager = FakeBiometricAuthManager(isAvailable = true)

        val lanEngine: LanTransferEngine = LanTransferEngine(
            keyManager = keyManager,
            teamRepository = teamRepository
        )
        val relayEngine: RelayClientEngine = RelayClientEngine()

        val transferCoordinator: TransferCoordinator = TransferCoordinator(
            lanEngine = lanEngine,
            relayEngine = relayEngine,
            networkMonitor = networkMonitor,
            transferRepository = transferRepository,
            serviceController = serviceController,
            auditRepository = auditRepository,
            keyManager = keyManager
        )

        // 20 UseCases kết nối trực tiếp các tầng kiến trúc
        val createTeamUseCase = CreateTeamUseCase(teamRepository, keyManager, auditRepository)
        val disbandTeamUseCase = DisbandTeamUseCase(teamRepository, keyManager, auditRepository, transferCoordinator)
        val generateQrInviteUseCase = GenerateQrInviteUseCase(teamRepository, keyManager, auditRepository)
        val joinTeamViaQrUseCase = JoinTeamViaQrUseCase(teamRepository, keyManager, auditRepository)
        val getTeamMembersUseCase = GetTeamMembersUseCase(teamRepository)
        val revokeMemberUseCase = RevokeMemberUseCase(teamRepository, transferCoordinator, auditRepository)

        val publishDocumentUseCase = PublishDocumentUseCase(documentRepository, null, auditRepository)
        val getDocumentCatalogUseCase = GetDocumentCatalogUseCase(documentRepository)
        val openSecureDocumentUseCase = OpenSecureDocumentUseCase(
            documentRepository = documentRepository,
            fileProviderGateway = null,
            directoryManager = null,
            uriConverter = { file -> FakeUri("file", file.absolutePath) }
        )
        val deleteDocumentUseCase = DeleteDocumentUseCase(documentRepository, null, auditRepository, teamRepository)

        val authenticatePeerUseCase = AuthenticatePeerUseCase(teamRepository, keyManager)
        val executeLanTransferUseCase = ExecuteLanTransferUseCase(transferCoordinator)
        val executeRelayTransferUseCase = ExecuteRelayTransferUseCase(transferCoordinator)
        val verifyChecksumUseCase = VerifyChecksumUseCase(auditRepository)
        val controlSessionUseCase = ControlSessionUseCase(transferCoordinator, transferRepository, auditRepository)

        val handleSendIntentUseCase = HandleSendIntentUseCase(publishDocumentUseCase, auditRepository)
        val authenticateBiometricUseCase = AuthenticateBiometricUseCase(biometricAuthManager)
        val manageForegroundServiceUseCase = ManageForegroundServiceUseCase(serviceController)
        val monitorNetworkAdaptiveUseCase = MonitorNetworkAdaptiveUseCase(networkMonitor)
        val querySecurityAuditUseCase = QuerySecurityAuditUseCase(auditRepository)

        fun getPublicKey(): PublicKey = keyManager.getPublicKey(IKeyManager.MEMBER_IDENTITY_KEY_ALIAS)
            ?: keyManager.generateMemberIdentityKey()
    }

    val ownerPeer = PeerNode(
        peerId = "owner_peer_001",
        peerName = "Nguyễn Văn Owner",
        workingDir = File(baseWorkingDir, "owner_storage").apply { mkdirs() }
    )

    val memberPeer = PeerNode(
        peerId = "member_peer_002",
        peerName = "Trần Thị Member",
        workingDir = File(baseWorkingDir, "member_storage").apply { mkdirs() }
    )

    /**
     * Tạo tệp mẫu với dung lượng xác định để thử nghiệm truyền tải và băm SHA-256.
     */
    fun createSampleFile(parentDir: File, fileName: String, sizeBytes: Int): File {
        val file = File(parentDir, fileName)
        FileOutputStream(file).use { fos ->
            val chunk = ByteArray(minOf(64 * 1024, sizeBytes)) { (it % 256).toByte() }
            var written = 0
            while (written < sizeBytes) {
                val toWrite = minOf(chunk.size, sizeBytes - written)
                fos.write(chunk, 0, toWrite)
                written += toWrite
            }
        }
        return file
    }

    /**
     * Đồng bộ thông tin nhóm và thành viên giữa hai thiết bị giả lập
     * (mô phỏng quá trình truyền broadcast danh bạ nhóm).
     */
    suspend fun syncTeamState(teamId: String) {
        val ownerTeam = ownerPeer.teamRepository.teams[teamId]
        if (ownerTeam != null) {
            memberPeer.teamRepository.teams[teamId] = ownerTeam
        }
        val ownerMembers = ownerPeer.teamRepository.members[teamId]
        if (ownerMembers != null) {
            memberPeer.teamRepository.members[teamId] = ArrayList(ownerMembers)
        }
    }
}
