package com.teamshare.app.domain.usecase.member

import com.teamshare.app.core.platform.network.INetworkMonitor
import com.teamshare.app.core.platform.network.NetworkState
import com.teamshare.app.core.platform.network.NetworkType
import com.teamshare.app.core.security.keystore.SoftwareKeyManager
import com.teamshare.app.core.transfer.FakeNetworkMonitor
import com.teamshare.app.core.transfer.FakeServiceController
import com.teamshare.app.core.transfer.FakeTransferRepository
import com.teamshare.app.core.transfer.TransferCoordinator
import com.teamshare.app.core.transfer.lan.LanTransferEngine
import com.teamshare.app.core.transfer.relay.RelayClientEngine
import com.teamshare.app.domain.model.MemberRole
import com.teamshare.app.domain.model.QrInvitePayload
import com.teamshare.app.domain.model.SessionStatus
import com.teamshare.app.domain.model.Team
import com.teamshare.app.domain.model.TeamMember
import com.teamshare.app.domain.model.TeamStatus
import com.teamshare.app.domain.model.TransferDirection
import com.teamshare.app.domain.model.TransferSession
import com.teamshare.app.domain.usecase.team.FakeAuditRepository
import com.teamshare.app.domain.usecase.team.FakeTeamRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.system.measureTimeMillis

class MemberUseCasesTest {

    private lateinit var teamRepository: FakeTeamRepository
    private lateinit var keyManager: SoftwareKeyManager
    private lateinit var auditRepository: FakeAuditRepository
    private lateinit var transferRepository: FakeTransferRepository
    private lateinit var transferCoordinator: TransferCoordinator

    private lateinit var generateQrInviteUseCase: GenerateQrInviteUseCase
    private lateinit var joinTeamViaQrUseCase: JoinTeamViaQrUseCase
    private lateinit var getTeamMembersUseCase: GetTeamMembersUseCase
    private lateinit var revokeMemberUseCase: RevokeMemberUseCase

    private val testTeamId = "team_alpha_1"

    @Before
    fun setUp() {
        runBlocking {
            teamRepository = FakeTeamRepository()
            keyManager = SoftwareKeyManager()
            auditRepository = FakeAuditRepository()
            transferRepository = FakeTransferRepository()

            transferCoordinator = TransferCoordinator(
                lanEngine = LanTransferEngine(),
                relayEngine = RelayClientEngine(),
                networkMonitor = FakeNetworkMonitor(),
                transferRepository = transferRepository,
                serviceController = FakeServiceController(),
                ioDispatcher = Dispatchers.Unconfined
            )

            generateQrInviteUseCase = GenerateQrInviteUseCase(teamRepository, keyManager, auditRepository)
            joinTeamViaQrUseCase = JoinTeamViaQrUseCase(teamRepository, keyManager, auditRepository)
            getTeamMembersUseCase = GetTeamMembersUseCase(teamRepository)
            revokeMemberUseCase = RevokeMemberUseCase(teamRepository, transferCoordinator, auditRepository)

            // Setup test team
            val ownerKey = keyManager.generateOwnerMasterKey(testTeamId)
            val team = Team(
                teamId = testTeamId,
                teamName = "Alpha Team",
                description = "Team for testing",
                ownerFingerprint = "fingerprint_owner",
                status = TeamStatus.ACTIVE
            )
            teamRepository.createTeam(team)
            teamRepository.addMember(
                TeamMember(
                    memberId = "owner_id",
                    teamId = testTeamId,
                    memberName = "Owner",
                    deviceFingerprint = "fingerprint_owner",
                    role = MemberRole.OWNER,
                    credentialData = "sig_owner",
                    isActive = true
                )
            )
        }
    }

    @Test
    fun testGenerateQrInviteSuccess() = runBlocking {
        val result = generateQrInviteUseCase(testTeamId, ttlSec = 300L).first()
        assertTrue(result.isSuccess)

        val payload = result.getOrThrow()
        assertEquals(testTeamId, payload.teamId)
        assertTrue(payload.invitationToken.isNotEmpty())
        assertFalse(payload.isExpired())
        assertTrue(payload.signature.isNotEmpty())
    }

    @Test
    fun testJoinTeamViaQrSuccess() = runBlocking {
        val qrResult = generateQrInviteUseCase(testTeamId, ttlSec = 600L).first()
        val payload = qrResult.getOrThrow()

        val joinResult = joinTeamViaQrUseCase(payload, "Nguyen Van A").first()
        assertTrue(joinResult.isSuccess)

        val member = joinResult.getOrThrow()
        assertEquals("Nguyen Van A", member.memberName)
        assertEquals(MemberRole.MEMBER, member.role)
        assertTrue(member.isActive)

        val members = getTeamMembersUseCase(testTeamId).first()
        assertEquals(2, members.size)
    }

    @Test
    fun testJoinTeamFailsWhenExpired() = runBlocking {
        val expiredPayload = QrInvitePayload(
            teamId = testTeamId,
            invitationToken = "token_expired",
            expiresAt = (System.currentTimeMillis() / 1000L) - 100L, // in the past
            ownerPublicKey = "abcd",
            signature = "1234"
        )

        val joinResult = joinTeamViaQrUseCase(expiredPayload, "Nguyen Van B").first()
        assertTrue(joinResult.isFailure)
    }

    @Test
    fun testRevokeMemberTerminatesSessionRapidly() = runBlocking {
        // Add member to revoke
        val memberToRevoke = TeamMember(
            memberId = "bad_user_007",
            teamId = testTeamId,
            memberName = "Bad User",
            deviceFingerprint = "fp_bad",
            role = MemberRole.MEMBER,
            credentialData = "sig_bad",
            isActive = true
        )
        teamRepository.addMember(memberToRevoke)

        // Setup active session for this member
        val session = TransferSession(
            sessionId = "sess_revoke_test",
            docId = "doc_test",
            peerMemberId = "bad_user_007",
            direction = TransferDirection.OUTGOING,
            totalBytes = 1000L,
            status = SessionStatus.TRANSFERRING
        )
        transferRepository.createSession(session)

        val durationMs = measureTimeMillis {
            val result = revokeMemberUseCase(testTeamId, "bad_user_007").first()
            assertTrue(result.isSuccess)
        }

        assertTrue("Thu hồi thành viên phải hoàn tất trong < 200ms (thực tế: ${durationMs}ms)", durationMs < 200)

        // Verify member status in repository
        assertFalse(teamRepository.isMemberActive(testTeamId, "bad_user_007"))
        assertEquals(SessionStatus.CANCELLED, transferRepository.getSessionById("sess_revoke_test")?.status)
    }

    @Test
    fun testRevokeMemberFailsOnOwner() = runBlocking {
        val result = revokeMemberUseCase(testTeamId, "owner_id").first()
        assertTrue(result.isFailure)
    }
}
