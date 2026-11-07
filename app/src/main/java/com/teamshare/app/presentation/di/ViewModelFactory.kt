package com.teamshare.app.presentation.di

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.teamshare.app.core.network.sync.PeerSyncManager
import com.teamshare.app.core.platform.network.AndroidNetworkMonitor
import com.teamshare.app.core.platform.network.NetworkAddressHelper
import com.teamshare.app.core.platform.service.TransferServiceController
import com.teamshare.app.core.security.biometric.BiometricAuthManager
import com.teamshare.app.core.security.keystore.AndroidKeystoreKeyManager
import com.teamshare.app.core.storage.saf.FileProviderGateway
import com.teamshare.app.core.storage.saf.SafStorageGateway
import com.teamshare.app.core.storage.saf.StorageDirectoryManager
import com.teamshare.app.core.transfer.TransferCoordinator
import com.teamshare.app.core.transfer.lan.LanTransferEngine
import com.teamshare.app.core.transfer.relay.RelayClientEngine
import com.teamshare.app.data.local.db.AppDatabase
import com.teamshare.app.data.repository.AuditRepositoryImpl
import com.teamshare.app.data.repository.DocumentRepositoryImpl
import com.teamshare.app.data.repository.FolderRepositoryImpl
import com.teamshare.app.data.repository.TeamRepositoryImpl
import com.teamshare.app.data.repository.TransferRepositoryImpl
import com.teamshare.app.domain.model.SessionStatus
import com.teamshare.app.domain.model.TransferChannel
import com.teamshare.app.domain.model.TransferDirection
import com.teamshare.app.domain.model.TransferSession
import com.teamshare.app.domain.usecase.audit.QuerySecurityAuditUseCase
import com.teamshare.app.domain.usecase.document.DeleteDocumentUseCase
import com.teamshare.app.domain.usecase.document.GetDocumentCatalogUseCase
import com.teamshare.app.domain.usecase.document.OpenSecureDocumentUseCase
import com.teamshare.app.domain.usecase.document.PublishDocumentUseCase
import com.teamshare.app.domain.usecase.member.GenerateQrInviteUseCase
import com.teamshare.app.domain.usecase.member.GetTeamMembersUseCase
import com.teamshare.app.domain.usecase.member.JoinTeamViaQrUseCase
import com.teamshare.app.domain.usecase.member.RevokeMemberUseCase
import com.teamshare.app.domain.usecase.platform.AuthenticateBiometricUseCase
import com.teamshare.app.domain.usecase.platform.HandleSendIntentUseCase
import com.teamshare.app.domain.usecase.team.CreateTeamUseCase
import com.teamshare.app.domain.usecase.team.DisbandTeamUseCase
import com.teamshare.app.domain.usecase.transfer.ControlSessionUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.teamshare.app.presentation.viewmodel.AuditViewModel
import com.teamshare.app.presentation.viewmodel.DocumentViewModel
import com.teamshare.app.presentation.viewmodel.TeamViewModel
import com.teamshare.app.presentation.viewmodel.TransferViewModel

/**
 * Nhà máy cấp phát ViewModels (Dependency Injection) cho tầng Presentation.
 * Khởi tạo trễ (Lazy initialization) toàn bộ ngăn xếp Clean Architecture.
 */
class ViewModelFactory(private val context: Context) : ViewModelProvider.Factory {

    private val keyManager by lazy { AndroidKeystoreKeyManager(context) }
    private val database by lazy { AppDatabase.getInstance(context, keyManager) }

    private val teamRepo by lazy { TeamRepositoryImpl(database.teamDao(), database.memberDao(), keyManager, database.revocationDao()) }
    private val docRepo by lazy { DocumentRepositoryImpl(database.documentDao()) }
    private val folderRepo by lazy { FolderRepositoryImpl(database.folderDao()) }
    private val transferRepo by lazy { TransferRepositoryImpl(database.transferSessionDao()) }
    private val auditRepo by lazy { AuditRepositoryImpl(database.auditDao()) }

    private val safGateway by lazy { SafStorageGateway(context) }
    private val fileProvider by lazy { FileProviderGateway(context) }
    private val dirManager by lazy { StorageDirectoryManager(context) }
    private val networkMonitor by lazy { AndroidNetworkMonitor(context) }

    private val lanEngine by lazy { LanTransferEngine() }
    private val relayEngine by lazy { RelayClientEngine() }

    val transferCoordinator by lazy {
        TransferCoordinator(
            lanEngine = lanEngine,
            relayEngine = relayEngine,
            networkMonitor = networkMonitor,
            transferRepository = transferRepo,
            serviceController = TransferServiceController,
            directoryManager = dirManager,
            auditRepository = auditRepo,
            keyManager = keyManager,
            documentRepository = docRepo,
            teamRepository = teamRepo
        )
    }

