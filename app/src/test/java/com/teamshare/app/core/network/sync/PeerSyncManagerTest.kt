package com.teamshare.app.core.network.sync

import com.teamshare.app.domain.model.AuditLog
import com.teamshare.app.domain.model.DownloadStatus
import com.teamshare.app.domain.model.MemberRole
import com.teamshare.app.domain.model.QrInvitePayload
import com.teamshare.app.domain.model.SharedDocument
import com.teamshare.app.domain.model.Team
import com.teamshare.app.domain.model.TeamMember
import com.teamshare.app.domain.model.TeamStatus
import com.teamshare.app.domain.repository.IAuditRepository
import com.teamshare.app.domain.repository.IDocumentRepository
import com.teamshare.app.domain.repository.ITeamRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

class PeerSyncManagerTest {

    private class TestTeamRepository : ITeamRepository {
        val members = ConcurrentHashMap<String, MutableList<TeamMember>>()

        override suspend fun createTeam(team: Team): Result<Team> = Result.success(team)
        override fun getTeamById(teamId: String): Flow<Team?> = flowOf(null)
        override fun getAllTeams(): Flow<List<Team>> = flowOf(emptyList())
        override suspend fun disbandTeam(teamId: String): Result<Unit> = Result.success(Unit)

        override suspend fun addMember(member: TeamMember): Result<Unit> {
            val list = members.getOrPut(member.teamId) { mutableListOf() }
            list.removeAll { it.memberId == member.memberId }
            list.add(member)
            return Result.success(Unit)
        }

        override fun getMembersByTeam(teamId: String): Flow<List<TeamMember>> {
            return flowOf(members[teamId] ?: emptyList())
        }

        override suspend fun isMemberActive(teamId: String, memberId: String): Boolean {
            return members[teamId]?.any { it.memberId == memberId && it.isActive } ?: false
        }

        override suspend fun revokeMember(teamId: String, memberId: String): Result<Unit> = Result.success(Unit)

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

    private class TestDocumentRepository : IDocumentRepository {
        val docs = ConcurrentHashMap<String, SharedDocument>()

        override suspend fun publishDocument(document: SharedDocument): Result<SharedDocument> {
            docs[document.docId] = document
            return Result.success(document)
        }

        override fun getDocumentsByTeam(teamId: String): Flow<List<SharedDocument>> {
            return flowOf(docs.values.filter { it.teamId == teamId })
        }

        override suspend fun getDocumentById(docId: String): SharedDocument? = docs[docId]

        override suspend fun updateDownloadStatus(docId: String, status: DownloadStatus, localUri: String?) {
            val d = docs[docId] ?: return
            docs[docId] = d.copy(downloadStatus = status, localUri = localUri ?: d.localUri)
        }

        override suspend fun deleteDocument(docId: String): Result<Unit> {
            docs.remove(docId)
            return Result.success(Unit)
        }
    }

    private class TestAuditRepository : IAuditRepository {
        val logs = mutableListOf<AuditLog>()
        override suspend fun logEvent(log: AuditLog) {
            logs.add(log)
        }
        override fun getLogsByTeam(teamId: String): Flow<List<AuditLog>> = flowOf(logs.filter { it.teamId == teamId })
        override fun getRecentLogs(limit: Int): Flow<List<AuditLog>> = flowOf(logs.takeLast(limit))
    }

    private lateinit var ownerTeamRepo: TestTeamRepository
    private lateinit var ownerDocRepo: TestDocumentRepository
    private lateinit var ownerAuditRepo: TestAuditRepository
    private lateinit var ownerSyncManager: PeerSyncManager

    private lateinit var memberTeamRepo: TestTeamRepository
    private lateinit var memberDocRepo: TestDocumentRepository
    private lateinit var memberSyncManager: PeerSyncManager

    private val testScope = CoroutineScope(Dispatchers.IO)

    @Before
    fun setUp() {
        ownerTeamRepo = TestTeamRepository()
        ownerDocRepo = TestDocumentRepository()
        ownerAuditRepo = TestAuditRepository()
        ownerSyncManager = PeerSyncManager(ownerTeamRepo, ownerDocRepo, ownerAuditRepo)

        memberTeamRepo = TestTeamRepository()
        memberDocRepo = TestDocumentRepository()
        memberSyncManager = PeerSyncManager(memberTeamRepo, memberDocRepo)
    }

    @After
    fun tearDown() {
        ownerSyncManager.stopSyncServer()
        memberSyncManager.stopSyncServer()
    }

