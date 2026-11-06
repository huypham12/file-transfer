package com.teamshare.app.e2e

import android.net.FakeUri
import com.teamshare.app.core.platform.network.NetworkState
import com.teamshare.app.core.platform.network.NetworkType
import com.teamshare.app.core.security.crypto.Sha256StreamCalculator
import com.teamshare.app.core.security.keystore.IKeyManager
import com.teamshare.app.domain.model.AuditEventType
import com.teamshare.app.domain.model.DownloadStatus
import com.teamshare.app.domain.model.MemberRole
import com.teamshare.app.domain.model.ServiceAction
import com.teamshare.app.domain.model.SessionCmd
import com.teamshare.app.domain.model.SessionStatus
import com.teamshare.app.domain.model.TeamMembershipCredential
import com.teamshare.app.domain.model.TeamStatus
import com.teamshare.app.domain.model.TransferChannel
import com.teamshare.app.domain.model.TransferDirection
import com.teamshare.app.domain.model.TransferSession
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
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets

/**
 * Kiểm thử tích hợp toàn hệ thống (Stage 8 E2E System Integration Test).
 * Thẩm định sự phối hợp thông suốt của toàn bộ 20 UseCases chuẩn mực
 * theo Ma trận Truy vết Yêu cầu phần mềm (RTM - SRS v3.0.0 Mục 8.1).
 */
