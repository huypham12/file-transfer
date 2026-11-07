package com.teamshare.app.domain.usecase.team

import com.teamshare.app.core.security.keystore.SoftwareKeyManager
import com.teamshare.app.core.transfer.FakeNetworkMonitor
import com.teamshare.app.core.transfer.FakeServiceController
import com.teamshare.app.core.transfer.FakeTransferRepository
import com.teamshare.app.core.transfer.TransferCoordinator
import com.teamshare.app.core.transfer.lan.LanTransferEngine
import com.teamshare.app.core.transfer.relay.RelayClientEngine
import com.teamshare.app.domain.model.AuditLog
import com.teamshare.app.domain.model.SessionStatus
import com.teamshare.app.domain.model.Team
import com.teamshare.app.domain.model.TeamMember
import com.teamshare.app.domain.model.TeamStatus
import com.teamshare.app.domain.model.TransferDirection
import com.teamshare.app.domain.model.TransferSession
import com.teamshare.app.domain.repository.IAuditRepository
import com.teamshare.app.domain.repository.ITeamRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

class FakeTeamRepository : ITeamRepository {
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
            return Result.success(Unit)
        }
        return Result.failure(NoSuchElementException())
    }

    override fun getMembersByTeam(teamId: String): Flow<List<TeamMember>> =
        flowOf(members[teamId] ?: emptyList())

    override suspend fun isMemberActive(teamId: String, memberId: String): Boolean {
        return members[teamId]?.find { it.memberId == memberId }?.isActive ?: false
    }

    val revocations = ConcurrentHashMap<String, MutableList<com.teamshare.app.data.local.db.entity.RevocationEntity>>()

    override suspend fun recordRevocation(revocation: com.teamshare.app.data.local.db.entity.RevocationEntity): Result<Unit> {
        revocations.getOrPut(revocation.teamId) { mutableListOf() }.add(revocation)
        return Result.success(Unit)
    }

    override suspend fun recordRevocations(revocationsList: List<com.teamshare.app.data.local.db.entity.RevocationEntity>): Result<Unit> {
        revocationsList.forEach { recordRevocation(it) }
        return Result.success(Unit)
    }

    override fun getRevocationsByTeam(teamId: String): Flow<List<com.teamshare.app.data.local.db.entity.RevocationEntity>> =
        flowOf(revocations[teamId] ?: emptyList())

    override suspend fun isMemberRevoked(teamId: String, memberId: String): Boolean =
        revocations[teamId]?.any { it.revokedMemberId == memberId } ?: false

    override suspend fun isTeamDisbanded(teamId: String): Boolean =
        revocations[teamId]?.any { it.revocationType == "TEAM_DISBAND" } ?: false

    override suspend fun getAllRevocationsList(teamId: String): List<com.teamshare.app.data.local.db.entity.RevocationEntity> =
        revocations[teamId] ?: emptyList()
}

class FakeAuditRepository : IAuditRepository {
    val logs = mutableListOf<AuditLog>()

    override suspend fun logEvent(log: AuditLog) {
        logs.add(log)
    }

    override fun getLogsByTeam(teamId: String): Flow<List<AuditLog>> =
        flowOf(logs.filter { it.teamId == teamId })

    override fun getRecentLogs(limit: Int): Flow<List<AuditLog>> =
        flowOf(logs.takeLast(limit))
}

class TeamUseCasesTest {

    private lateinit var teamRepository: FakeTeamRepository
    private lateinit var transferRepository: FakeTransferRepository
    private lateinit var keyManager: SoftwareKeyManager
    private lateinit var auditRepository: FakeAuditRepository
    private lateinit var coordinator: TransferCoordinator

    private lateinit var createTeamUseCase: CreateTeamUseCase
    private lateinit var disbandTeamUseCase: DisbandTeamUseCase

    @Before
    fun setUp() {
        teamRepository = FakeTeamRepository()
        transferRepository = FakeTransferRepository()
        keyManager = SoftwareKeyManager()
        auditRepository = FakeAuditRepository()

        coordinator = TransferCoordinator(
            lanEngine = LanTransferEngine(),
            relayEngine = RelayClientEngine(),
            networkMonitor = FakeNetworkMonitor(),
            transferRepository = transferRepository,
            serviceController = FakeServiceController(),
            ioDispatcher = Dispatchers.Unconfined
        )

        createTeamUseCase = CreateTeamUseCase(teamRepository, keyManager, auditRepository)
        disbandTeamUseCase = DisbandTeamUseCase(teamRepository, keyManager, auditRepository, coordinator)
    }

    @Test
    fun testCreateTeamSuccess() = runBlocking {
        val result = createTeamUseCase("Nhom Nghien Cuu", "Mo ta nhom").first()
        assertTrue(result.isSuccess)

        val team = result.getOrNull()
        assertNotNull(team)
        assertEquals("Nhom Nghien Cuu", team?.teamName)
        assertEquals(TeamStatus.ACTIVE, team?.status)
        assertTrue(team?.ownerFingerprint?.isNotEmpty() == true)

        // Verify owner is added as member
        val members = teamRepository.getMembersByTeam(team!!.teamId).first()
        assertEquals(1, members.size)
        assertTrue(members[0].isOwner)

        // Verify audit log
        assertEquals(1, auditRepository.logs.size)
    }

    @Test
    fun testCreateTeamValidationFailsOnBlankName() = runBlocking {
        val result = createTeamUseCase("   ", "").first()
        assertTrue(result.isFailure)
    }

    @Test
    fun testDisbandTeamSuccess() = runBlocking {
        val createResult = createTeamUseCase("Nhom Tam Thoi", "Sap giai tan").first()
        val team = createResult.getOrThrow()

        val disbandResult = disbandTeamUseCase(team.teamId).first()
        assertTrue(disbandResult.isSuccess)

        val updatedTeam = teamRepository.getTeamById(team.teamId).first()
        assertEquals(TeamStatus.DISBANDED, updatedTeam?.status)
        assertFalse(keyManager.hasKey("team_owner_${team.teamId}"))
    }

    @Test
    fun testDisbandTeamCancelsActiveTransferSessions() = runBlocking {
        val createResult = createTeamUseCase("Nhom Truyen Tai", "Mo ta").first()
        val team = createResult.getOrThrow()

        // Bắt đầu một session truyền tải gắn với teamId
        val session = TransferSession(
            sessionId = "sess_disband_01",
            docId = "doc_disband_01",
            peerMemberId = "mem_02",
            direction = TransferDirection.OUTGOING,
            totalBytes = 2048L,
            status = SessionStatus.TRANSFERRING
        )
        transferRepository.createSession(session)

        // Đăng ký session vào coordinator
        coordinator.startTransfer(
            session = session,
            peerIp = "192.168.1.100",
            teamId = team.teamId
        )

        val disbandResult = disbandTeamUseCase(team.teamId).first()
        assertTrue(disbandResult.isSuccess)

        val updatedSession = transferRepository.getSessionById("sess_disband_01")
        assertEquals(SessionStatus.CANCELLED, updatedSession?.status)
    }
}

