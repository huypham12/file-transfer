package com.teamshare.app.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.teamshare.app.core.network.sync.PeerSyncManager
import com.teamshare.app.core.platform.network.NetworkAddressHelper
import com.teamshare.app.core.security.crypto.Sha256StreamCalculator
import com.teamshare.app.core.security.keystore.IKeyManager
import com.teamshare.app.domain.model.MemberRole
import com.teamshare.app.domain.model.QrInvitePayload
import com.teamshare.app.domain.model.TeamMember
import com.teamshare.app.domain.repository.ITeamRepository
import com.teamshare.app.domain.usecase.member.GenerateQrInviteUseCase
import com.teamshare.app.domain.usecase.member.GetTeamMembersUseCase
import com.teamshare.app.domain.usecase.member.JoinTeamViaQrUseCase
import com.teamshare.app.domain.usecase.member.RevokeMemberUseCase
import com.teamshare.app.domain.usecase.team.CreateTeamUseCase
import com.teamshare.app.domain.usecase.team.DisbandTeamUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel quản lý danh sách nhóm, tạo nhóm, quản lý thành viên,
 * sinh mã QR lời mời và gia nhập nhóm qua mã QR.
 */
class TeamViewModel(
    private val teamRepository: ITeamRepository,
    private val createTeamUseCase: CreateTeamUseCase,
    private val disbandTeamUseCase: DisbandTeamUseCase,
    private val generateQrInviteUseCase: GenerateQrInviteUseCase,
    private val joinTeamViaQrUseCase: JoinTeamViaQrUseCase,
    private val getTeamMembersUseCase: GetTeamMembersUseCase,
    private val revokeMemberUseCase: RevokeMemberUseCase,
    private val keyManager: IKeyManager? = null,
    private val peerSyncManager: PeerSyncManager? = null,
    private val customScope: CoroutineScope? = null
) : ViewModel() {

    private val scope: CoroutineScope get() = customScope ?: viewModelScope

    private val _teamListUiState = MutableStateFlow(TeamListUiState())
    val teamListUiState: StateFlow<TeamListUiState> = _teamListUiState.asStateFlow()

    private val _workspaceUiState = MutableStateFlow(TeamWorkspaceUiState())
    val workspaceUiState: StateFlow<TeamWorkspaceUiState> = _workspaceUiState.asStateFlow()

    private val _qrInviteUiState = MutableStateFlow(QrInviteUiState())
    val qrInviteUiState: StateFlow<QrInviteUiState> = _qrInviteUiState.asStateFlow()

    private var countdownJob: Job? = null
    private var workspaceObserveJob: Job? = null

    init {
        observeAllTeams()
    }

    /**
     * Lắng nghe biến động danh sách nhóm từ Repository.
     */
    fun observeAllTeams() {
        teamRepository.getAllTeams()
            .onEach { teams ->
                _teamListUiState.update { it.copy(teams = teams, isLoading = false, errorMessage = null) }
            }
            .catch { e ->
                _teamListUiState.update { it.copy(isLoading = false, errorMessage = e.message ?: "Lỗi tải danh sách nhóm") }
            }
            .launchIn(scope)
    }

    /**
     * Tạo nhóm mới (UC-01 - FR-TEAM-01).
     */
    fun createTeam(name: String, desc: String, ownerName: String = "", onSuccess: ((String) -> Unit)? = null) {
        scope.launch {
            _teamListUiState.update { it.copy(isLoading = true, errorMessage = null) }
            createTeamUseCase(name, desc, ownerName).collect { result ->
                result.fold(
                    onSuccess = { team ->
                        _teamListUiState.update {
                            it.copy(isLoading = false, newlyCreatedTeam = team, errorMessage = null)
                        }
                        onSuccess?.invoke(team.teamId)
                    },
                    onFailure = { error ->
                        _teamListUiState.update {
                            it.copy(isLoading = false, errorMessage = error.message ?: "Tạo nhóm thất bại")
                        }
                    }
                )
            }
        }
    }

    /**
     * Tải và quan sát Không gian làm việc nhóm (Workspace) theo teamId.
     */
    fun loadWorkspace(teamId: String, currentMemberId: String? = null) {
        workspaceObserveJob?.cancel()
        workspaceObserveJob = scope.launch {
            _workspaceUiState.update { it.copy(isLoading = true, errorMessage = null, isDisbanded = false) }

            val hasOwnerKey = keyManager?.hasKey(IKeyManager.MASTER_KEY_PREFIX + teamId) ?: false
            val initialTeam = teamRepository.getTeamById(teamId).firstOrNull()
            val initialMemberId = currentMemberId ?: (if (hasOwnerKey) initialTeam?.ownerFingerprint else null)

            // Khởi động Sync Server lắng nghe các peer trong nhóm (Direct LAN + Relay Room)
            val relayUrl = peerSyncManager?.getRelayUrlForTeam(teamId)
                ?: NetworkAddressHelper.getDefaultRelayUrl(endpoint = "v1/sync")
            peerSyncManager?.startSyncServer(
                teamId = teamId,
                relayUrl = relayUrl,
                scope = scope,
                memberId = initialMemberId,
                memberName = null
            )

            // Lắng nghe thông tin nhóm
            launch {
                teamRepository.getTeamById(teamId).collect { team ->
                    if (team == null) {
                        _workspaceUiState.update { it.copy(isLoading = false, errorMessage = "Không tìm thấy nhóm") }
                    } else {
                        val resolvedMemberId = currentMemberId
                            ?: (if (hasOwnerKey) team.ownerFingerprint else null)
                            ?: _workspaceUiState.value.currentMemberId

                        val isOwner = hasOwnerKey || (resolvedMemberId != null &&
                                _workspaceUiState.value.members.any { it.memberId == resolvedMemberId && it.role == MemberRole.OWNER })
                        _workspaceUiState.update {
                            it.copy(
                                team = team,
                                isOwner = isOwner,
                                currentMemberId = resolvedMemberId,
                                isLoading = false
                            )
                        }
                    }
                }
            }

            // Lắng nghe danh sách thành viên
            launch {
                getTeamMembersUseCase(teamId).collect { members ->
                    val team = _workspaceUiState.value.team
                    val myPubKey = try { keyManager?.getPublicKey(IKeyManager.MEMBER_IDENTITY_KEY_ALIAS) } catch (_: Exception) { null }
                    val myFingerprint = myPubKey?.encoded?.let { bytes: ByteArray -> Sha256StreamCalculator.calculateBytesSha256(bytes) }
                    val resolvedMemberId = currentMemberId
                        ?: (if (hasOwnerKey) team?.ownerFingerprint else null)
                        ?: _workspaceUiState.value.currentMemberId
                        ?: (if (myFingerprint != null) members.find { it.deviceFingerprint == myFingerprint }?.memberId else null)
                        ?: members.firstOrNull { it.role != MemberRole.OWNER }?.memberId

                    val isOwner = hasOwnerKey || (resolvedMemberId != null &&
                            members.any { it.memberId == resolvedMemberId && it.role == MemberRole.OWNER })
                    val myMember = members.find { it.memberId == resolvedMemberId }
                    if (myMember != null) {
                        peerSyncManager?.updateMemberIdentity(
                            memberId = myMember.memberId,
                            memberName = myMember.memberName,
                            teamId = teamId
                        )
                    }

                    _workspaceUiState.update {
                        it.copy(
                            members = members,
                            isOwner = isOwner,
                            currentMemberId = resolvedMemberId
                        )
                    }
                }
            }
        }
    }

    /**
     * Sinh mã QR lời mời có chữ ký số và khởi chạy đồng hồ đếm ngược (UC-03 - FR-MEM-01).
     */
    fun generateInviteQr(
        teamId: String,
        ttlSec: Long = 600L,
        customHost: String? = null,
        customRelayUrl: String? = null
    ) {
        countdownJob?.cancel()
        scope.launch {
            _qrInviteUiState.update { it.copy(isLoading = true, errorMessage = null) }

            // Tự động phát hiện IP Wi-Fi thật của máy tính từ Relay Server nếu chạy trên máy ảo
            if (NetworkAddressHelper.isEmulator()) {
                NetworkAddressHelper.autoDetectRelayHostFromEmulator()
            }

            val syncPort = peerSyncManager?.currentListeningPort?.takeIf { it > 0 }
                ?: NetworkAddressHelper.DEFAULT_SYNC_PORT

            // Đảm bảo Sync Server đang lắng nghe các peer trong nhóm
            val relayUrl = customRelayUrl ?: NetworkAddressHelper.getDefaultRelayUrl(endpoint = "v1/sync")
            val myMemId = _workspaceUiState.value.currentMemberId
            val myMemName = _workspaceUiState.value.members.find { it.memberId == myMemId }?.memberName ?: "Trưởng nhóm"
            peerSyncManager?.startSyncServer(
                teamId = teamId,
                preferredPort = syncPort,
                relayUrl = relayUrl,
                scope = scope,
                memberId = myMemId,
                memberName = myMemName
            )

            val resolvedHost = customHost ?: NetworkAddressHelper.getLocalIpv4Address()
            val relayForQr = if (NetworkAddressHelper.isEmulator() && NetworkAddressHelper.configuredRelayHost.isNotBlank()) {
                "ws://${NetworkAddressHelper.configuredRelayHost}:${NetworkAddressHelper.DEFAULT_RELAY_PORT}/v1/sync"
            } else {
                relayUrl
            }

            generateQrInviteUseCase(
                teamId = teamId,
                ttlSec = ttlSec,
                customHost = resolvedHost,
                port = peerSyncManager?.currentListeningPort ?: syncPort,
                customRelayUrl = relayForQr
            ).collect { result ->
                result.fold(
                    onSuccess = { payload ->
                        _qrInviteUiState.update {
                            it.copy(
                                payload = payload,
                                remainingSeconds = ttlSec,
                                isExpired = false,
                                isLoading = false,
                                errorMessage = null
                            )
                        }
                        startCountdown(ttlSec)
                    },
                    onFailure = { error ->
                        _qrInviteUiState.update {
                            it.copy(isLoading = false, errorMessage = error.message ?: "Không thể sinh mã QR lời mời")
                        }
                    }
                )
            }
        }
    }

    private fun startCountdown(initialSeconds: Long) {
        countdownJob?.cancel()
        countdownJob = scope.launch {
            var remaining = initialSeconds
            while (remaining > 0) {
                delay(1000L)
                remaining--
                _qrInviteUiState.update {
                    it.copy(
                        remainingSeconds = remaining,
                        isExpired = remaining <= 0L
                    )
                }
            }
        }
    }

    /**
     * Xử lý quét QR gia nhập nhóm (UC-04 - FR-MEM-02).
     */
    fun joinTeamViaQr(
        qrPayload: QrInvitePayload,
        memberName: String,
        onSuccess: (TeamMember) -> Unit
    ) {
        scope.launch {
            _teamListUiState.update { it.copy(isLoading = true, errorMessage = null) }
            joinTeamViaQrUseCase(qrPayload, memberName).collect { result ->
                result.fold(
                    onSuccess = { member ->
                        _teamListUiState.update { it.copy(isLoading = false, errorMessage = null) }
                        onSuccess(member)
                    },
                    onFailure = { error ->
                        _teamListUiState.update {
                            it.copy(isLoading = false, errorMessage = error.message ?: "Gia nhập nhóm thất bại")
                        }
                    }
                )
            }
        }
    }

    /**
     * Tước quyền thành viên khỏi nhóm (UC-06 - FR-MEM-04).
     */
    fun revokeMember(teamId: String, memberId: String) {
        scope.launch {
            _workspaceUiState.update { it.copy(isLoading = true, errorMessage = null, actionSuccessMessage = null) }
            revokeMemberUseCase(teamId, memberId).collect { result ->
                result.fold(
                    onSuccess = {
                        _workspaceUiState.update {
                            it.copy(isLoading = false, actionSuccessMessage = "Đã thu hồi quyền thành viên thành công")
                        }
                    },
                    onFailure = { error ->
                        _workspaceUiState.update {
                            it.copy(isLoading = false, errorMessage = error.message ?: "Không thể tước quyền thành viên")
                        }
                    }
                )
            }
        }
    }

    /**
     * Rời khỏi nhóm cộng tác dành cho Member (UC-06 - FR-MEM-04).
     */
    fun leaveTeam(teamId: String, memberId: String, onSuccess: () -> Unit) {
        scope.launch {
            _workspaceUiState.update { it.copy(isLoading = true, errorMessage = null) }
            teamRepository.leaveTeam(teamId, memberId).fold(
                onSuccess = {
                    _workspaceUiState.update {
                        it.copy(isLoading = false, isDisbanded = true, actionSuccessMessage = "Đã rời khỏi nhóm")
                    }
                    onSuccess()
                },
                onFailure = { error ->
                    _workspaceUiState.update {
                        it.copy(isLoading = false, errorMessage = error.message ?: "Không thể rời khỏi nhóm")
                    }
                }
            )
        }
    }

    /**
     * Giải tán nhóm (UC-02 - FR-TEAM-02).
     */
    fun disbandTeam(teamId: String, onSuccess: () -> Unit) {
        scope.launch {
            _workspaceUiState.update { it.copy(isLoading = true, errorMessage = null) }
            disbandTeamUseCase(teamId).collect { result ->
                result.fold(
                    onSuccess = {
                        _workspaceUiState.update {
                            it.copy(isLoading = false, isDisbanded = true, actionSuccessMessage = "Đã giải tán nhóm")
                        }
                        onSuccess()
                    },
                    onFailure = { error ->
                        _workspaceUiState.update {
                            it.copy(isLoading = false, errorMessage = error.message ?: "Không thể giải tán nhóm")
                        }
                    }
                )
            }
        }
    }

    fun clearNewlyCreatedTeam() {
        _teamListUiState.update { it.copy(newlyCreatedTeam = null) }
    }

    fun clearErrors() {
        _teamListUiState.update { it.copy(errorMessage = null) }
        _workspaceUiState.update { it.copy(errorMessage = null, actionSuccessMessage = null) }
        _qrInviteUiState.update { it.copy(errorMessage = null) }
    }

    override fun onCleared() {
        super.onCleared()
        countdownJob?.cancel()
        workspaceObserveJob?.cancel()
        peerSyncManager?.stopSyncServer()
    }
}