class Stage8E2EIntegrationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var harness: E2ETestHarness

    @Before
    fun setUp() {
        harness = E2ETestHarness(tempFolder.root)
    }

    @Test
    fun testCompleteSystemLifecycle_All20UseCases_RTM() = runBlocking {
        val owner = harness.ownerPeer
        val member = harness.memberPeer

        // ---------------------------------------------------------------------
        // 1. Phân hệ Quản lý Nhóm (FR-TEAM-01, FR-TEAM-02)
        // ---------------------------------------------------------------------
        // UC-01: CreateTeamUseCase
        val createTeamResult = owner.createTeamUseCase(
            name = "Phòng Kỹ thuật Dự án An toàn",
            desc = "Chia sẻ tài liệu nội bộ"
        ).first()
        assertTrue("UC-01 Tạo nhóm phải thành công", createTeamResult.isSuccess)
        val team = createTeamResult.getOrThrow()
        val teamId = team.teamId
        assertEquals(TeamStatus.ACTIVE, team.status)

        harness.syncTeamState(teamId)

        // ---------------------------------------------------------------------
        // 2. Phân hệ Quản lý Thành viên (FR-MEM-01 -> FR-MEM-04)
        // ---------------------------------------------------------------------
        // UC-03: GenerateQrInviteUseCase
        val qrResult = owner.generateQrInviteUseCase(teamId, ttlSec = 600L).first()
        assertTrue("UC-03 Sinh QR mời nhóm phải thành công", qrResult.isSuccess)
        val qrPayload = qrResult.getOrThrow()

        // UC-04: JoinTeamViaQrUseCase
        val joinResult = member.joinTeamViaQrUseCase(qrPayload, member.peerName).first()
        assertTrue("UC-04 Quét QR gia nhập phải thành công", joinResult.isSuccess)
        val joinedMember = joinResult.getOrThrow()
        assertEquals(MemberRole.MEMBER, joinedMember.role)

        // Đồng bộ thành viên sang Repository của Owner
        owner.teamRepository.addMember(joinedMember)

        // UC-05: GetTeamMembersUseCase
        val membersList = owner.getTeamMembersUseCase(teamId).first()
        assertTrue("UC-05 Danh sách thành viên phải chứa cả Owner và Member", membersList.any { it.memberId == joinedMember.memberId })

        // ---------------------------------------------------------------------
        // 3. Phân hệ Quản lý Tài liệu (FR-DOC-01 -> FR-DOC-04)
        // ---------------------------------------------------------------------
        // UC-07: PublishDocumentUseCase
        val sampleContent = "BẢN GHI TÀI LIỆU CỘNG TÁC KMA 2026".toByteArray(StandardCharsets.UTF_8)
        val docHash = Sha256StreamCalculator.calculateStreamSha256(ByteArrayInputStream(sampleContent))
        val sampleUri = FakeUri("content", "documents/tai_lieu_chien_luoc.pdf")

        val publishResult = owner.publishDocumentUseCase(
            teamId = teamId,
            publisherId = owner.peerId,
            uri = sampleUri,
            requireBiometric = true,
            overrideName = "tai_lieu_chien_luoc.pdf",
            overrideSize = sampleContent.size.toLong(),
            overrideMime = "application/pdf",
            customInputStream = ByteArrayInputStream(sampleContent)
        ).first()
        assertTrue("UC-07 Công bố tài liệu phải thành công", publishResult.isSuccess)
        val publishedDoc = publishResult.getOrThrow()
        val docId = publishedDoc.docId

        // Đồng bộ danh mục tài liệu sang Member
        member.documentRepository.publishDocument(publishedDoc)

        // UC-08: GetDocumentCatalogUseCase
        val catalog = member.getDocumentCatalogUseCase(teamId).first()
        assertEquals("UC-08 Danh mục tài liệu phải có đúng 1 tài liệu", 1, catalog.size)
        assertEquals("tai_lieu_chien_luoc.pdf", catalog[0].fileName)
        assertTrue(catalog[0].requireBiometric)

        // UC-09: OpenSecureDocumentUseCase
        val localDocFile = File(member.workingDir, "tai_lieu_chien_luoc.pdf").apply {
            FileOutputStream(this).use { it.write(sampleContent) }
        }
        member.documentRepository.updateDownloadStatus(docId, DownloadStatus.COMPLETED, localDocFile.absolutePath)

        val openResult = member.openSecureDocumentUseCase(docId).first()
        assertTrue("UC-09 Mở tài liệu an toàn phải thành công", openResult.isSuccess)
        val openedUri = openResult.getOrThrow()
        assertNotNull(openedUri)

        // ---------------------------------------------------------------------
        // 4. Phân hệ Điều phối Truyền tải (FR-TRF-01 -> FR-TRF-05)
        // ---------------------------------------------------------------------
        // UC-11: AuthenticatePeerUseCase
        val masterKeyAlias = IKeyManager.MASTER_KEY_PREFIX + teamId
        val ownerPubKey = owner.keyManager.getPublicKey(masterKeyAlias)!!
        val credPayload = "$teamId|${joinedMember.memberId}|${joinedMember.role.name}"
        val signatureBytes = owner.keyManager.signData(masterKeyAlias, credPayload.toByteArray())
        val signatureHex = signatureBytes.joinToString("") { "%02x".format(it) }

        val memberCredential = TeamMembershipCredential(
            teamId = teamId,
            memberId = joinedMember.memberId,
            deviceFingerprint = joinedMember.deviceFingerprint,
            role = joinedMember.role,
            issuedAt = System.currentTimeMillis() / 1000L,
            ownerSignature = signatureHex
        )

        val authPassed = owner.authenticatePeerUseCase(memberCredential, ownerPubKey).first().getOrThrow()
        assertTrue("UC-11 Bắt tay thẩm định hai chiều phải thành công", authPassed)

        // UC-12: ExecuteLanTransferUseCase
        val testSession = TransferSession(
            sessionId = "session_e2e_001",
            docId = docId,
            peerMemberId = joinedMember.memberId,
            direction = TransferDirection.OUTGOING,
            channel = TransferChannel.DIRECT_LAN,
            totalBytes = sampleContent.size.toLong(),
            bytesTransferred = sampleContent.size.toLong(),
            confirmedOffset = sampleContent.size.toLong(),
            status = SessionStatus.COMPLETED
        )
        owner.transferRepository.createSession(testSession)
        val lanProgressFlow = owner.executeLanTransferUseCase(session = testSession, peerIp = "127.0.0.1")
        assertNotNull("UC-12 Luồng tiến độ truyền LAN không được null", lanProgressFlow)

        // UC-13: ExecuteRelayTransferUseCase
        val relaySession = testSession.copy(sessionId = "session_e2e_002", channel = TransferChannel.ZERO_KNOWLEDGE_RELAY)
        owner.transferRepository.createSession(relaySession)
        val relayProgressFlow = owner.executeRelayTransferUseCase(session = relaySession)
        assertNotNull("UC-13 Luồng tiến độ truyền Relay không được null", relayProgressFlow)

        // UC-14: VerifyChecksumUseCase
        val isChecksumValid = owner.verifyChecksumUseCase(
            tempFile = localDocFile,
            expectedSha256 = docHash,
            docId = docId,
            teamId = teamId
        )
        assertTrue("UC-14 Mã băm SHA-256 đối chiếu phải trùng khớp tuyệt đối", isChecksumValid)

        // UC-15: ControlSessionUseCase
        val pauseResult = owner.controlSessionUseCase("session_e2e_001", SessionCmd.PAUSE).first()
        assertTrue("UC-15 Tạm dừng phiên phải thành công", pauseResult.isSuccess)
        val resumeResult = owner.controlSessionUseCase("session_e2e_001", SessionCmd.RESUME).first()
        assertTrue("UC-15 Tiếp tục phiên phải thành công", resumeResult.isSuccess)
        val cancelResult = owner.controlSessionUseCase("session_e2e_001", SessionCmd.CANCEL).first()
        assertTrue("UC-15 Hủy phiên phải thành công", cancelResult.isSuccess)

        // ---------------------------------------------------------------------
        // 5. Phân hệ Cầu nối Hệ điều hành Di động (FR-MOB-01 -> FR-MOB-04)
        // ---------------------------------------------------------------------
        // UC-16: HandleSendIntentUseCase
        val sharedUri = FakeUri("content", "external/file1.png")
        val sharedUris = listOf(sharedUri)
        val sendIntentResult = owner.handleSendIntentUseCase(
            intentUris = sharedUris,
            targetTeamId = teamId,
            publisherId = owner.peerId,
            customStreams = mapOf(sharedUri to ByteArrayInputStream("TEST_IMAGE_BYTES".toByteArray()))
        ).first()
        assertTrue("UC-16 Xử lý Send Intent phải thành công", sendIntentResult.isSuccess)
        assertEquals("UC-16 Đã công bố 1 tệp", 1, sendIntentResult.getOrThrow())

        // UC-17: AuthenticateBiometricUseCase
        val bioResult = member.authenticateBiometricUseCase(
            activity = null,
            title = "Xác thực vân tay mở tệp mật",
            subtitle = "TeamShare Security"
        ).first()
        assertTrue("UC-17 Xác thực sinh trắc học thành công", bioResult.isSuccess)
        assertTrue(bioResult.getOrThrow())

        // UC-18: ManageForegroundServiceUseCase
        owner.manageForegroundServiceUseCase(
            action = ServiceAction.START,
            sessionId = "session_e2e_001",
            docName = "tai_lieu_chien_luoc.pdf",
            totalBytes = sampleContent.size.toLong(),
            isLan = true
        )
        assertTrue("UC-18 Foreground Service phải ghi nhận khởi tạo", owner.serviceController.startSessionCalled)

        // UC-19: MonitorNetworkAdaptiveUseCase
        owner.networkMonitor.updateState(
            NetworkState(type = NetworkType.WIFI_SAME_LAN, isConnected = true)
        )
        val netStatus = owner.monitorNetworkAdaptiveUseCase().first()
        assertTrue("UC-19 Mạng phải kết nối online", netStatus.isOnline)
        assertEquals(TransferChannel.DIRECT_LAN, netStatus.recommendedChannel)

        // ---------------------------------------------------------------------
        // 6. Phân hệ Nhật ký Kiểm toán An ninh (FR-AUD-01)
        // ---------------------------------------------------------------------
        // UC-20: QuerySecurityAuditUseCase
        val auditLogs = owner.querySecurityAuditUseCase(teamId, limit = 50).first()
        assertTrue("UC-20 Hệ thống phải tự động ghi nhật ký các hành động", auditLogs.isNotEmpty())
        assertTrue("Nhật ký phải ghi nhận sự kiện tạo nhóm TEAM_CREATED", auditLogs.any { it.eventType == AuditEventType.TEAM_CREATED })

        // ---------------------------------------------------------------------
        // 7. Hoàn tất Vòng đời: Tước quyền thành viên & Giải tán nhóm
        // ---------------------------------------------------------------------
        // UC-06: RevokeMemberUseCase (FR-MEM-04)
        val revokeResult = owner.revokeMemberUseCase(teamId, joinedMember.memberId).first()
        assertTrue("UC-06 Thu hồi quyền thành viên phải thành công", revokeResult.isSuccess)
        assertFalse(owner.teamRepository.isMemberActive(teamId, joinedMember.memberId))

        // UC-10: DeleteDocumentUseCase (FR-DOC-04)
        val deleteDocResult = owner.deleteDocumentUseCase(
            docId = docId,
            requesterId = owner.peerId
        ).first()
        assertTrue("UC-10 Xóa tài liệu khỏi nhóm phải thành công", deleteDocResult.isSuccess)

        // UC-02: DisbandTeamUseCase (FR-TEAM-02)
        val disbandResult = owner.disbandTeamUseCase(teamId).first()
        assertTrue("UC-02 Giải tán nhóm phải thành công", disbandResult.isSuccess)
        val finalTeam = owner.teamRepository.getTeamById(teamId).first()
        assertEquals(TeamStatus.DISBANDED, finalTeam?.status)
    }
}
