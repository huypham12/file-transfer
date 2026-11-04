package com.teamshare.app.presentation.viewmodel

import com.teamshare.app.core.security.keystore.SoftwareKeyManager
import com.teamshare.app.domain.model.MemberRole
import com.teamshare.app.domain.model.QrInvitePayload
import com.teamshare.app.domain.model.Team
import com.teamshare.app.domain.model.TeamMember
import com.teamshare.app.domain.model.TeamStatus
import com.teamshare.app.domain.repository.ITeamRepository
import com.teamshare.app.domain.usecase.member.GenerateQrInviteUseCase
import com.teamshare.app.domain.usecase.member.GetTeamMembersUseCase
import com.teamshare.app.domain.usecase.member.JoinTeamViaQrUseCase
import com.teamshare.app.domain.usecase.member.RevokeMemberUseCase
import com.teamshare.app.domain.usecase.team.CreateTeamUseCase
import com.teamshare.app.domain.usecase.team.DisbandTeamUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

class TeamViewModelTest {

    private class FakeTeamRepository : ITeamRepository {
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
            val list = members.getOrPut(member.teamId) { mutableListOf() }
            list.add(member)
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

    private lateinit var teamRepository: FakeTeamRepository
    private lateinit var keyManager: SoftwareKeyManager
    private lateinit var teamViewModel: TeamViewModel

    @Before
    fun setup() {
        teamRepository = FakeTeamRepository()
        keyManager = SoftwareKeyManager()

        val createTeamUseCase = CreateTeamUseCase(teamRepository, keyManager)
        val disbandTeamUseCase = DisbandTeamUseCase(teamRepository, keyManager, null)
        val generateQrInviteUseCase = GenerateQrInviteUseCase(teamRepository, keyManager)
        val joinTeamViaQrUseCase = JoinTeamViaQrUseCase(teamRepository, keyManager)
        val getTeamMembersUseCase = GetTeamMembersUseCase(teamRepository)
        val revokeMemberUseCase = RevokeMemberUseCase(teamRepository, null)

        teamViewModel = TeamViewModel(
            teamRepository = teamRepository,
            createTeamUseCase = createTeamUseCase,
            disbandTeamUseCase = disbandTeamUseCase,
            generateQrInviteUseCase = generateQrInviteUseCase,
            joinTeamViaQrUseCase = joinTeamViaQrUseCase,
            getTeamMembersUseCase = getTeamMembersUseCase,
            revokeMemberUseCase = revokeMemberUseCase,
            keyManager = keyManager,
            customScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        )
    }

    @Test
    fun `createTeam updates listUiState with newlyCreatedTeam`() = runBlocking {
        var createdId: String? = null
        teamViewModel.createTeam("Đội Alpha", "Nhóm nghiên cứu bảo mật") { id ->
            createdId = id
        }

        val state = teamViewModel.teamListUiState.value
        assertNotNull(createdId)
        assertNotNull(state.newlyCreatedTeam)
        assertEquals("Đội Alpha", state.newlyCreatedTeam?.teamName)
        assertNull(state.errorMessage)

        teamViewModel.clearNewlyCreatedTeam()
        assertNull(teamViewModel.teamListUiState.value.newlyCreatedTeam)
    }

    @Test
    fun `loadWorkspace populates team and members and identifies owner`() = runBlocking {
        val team = Team(
            teamId = "t1",
            teamName = "Team 1",
            description = "Desc",
            ownerFingerprint = "fp1"
        )
        teamRepository.createTeam(team)
        val ownerMember = TeamMember(
            memberId = "m1",
            teamId = "t1",
            memberName = "Alice",
            deviceFingerprint = "fp1",
            role = MemberRole.OWNER,
            credentialData = "cred_data_1"
        )
        teamRepository.addMember(ownerMember)

        teamViewModel.loadWorkspace("t1", currentMemberId = "m1")

        val wsState = teamViewModel.workspaceUiState.value
        assertNotNull(wsState.team)
        assertEquals("t1", wsState.team?.teamId)
        assertEquals(1, wsState.members.size)
        assertTrue(wsState.isOwner)
    }

    @Test
    fun `loadWorkspace automatically detects owner from keyManager when currentMemberId is null`() = runBlocking {
        val team = Team(
            teamId = "t-auto",
            teamName = "Auto Owner Team",
            description = "Desc",
            ownerFingerprint = "fp-owner-auto"
        )
        teamRepository.createTeam(team)
        val ownerMember = TeamMember(
            memberId = "fp-owner-auto",
            teamId = "t-auto",
            memberName = "Owner",
            deviceFingerprint = "fp-owner-auto",
            role = MemberRole.OWNER,
            credentialData = "cred_data"
        )
        teamRepository.addMember(ownerMember)

        // Sinh Master Key vào KeyManager đại diện cho thiết bị tạo nhóm
        keyManager.generateOwnerMasterKey("t-auto")

        // Điều hướng vào nhóm mà KHÔNG truyền currentMemberId
        teamViewModel.loadWorkspace("t-auto", currentMemberId = null)

        val wsState = teamViewModel.workspaceUiState.value
        assertNotNull(wsState.team)
        assertEquals("t-auto", wsState.team?.teamId)
        assertTrue("isOwner phải tự động được nhận diện là true", wsState.isOwner)
        assertEquals("fp-owner-auto", wsState.currentMemberId)
    }

    @Test
    fun `generateInviteQr creates valid QrInvitePayload`() = runBlocking {
        val team = Team(
            teamId = "t1",
            teamName = "Team 1",
            description = "Desc",
            ownerFingerprint = "fp1"
        )
        teamRepository.createTeam(team)
        keyManager.generateOwnerMasterKey("t1")

        teamViewModel.generateInviteQr("t1", ttlSec = 300L)

        val qrState = teamViewModel.qrInviteUiState.value
        assertNotNull(qrState.payload)
        assertEquals("t1", qrState.payload?.teamId)
        assertEquals(300L, qrState.remainingSeconds)
        assertFalse(qrState.isExpired)
    }

    @Test
    fun `disbandTeam marks team as disbanded and executes callback`() = runBlocking {
        val team = Team(
            teamId = "t-disband",
            teamName = "Team To Disband",
            description = "Desc",
            ownerFingerprint = "fp"
        )
        teamRepository.createTeam(team)

        var disbandedCallbackCalled = false
        teamViewModel.disbandTeam("t-disband") {
            disbandedCallbackCalled = true
        }

        assertTrue(disbandedCallbackCalled)
        assertTrue(teamViewModel.workspaceUiState.value.isDisbanded)
    }
}