    val peerSyncManager by lazy {
        PeerSyncManager(
            teamRepository = teamRepo,
            documentRepository = docRepo,
            auditRepository = auditRepo,
            keyManager = keyManager,
            context = context,
            folderRepository = folderRepo
        ).apply {
            onFileRequestedListener = { req, doc, file ->
                val useLan = req.preferredChannel == "DIRECT_LAN" && !req.requesterHost.isNullOrBlank() && req.requesterPort > 0
                val channel = if (useLan) TransferChannel.DIRECT_LAN else TransferChannel.ZERO_KNOWLEDGE_RELAY
                val outSession = TransferSession(
                    sessionId = req.streamId,
                    docId = doc.docId,
                    peerMemberId = req.requesterId,
                    direction = TransferDirection.OUTGOING,
                    channel = channel,
                    status = SessionStatus.TRANSFERRING,
                    totalBytes = doc.fileSize,
                    bytesTransferred = 0L
                )
                val relayStreamUrl = if (useLan) null else NetworkAddressHelper.getDefaultRelayUrl(endpoint = "v1/stream")
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        transferCoordinator.startTransfer(
                            session = outSession,
                            file = file,
                            peerIp = if (useLan) req.requesterHost else null,
                            port = if (useLan) req.requesterPort else 0,
                            streamId = req.streamId,
                            relayUrl = relayStreamUrl,
                            documentName = doc.fileName,
                            teamId = req.teamId,
                            expectedSha256 = doc.expectedSha256
                        ).collect { progress ->
                            // Ghi nhận tiến độ truyền gửi
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("TransferCoordinator", "Lỗi truyền gửi Sender cho streamId=${req.streamId}: ${e.message}", e)
                    }
                }
            }
            onSessionAbortRequested = { memberId, teamId ->
                CoroutineScope(Dispatchers.IO).launch {
                    if (memberId != null) {
                        transferCoordinator.cancelSessionForMember(memberId)
                    } else if (teamId != null) {
                        transferCoordinator.cancelSessionsForTeam(teamId)
                    }
                }
            }
        }
    }

    val teamViewModel by lazy {
        TeamViewModel(
            teamRepository = teamRepo,
            createTeamUseCase = CreateTeamUseCase(teamRepo, keyManager, auditRepo),
            disbandTeamUseCase = DisbandTeamUseCase(teamRepo, keyManager, auditRepo, transferCoordinator, peerSyncManager),
            generateQrInviteUseCase = GenerateQrInviteUseCase(teamRepo, keyManager, auditRepo),
            joinTeamViaQrUseCase = JoinTeamViaQrUseCase(
                teamRepository = teamRepo,
                keyManager = keyManager,
                auditRepository = auditRepo,
                peerSyncGateway = peerSyncManager,
                documentRepository = docRepo
            ),
            getTeamMembersUseCase = GetTeamMembersUseCase(teamRepo),
            revokeMemberUseCase = RevokeMemberUseCase(teamRepo, transferCoordinator, auditRepo, keyManager, peerSyncManager),
            keyManager = keyManager,
            peerSyncManager = peerSyncManager
        )
    }

    private val biometricManager by lazy { BiometricAuthManager(context) }
    private val publishDocUseCase by lazy { PublishDocumentUseCase(docRepo, safGateway, auditRepo, dirManager) }
    private val biometricUseCase by lazy { AuthenticateBiometricUseCase(biometricManager) }
    private val handleSendIntentUseCase by lazy { HandleSendIntentUseCase(publishDocUseCase, auditRepo) }

    val documentViewModel by lazy {
        DocumentViewModel(
            publishDocumentUseCase = publishDocUseCase,
            getDocumentCatalogUseCase = GetDocumentCatalogUseCase(docRepo),
            openSecureDocumentUseCase = OpenSecureDocumentUseCase(docRepo, fileProvider, dirManager),
            deleteDocumentUseCase = DeleteDocumentUseCase(docRepo, dirManager, auditRepo, teamRepo),
            authenticateBiometricUseCase = biometricUseCase,
            handleSendIntentUseCase = handleSendIntentUseCase,
            peerSyncManager = peerSyncManager,
            folderRepository = folderRepo
        )
    }

    val transferViewModel by lazy {
        TransferViewModel(
            controlSessionUseCase = ControlSessionUseCase(transferCoordinator, transferRepo, auditRepo),
            transferCoordinator = transferCoordinator,
            peerSyncManager = peerSyncManager
        )
    }

    val auditViewModel by lazy {
        AuditViewModel(
            querySecurityAuditUseCase = QuerySecurityAuditUseCase(auditRepo),
            peerSyncManager = peerSyncManager
        )
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when {
            modelClass.isAssignableFrom(TeamViewModel::class.java) -> teamViewModel as T
            modelClass.isAssignableFrom(DocumentViewModel::class.java) -> documentViewModel as T
            modelClass.isAssignableFrom(TransferViewModel::class.java) -> transferViewModel as T
            modelClass.isAssignableFrom(AuditViewModel::class.java) -> auditViewModel as T
            else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}

