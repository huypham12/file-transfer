package com.teamshare.app.domain.usecase.transfer

import com.teamshare.app.core.security.crypto.Sha256StreamCalculator
import com.teamshare.app.core.security.keystore.IKeyManager
import com.teamshare.app.core.security.keystore.SoftwareKeyManager
import com.teamshare.app.core.transfer.FakeNetworkMonitor
import com.teamshare.app.core.transfer.FakeServiceController
import com.teamshare.app.core.transfer.FakeTransferRepository
import com.teamshare.app.core.transfer.TransferCoordinator
import com.teamshare.app.core.transfer.lan.LanTransferEngine
import com.teamshare.app.core.transfer.relay.RelayClientEngine
import com.teamshare.app.domain.model.MemberRole
import com.teamshare.app.domain.model.SessionCmd
import com.teamshare.app.domain.model.SessionStatus
import com.teamshare.app.domain.model.Team
import com.teamshare.app.domain.model.TeamMember
import com.teamshare.app.domain.model.TeamMembershipCredential
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TransferUseCasesTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var teamRepository: FakeTeamRepository
    private lateinit var transferRepository: FakeTransferRepository
    private lateinit var auditRepository: FakeAuditRepository
    private lateinit var keyManager: SoftwareKeyManager
    private lateinit var coordinator: TransferCoordinator

    private lateinit var authenticatePeerUseCase: AuthenticatePeerUseCase
    private lateinit var verifyChecksumUseCase: VerifyChecksumUseCase
    private lateinit var controlSessionUseCase: ControlSessionUseCase

    private val testTeamId = "team_trf_01"

    @Before
    fun setUp() {
        teamRepository = FakeTeamRepository()
        transferRepository = FakeTransferRepository()
        auditRepository = FakeAuditRepository()
        keyManager = SoftwareKeyManager()

        coordinator = TransferCoordinator(
            lanEngine = LanTransferEngine(),
            relayEngine = RelayClientEngine(),
            networkMonitor = FakeNetworkMonitor(),
            transferRepository = transferRepository,
            serviceController = FakeServiceController(),
            ioDispatcher = Dispatchers.Unconfined
        )

        authenticatePeerUseCase = AuthenticatePeerUseCase(teamRepository, keyManager)
        verifyChecksumUseCase = VerifyChecksumUseCase(auditRepository)
        controlSessionUseCase = ControlSessionUseCase(coordinator, transferRepository, auditRepository)
    }

    @Test
    fun testAuthenticatePeerSuccess() = runBlocking {
        val ownerKey = keyManager.generateOwnerMasterKey(testTeamId)
        val team = Team(testTeamId, "Trf Team", "Desc", "fp_owner", TeamStatus.ACTIVE)
        teamRepository.createTeam(team)

        val member = TeamMember(
            memberId = "mem_valid_01",
            teamId = testTeamId,
            memberName = "Valid Member",
            deviceFingerprint = "fp_valid_01",
            role = MemberRole.MEMBER,
            credentialData = "sig_valid",
            isActive = true
        )
        teamRepository.addMember(member)

        val payload = "$testTeamId|mem_valid_01|MEMBER"
        val masterAlias = IKeyManager.MASTER_KEY_PREFIX + testTeamId
        val sigBytes = keyManager.signData(masterAlias, payload.toByteArray())
        val sigHex = sigBytes.joinToString("") { "%02x".format(it) }

        val cred = TeamMembershipCredential(
            teamId = testTeamId,
            memberId = "mem_valid_01",
            deviceFingerprint = "fp_valid_01",
            role = MemberRole.MEMBER,
            issuedAt = System.currentTimeMillis(),
            ownerSignature = sigHex
        )

        val result = authenticatePeerUseCase(cred, ownerKey).first()
        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow())
    }

    @Test
    fun testAuthenticatePeerFailsWhenMemberInactive() = runBlocking {
        val ownerKey = keyManager.generateOwnerMasterKey(testTeamId)
        val cred = TeamMembershipCredential(
            teamId = testTeamId,
            memberId = "mem_revoked",
            deviceFingerprint = "fp_revoked",
            role = MemberRole.MEMBER,
            issuedAt = System.currentTimeMillis(),
            ownerSignature = "abcd"
        )

        val result = authenticatePeerUseCase(cred, ownerKey).first()
        assertTrue(result.isFailure)
    }

    @Test
    fun testVerifyChecksumSuccess() = runBlocking {
        val file = tempFolder.newFile("good_file.bin")
        val content = "1234567890 TeamShare Data"
        file.writeText(content)
        val expectedSha256 = Sha256StreamCalculator.calculateFileSha256(file)

        val isVerified = verifyChecksumUseCase.execute(file, expectedSha256, "doc_good", testTeamId)
        assertTrue(isVerified)
        assertTrue(file.exists())
    }

    @Test
    fun testVerifyChecksumMismatchTriggersWipe() = runBlocking {
        val file = tempFolder.newFile("corrupt_file.bin")
        file.writeBytes(ByteArray(512) { 0xFF.toByte() })
        val wrongSha256 = "0000000000000000000000000000000000000000000000000000000000000000"

        val isVerified = verifyChecksumUseCase.execute(file, wrongSha256, "doc_bad", testTeamId)
        assertFalse(isVerified)
        // File must be wiped and deleted
        assertFalse(file.exists())
        assertEquals(1, auditRepository.logs.size)
    }

    @Test
    fun testControlSessionPauseResumeCancel() = runBlocking {
        val session = TransferSession(
            sessionId = "sess_ctrl_test",
            docId = "doc_ctrl",
            peerMemberId = "peer_01",
            direction = TransferDirection.OUTGOING,
            totalBytes = 2000L,
            status = SessionStatus.TRANSFERRING
        )
        transferRepository.createSession(session)

        val pauseResult = controlSessionUseCase("sess_ctrl_test", SessionCmd.PAUSE).first()
        assertTrue(pauseResult.isSuccess)
        assertEquals(SessionStatus.PAUSED, transferRepository.getSessionById("sess_ctrl_test")?.status)

        val resumeResult = controlSessionUseCase("sess_ctrl_test", SessionCmd.RESUME).first()
        assertTrue(resumeResult.isSuccess)
        assertEquals(SessionStatus.TRANSFERRING, transferRepository.getSessionById("sess_ctrl_test")?.status)

        val cancelResult = controlSessionUseCase("sess_ctrl_test", SessionCmd.CANCEL).first()
        assertTrue(cancelResult.isSuccess)
        assertEquals(SessionStatus.CANCELLED, transferRepository.getSessionById("sess_ctrl_test")?.status)
    }
}