    @Test
    fun testJoinHandshake_syncsMemberAndReturnsCatalog() = runBlocking {
        val teamId = "team_alpha"

        // Owner thêm 1 tài liệu ban đầu vào CSDL của Owner
        ownerDocRepo.publishDocument(
            SharedDocument(
                docId = "doc_init",
                teamId = teamId,
                publisherId = "owner_id",
                fileName = "readme.txt",
                fileSize = 1024L,
                mimeType = "text/plain",
                expectedSha256 = "dummy_sha",
                downloadStatus = DownloadStatus.COMPLETED
            )
        )

        // Owner khởi động Sync Server trên cổng ngẫu nhiên khả dụng
        val boundPort = ownerSyncManager.startSyncServer(teamId, preferredPort = 0, scope = testScope)
        assertTrue(boundPort > 0)

        // Member B quét được mã QR từ Owner (mang ownerHost là 127.0.0.1 và port đã bind)
        val qrPayload = QrInvitePayload(
            teamId = teamId,
            invitationToken = "token_xyz",
            expiresAt = System.currentTimeMillis() / 1000L + 600L,
            ownerPublicKey = "pubkey_owner",
            signature = "sig_owner",
            teamName = "Test Team",
            ownerHost = "127.0.0.1",
            ownerPort = boundPort
        )

        val newMember = TeamMember(
            memberId = "member_b",
            teamId = teamId,
            memberName = "Huy Member",
            deviceFingerprint = "fingerprint_b",
            role = MemberRole.MEMBER,
            credentialData = "cred_data"
        )

        // Member gửi Join Request qua socket tới Owner
        val result = memberSyncManager.sendJoinRequest(qrPayload, newMember)
        assertTrue(result.isSuccess)

        val response = result.getOrThrow()
        assertEquals("OK", response.status)

        // Kiểm tra 1: Owner đã tự động lưu Member B vào CSDL của Owner
        val ownerMembers = ownerTeamRepo.members[teamId]
        assertNotNull(ownerMembers)
        assertTrue(ownerMembers!!.any { it.memberId == "member_b" && it.memberName == "Huy Member" })

        // Kiểm tra 2: Owner đã ghi log kiểm toán MEMBER_JOINED
        assertTrue(ownerAuditRepo.logs.any { it.details.contains("Huy Member") })

        // Kiểm tra 3: Member B đã nhận được danh mục tài liệu ban đầu từ Owner với trạng thái NOT_DOWNLOADED
        val memberDocs = memberDocRepo.docs.values
        assertTrue(memberDocs.any { it.docId == "doc_init" && it.downloadStatus == DownloadStatus.NOT_DOWNLOADED })
    }

    @Test
    fun testCatalogAnnounce_updatesRemotePeer() = runBlocking {
        val teamId = "team_alpha"
        val ownerPort = ownerSyncManager.startSyncServer(teamId, preferredPort = 0, scope = testScope)

        // Khởi động server của Member để Member có thể nhận socket
        val memberPort = memberSyncManager.startSyncServer(teamId, preferredPort = 0, scope = testScope)

        // Member kết nối join trước để Owner biết địa chỉ của Member
        val qrPayload = QrInvitePayload(
            teamId = teamId,
            invitationToken = "token_xyz",
            expiresAt = System.currentTimeMillis() / 1000L + 600L,
            ownerPublicKey = "pubkey_owner",
            signature = "sig_owner",
            teamName = "Test Team",
            ownerHost = "127.0.0.1",
            ownerPort = ownerPort
        )

        val member = TeamMember(
            memberId = "member_b",
            teamId = teamId,
            memberName = "Huy Member",
            deviceFingerprint = "fingerprint_b",
            role = MemberRole.MEMBER,
            credentialData = "cred_b"
        )
        memberSyncManager.sendJoinRequest(qrPayload, member)

        // Member công bố 1 tài liệu mới
        val newDoc = SharedDocument(
            docId = "doc_photo_123",
            teamId = teamId,
            publisherId = "member_b",
            fileName = "photo.jpg",
            fileSize = 4096L,
            mimeType = "image/jpeg",
            expectedSha256 = "sha256_hash_123",
            downloadStatus = DownloadStatus.COMPLETED
        )

        // Gửi thông báo catalog announce trực tiếp tới Owner
        val announceMsg = CatalogAnnounceMessage(
            teamId = teamId,
            docId = newDoc.docId,
            publisherId = newDoc.publisherId,
            publisherName = "Huy Member",
            fileName = newDoc.fileName,
            fileSize = newDoc.fileSize,
            mimeType = newDoc.mimeType,
            expectedSha256 = newDoc.expectedSha256
        )

        // Gửi qua socket cục bộ tới Owner
        java.net.Socket("127.0.0.1", ownerPort).use { socket ->
            val writer = java.io.BufferedWriter(java.io.OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
            writer.write(announceMsg.toJson())
            writer.newLine()
            writer.flush()
        }

        // Chờ 200ms để coroutine của server xử lý xong
        kotlinx.coroutines.delay(200L)

        // Kiểm tra: Owner đã nhận được tài liệu của Member và lưu vào CSDL với trạng thái NOT_DOWNLOADED
        val ownerDoc = ownerDocRepo.docs["doc_photo_123"]
        assertNotNull(ownerDoc)
        assertEquals("photo.jpg", ownerDoc!!.fileName)
        assertEquals(DownloadStatus.NOT_DOWNLOADED, ownerDoc.downloadStatus)

        // Owner cũng đã ghi log kiểm toán DOCUMENT_PUBLISHED
        assertTrue(ownerAuditRepo.logs.any { it.details.contains("photo.jpg") })
    }
}
