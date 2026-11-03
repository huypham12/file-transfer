package com.teamshare.app.data.repository

import com.teamshare.app.core.security.keystore.IKeyManager
import com.teamshare.app.core.security.keystore.SoftwareKeyManager
import com.teamshare.app.core.storage.saf.SafeFileDeleter
import com.teamshare.app.data.local.db.dao.AuditDao
import com.teamshare.app.data.local.db.dao.DocumentDao
import com.teamshare.app.data.local.db.dao.MemberDao
import com.teamshare.app.data.local.db.dao.TeamDao
import com.teamshare.app.data.local.db.dao.TransferSessionDao
import com.teamshare.app.data.local.db.entity.AuditEntity
import com.teamshare.app.data.local.db.entity.DocumentEntity
import com.teamshare.app.data.local.db.entity.MemberEntity
import com.teamshare.app.data.local.db.entity.SessionEntity
import com.teamshare.app.data.local.db.entity.TeamEntity
import com.teamshare.app.domain.model.AuditLog
import com.teamshare.app.domain.model.AuditEventType
import com.teamshare.app.domain.model.DownloadStatus
import com.teamshare.app.domain.model.MemberRole
import com.teamshare.app.domain.model.SessionStatus
import com.teamshare.app.domain.model.SharedDocument
import com.teamshare.app.domain.model.Team
import com.teamshare.app.domain.model.TeamMember
import com.teamshare.app.domain.model.TeamStatus
import com.teamshare.app.domain.model.TransferChannel
import com.teamshare.app.domain.model.TransferDirection
import com.teamshare.app.domain.model.TransferSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RepositoriesTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var keyManager: SoftwareKeyManager
    private lateinit var fakeTeamDao: FakeTeamDao
    private lateinit var fakeMemberDao: FakeMemberDao
    private lateinit var fakeDocumentDao: FakeDocumentDao
    private lateinit var fakeSessionDao: FakeTransferSessionDao
    private lateinit var fakeAuditDao: FakeAuditDao

    private lateinit var teamRepository: TeamRepositoryImpl
    private lateinit var documentRepository: DocumentRepositoryImpl
    private lateinit var transferRepository: TransferRepositoryImpl
    private lateinit var auditRepository: AuditRepositoryImpl

    @Before
    fun setUp() {
        keyManager = SoftwareKeyManager()
        fakeTeamDao = FakeTeamDao()
        fakeMemberDao = FakeMemberDao()
        fakeDocumentDao = FakeDocumentDao()
        fakeSessionDao = FakeTransferSessionDao()
        fakeAuditDao = FakeAuditDao()

        teamRepository = TeamRepositoryImpl(fakeTeamDao, fakeMemberDao, keyManager)
        documentRepository = DocumentRepositoryImpl(fakeDocumentDao, SafeFileDeleter)
        transferRepository = TransferRepositoryImpl(fakeSessionDao)
        auditRepository = AuditRepositoryImpl(fakeAuditDao)
    }

    // ----------------------------------------------------
    // TeamRepository Tests
    // ----------------------------------------------------

    @Test
    fun testCreateAndGetTeam() = runBlocking {
        val team = Team(
            teamId = "team-sec-01",
            teamName = "An Toàn Thông Tin",
            description = "Nghiên cứu mật mã P2P",
            ownerFingerprint = "fp-owner-123"
        )

        val result = teamRepository.createTeam(team)
        assertTrue(result.isSuccess)
        assertEquals(team, result.getOrNull())

        val fetched = teamRepository.getTeamById("team-sec-01").first()
        assertNotNull(fetched)
        assertEquals("team-sec-01", fetched?.teamId)
        assertEquals(TeamStatus.ACTIVE, fetched?.status)

        val allTeams = teamRepository.getAllTeams().first()
        assertEquals(1, allTeams.size)
    }

    @Test
    fun testDisbandTeamCleansKey() = runBlocking {
        val teamId = "team-to-disband"
        val team = Team(
            teamId = teamId,
            teamName = "Nhóm Tạm Thời",
            description = "Sẽ giải tán",
            ownerFingerprint = "fp-tmp"
        )

        // Sinh khóa Master trước
        keyManager.generateOwnerMasterKey(teamId)
        val alias = IKeyManager.MASTER_KEY_PREFIX + teamId
        assertTrue(keyManager.hasKey(alias))

        teamRepository.createTeam(team)
        val disbandResult = teamRepository.disbandTeam(teamId)
        assertTrue(disbandResult.isSuccess)

        // Kiểm tra trạng thái đã chuyển DISBANDED
        val updatedTeam = teamRepository.getTeamById(teamId).first()
        assertEquals(TeamStatus.DISBANDED, updatedTeam?.status)

        // Khóa Master phải bị xóa hoàn toàn khỏi KeyManager
        assertFalse("Khóa Master phải bị xóa khi giải tán nhóm", keyManager.hasKey(alias))
    }

    @Test
    fun testTeamMemberManagement() = runBlocking {
        val teamId = "team-membership"
        val team = Team(
            teamId = teamId,
            teamName = "Đội Đặc Nhiệm",
            description = "Quản lý thành viên",
            ownerFingerprint = "fp-boss"
        )
        teamRepository.createTeam(team)

        val member = TeamMember(
            memberId = "mem-007",
            teamId = teamId,
            memberName = "James Bond",
            deviceFingerprint = "fp-agent",
            role = MemberRole.MEMBER,
            credentialData = "cred-signed"
        )

        val addResult = teamRepository.addMember(member)
        assertTrue(addResult.isSuccess)

        val members = teamRepository.getMembersByTeam(teamId).first()
        assertEquals(1, members.size)
        assertEquals("mem-007", members[0].memberId)
        assertTrue(teamRepository.isMemberActive(teamId, "mem-007"))

        // Thu hồi quyền
        val revokeResult = teamRepository.revokeMember(teamId, "mem-007")
        assertTrue(revokeResult.isSuccess)
        assertFalse(teamRepository.isMemberActive(teamId, "mem-007"))
    }

    @Test
    fun testMemberActiveReturnsFalseWhenTeamIsDisbanded() = runBlocking {
        val teamId = "team-to-disband-member-check"
        val team = Team(
            teamId = teamId,
            teamName = "Nhóm Sắp Giải Tán",
            description = "Test disband member active",
            ownerFingerprint = "fp-owner"
        )
        teamRepository.createTeam(team)

        val member = TeamMember(
            memberId = "mem-active",
            teamId = teamId,
            memberName = "Active Member",
            deviceFingerprint = "fp-active",
            role = MemberRole.MEMBER,
            credentialData = "cred-data"
        )
        teamRepository.addMember(member)
        assertTrue("Thành viên phải active khi nhóm active", teamRepository.isMemberActive(teamId, "mem-active"))

        // Giải tán nhóm
        teamRepository.disbandTeam(teamId)

        // Thành viên phải lập tức bị vô hiệu hóa
        assertFalse("Thành viên không được active khi nhóm đã DISBANDED", teamRepository.isMemberActive(teamId, "mem-active"))
    }

    @Test
    fun testMemberActiveReturnsFalseWhenTeamDoesNotExist() = runBlocking {
        assertFalse("Nhóm không tồn tại phải trả về false", teamRepository.isMemberActive("non-existent-team", "mem-01"))
    }

    // ----------------------------------------------------
    // DocumentRepository Tests
    // ----------------------------------------------------

    @Test
    fun testPublishAndQueryDocument() = runBlocking {
        val doc = SharedDocument(
            docId = "doc-alpha",
            teamId = "team-doc",
            publisherId = "pub-1",
            fileName = "ke_hoach_trien_khai.pdf",
            fileSize = 2048L,
            mimeType = "application/pdf",
            expectedSha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            requireBiometric = true
        )

        val result = documentRepository.publishDocument(doc)
        assertTrue(result.isSuccess)

        val fetched = documentRepository.getDocumentById("doc-alpha")
        assertNotNull(fetched)
        assertEquals("doc-alpha", fetched?.docId)
        assertEquals(DownloadStatus.NOT_DOWNLOADED, fetched?.downloadStatus)
        assertTrue(fetched?.requireBiometric == true)

        val teamDocs = documentRepository.getDocumentsByTeam("team-doc").first()
        assertEquals(1, teamDocs.size)
    }

    @Test
    fun testUpdateDownloadStatusAndSafeDelete() = runBlocking {
        // Tạo file vật lý giả lập
        val realFile = tempFolder.newFile("downloaded_doc.pdf")
        realFile.writeBytes(ByteArray(4096) { 0x5A.toByte() })
        assertTrue(realFile.exists())

        val doc = SharedDocument(
            docId = "doc-to-delete",
            teamId = "team-del",
            publisherId = "pub-1",
            fileName = "downloaded_doc.pdf",
            fileSize = 4096L,
            mimeType = "application/pdf",
            expectedSha256 = "abc",
            localUri = realFile.absolutePath,
            downloadStatus = DownloadStatus.COMPLETED
        )
        documentRepository.publishDocument(doc)

        // Cập nhật trạng thái tải
        documentRepository.updateDownloadStatus("doc-to-delete", DownloadStatus.COMPLETED, realFile.absolutePath)
        val afterUpdate = documentRepository.getDocumentById("doc-to-delete")
        assertEquals(DownloadStatus.COMPLETED, afterUpdate?.downloadStatus)
        assertEquals(realFile.absolutePath, afterUpdate?.localUri)

        // Xóa tài liệu: phải xóa cả trong DB lẫn tiêu hủy tệp vật lý
        val deleteResult = documentRepository.deleteDocument("doc-to-delete")
        assertTrue(deleteResult.isSuccess)

        assertNull(documentRepository.getDocumentById("doc-to-delete"))
        assertFalse("Tệp vật lý phải bị tiêu hủy an toàn bởi SafeFileDeleter", realFile.exists())
    }

    // ----------------------------------------------------
    // TransferRepository Tests
    // ----------------------------------------------------

    @Test
    fun testTransferSessionLifecycleAndCheckpoint() = runBlocking {
        val session = TransferSession(
            sessionId = "sess-001",
            docId = "doc-trf",
            peerMemberId = "peer-a",
            direction = TransferDirection.INCOMING,
            channel = TransferChannel.DIRECT_LAN,
            totalBytes = 10_000_000L,
            tempFilePath = "/tmp/.part_sess-001_doc-trf",
            status = SessionStatus.TRANSFERRING
        )

        transferRepository.createSession(session)
        var fetched = transferRepository.getSessionById("sess-001")
        assertNotNull(fetched)
        assertEquals(0L, fetched?.confirmedOffset)
        assertEquals(SessionStatus.TRANSFERRING, fetched?.status)

        // Cập nhật checkpoint (sau khi nhận được 2MB)
        transferRepository.updateCheckpoint("sess-001", 2_000_000L)
        fetched = transferRepository.getSessionById("sess-001")
        assertEquals(2_000_000L, fetched?.confirmedOffset)

        // Tạm dừng
        transferRepository.updateSessionStatus("sess-001", SessionStatus.PAUSED)
        fetched = transferRepository.getSessionById("sess-001")
        assertEquals(SessionStatus.PAUSED, fetched?.status)

        // Hoàn tất
        transferRepository.updateSessionStatus("sess-001", SessionStatus.COMPLETED)
        fetched = transferRepository.getSessionById("sess-001")
        assertEquals(SessionStatus.COMPLETED, fetched?.status)
        assertNotNull(fetched?.completedAt)

        // Xóa phiên
        transferRepository.deleteSession("sess-001")
        assertNull(transferRepository.getSessionById("sess-001"))
    }

    // ----------------------------------------------------
    // AuditRepository Tests
    // ----------------------------------------------------

    @Test
    fun testAuditLogging() = runBlocking {
        val log1 = AuditLog(
            teamId = "team-audit",
            actorId = "owner-1",
            eventType = AuditEventType.TEAM_CREATED,
            details = "Nhóm được tạo thành công"
        )
        val log2 = AuditLog(
            teamId = "team-audit",
            actorId = "owner-1",
            eventType = AuditEventType.INVITATION_GENERATED,
            details = "Sinh mã QR mời thành viên"
        )

        auditRepository.logEvent(log1)
        auditRepository.logEvent(log2)

        val teamLogs = auditRepository.getLogsByTeam("team-audit").first()
        assertEquals(2, teamLogs.size)

        val recentLogs = auditRepository.getRecentLogs(10).first()
        assertEquals(2, recentLogs.size)
    }

    // ====================================================
    // FAKE DAOS IMPLEMENTATIONS
    // ====================================================

    class FakeTeamDao : TeamDao {
        private val teams = MutableStateFlow<Map<String, TeamEntity>>(emptyMap())

        override suspend fun insertTeam(team: TeamEntity): Long {
            teams.value = teams.value + (team.teamId to team)
            return 1L
        }

        override fun getTeamById(teamId: String): Flow<TeamEntity?> {
            return teams.map { it[teamId] }
        }

        override suspend fun findTeamByIdSync(teamId: String): TeamEntity? {
            return teams.value[teamId]
        }

        override fun getAllTeams(): Flow<List<TeamEntity>> {
            return teams.map { it.values.toList().sortedByDescending { t -> t.createdAt } }
        }

        override suspend fun markDisbanded(teamId: String, timestamp: Long): Int {
            val existing = teams.value[teamId] ?: return 0
            teams.value = teams.value + (teamId to existing.copy(status = "DISBANDED", updatedAt = timestamp))
            return 1
        }

        override suspend fun deleteTeam(teamId: String): Int {
            val existed = teams.value.containsKey(teamId)
            teams.value = teams.value - teamId
            return if (existed) 1 else 0
        }
    }

    class FakeMemberDao : MemberDao {
        private val members = MutableStateFlow<Map<String, MemberEntity>>(emptyMap())

        override suspend fun insertMember(member: MemberEntity): Long {
            members.value = members.value + (member.memberId to member)
            return 1L
        }

        override suspend fun insertMembers(members: List<MemberEntity>): List<Long> {
            val map = this.members.value.toMutableMap()
            members.forEach { map[it.memberId] = it }
            this.members.value = map
            return members.map { 1L }
        }

        override fun getMembersByTeam(teamId: String): Flow<List<MemberEntity>> {
            return members.map { it.values.filter { m -> m.teamId == teamId }.sortedBy { m -> m.joinedAt } }
        }

        override suspend fun getMemberById(memberId: String): MemberEntity? {
            return members.value[memberId]
        }

        override suspend fun updateActiveStatus(teamId: String, memberId: String, isActive: Boolean): Int {
            val existing = members.value[memberId] ?: return 0
            members.value = members.value + (memberId to existing.copy(isActive = isActive))
            return 1
        }

        override suspend fun isMemberActive(teamId: String, memberId: String): Boolean? {
            return members.value[memberId]?.isActive
        }

        override suspend fun deleteMember(teamId: String, memberId: String): Int {
            val existed = members.value.containsKey(memberId)
            members.value = members.value - memberId
            return if (existed) 1 else 0
        }
    }

    class FakeDocumentDao : DocumentDao {
        private val docs = MutableStateFlow<Map<String, DocumentEntity>>(emptyMap())

        override suspend fun insertDocument(doc: DocumentEntity): Long {
            docs.value = docs.value + (doc.docId to doc)
            return 1L
        }

        override fun getDocumentsByTeam(teamId: String): Flow<List<DocumentEntity>> {
            return docs.map { it.values.filter { d -> d.teamId == teamId }.sortedByDescending { d -> d.createdAt } }
        }

        override suspend fun getDocumentById(docId: String): DocumentEntity? {
            return docs.value[docId]
        }

        override suspend fun updateDownloadSuccess(docId: String, status: String, localUri: String): Int {
            val existing = docs.value[docId] ?: return 0
            docs.value = docs.value + (docId to existing.copy(downloadStatus = status, localUri = localUri))
            return 1
        }

        override suspend fun updateDownloadStatus(docId: String, status: String): Int {
            val existing = docs.value[docId] ?: return 0
            docs.value = docs.value + (docId to existing.copy(downloadStatus = status))
            return 1
        }

        override suspend fun deleteDocumentById(docId: String): Int {
            val existed = docs.value.containsKey(docId)
            docs.value = docs.value - docId
            return if (existed) 1 else 0
        }
    }

    class FakeTransferSessionDao : TransferSessionDao {
        private val sessions = MutableStateFlow<Map<String, SessionEntity>>(emptyMap())

        override suspend fun insertSession(session: SessionEntity): Long {
            sessions.value = sessions.value + (session.sessionId to session)
            return 1L
        }

        override suspend fun updateCheckpoint(sessionId: String, offset: Long): Int {
            val existing = sessions.value[sessionId] ?: return 0
            sessions.value = sessions.value + (sessionId to existing.copy(confirmedOffset = offset))
            return 1
        }

        override suspend fun updateSessionStatus(
            sessionId: String,
            status: String,
            completedAt: Long?,
            errorCode: String?
        ): Int {
            val existing = sessions.value[sessionId] ?: return 0
            sessions.value = sessions.value + (sessionId to existing.copy(
                status = status,
                completedAt = completedAt,
                errorCode = errorCode
            ))
            return 1
        }

        override suspend fun getSessionById(sessionId: String): SessionEntity? {
            return sessions.value[sessionId]
        }

        override fun getActiveSessions(): Flow<List<SessionEntity>> {
            return sessions.map {
                it.values.filter { s -> s.status in listOf("TRANSFERRING", "HANDSHAKING", "PAUSED") }
                    .sortedByDescending { s -> s.startedAt }
            }
        }

        override suspend fun deleteSession(sessionId: String): Int {
            val existed = sessions.value.containsKey(sessionId)
            sessions.value = sessions.value - sessionId
            return if (existed) 1 else 0
        }
    }

    class FakeAuditDao : AuditDao {
        private val logs = MutableStateFlow<List<AuditEntity>>(emptyList())
        private var idCounter = 1L

        override suspend fun insertLog(log: AuditEntity): Long {
            val newId = idCounter++
            logs.value = listOf(log.copy(logId = newId)) + logs.value
            return newId
        }

        override fun getLogsByTeam(teamId: String): Flow<List<AuditEntity>> {
            return logs.map { list -> list.filter { it.teamId == teamId }.sortedByDescending { it.timestamp } }
        }

        override fun getRecentLogs(limit: Int): Flow<List<AuditEntity>> {
            return logs.map { list -> list.take(limit) }
        }
    }
}

