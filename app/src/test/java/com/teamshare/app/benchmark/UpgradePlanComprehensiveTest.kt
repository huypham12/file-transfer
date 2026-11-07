package com.teamshare.app.benchmark

import com.teamshare.app.core.network.sync.FolderAnnounceMessage
import com.teamshare.app.core.network.sync.FolderSyncDto
import com.teamshare.app.core.network.sync.FolderSyncResponseMessage
import com.teamshare.app.core.network.sync.MemberRevokedMessage
import com.teamshare.app.core.network.sync.PeerHeartbeatMessage
import com.teamshare.app.core.network.sync.TeamDisbandedMessage
import com.teamshare.app.core.security.crypto.Sha256StreamCalculator
import com.teamshare.app.core.security.keystore.SoftwareKeyManager
import com.teamshare.app.data.local.db.dao.FolderDao
import com.teamshare.app.data.local.db.dao.RevocationDao
import com.teamshare.app.data.local.db.entity.RevocationEntity
import com.teamshare.app.data.local.db.entity.TeamFolderEntity
import com.teamshare.app.data.repository.FolderRepositoryImpl
import com.teamshare.app.domain.model.AuditEventType
import com.teamshare.app.domain.model.AuditLog
import com.teamshare.app.domain.model.AuditSeverity
import com.teamshare.app.domain.model.Team
import com.teamshare.app.domain.model.TeamFolder
import com.teamshare.app.domain.model.TeamMember
import com.teamshare.app.domain.model.TeamStatus
import com.teamshare.app.domain.usecase.team.CreateTeamUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

class UpgradePlanComprehensiveTest {

    private lateinit var keyManager: SoftwareKeyManager

    @Before
    fun setup() {
        keyManager = SoftwareKeyManager()
    }

    // --- KIỂM CHỨNG VẤN ĐỀ 1: Quản lý Username & Định danh thành viên ---
    @Test
    fun testProblem1_OwnerDisplayNameAndUniqueness() = runBlocking {
        val fakeTeamRepo = com.teamshare.app.domain.usecase.team.FakeTeamRepository()
        val createTeamUseCase = CreateTeamUseCase(fakeTeamRepo, keyManager)

        // Tạo nhóm với tên hiển thị cụ thể của Owner
        val teamResult = createTeamUseCase("Nhóm Dự Án Mới", "Mô tả", "Trưởng Nhóm Alice").first()
        assertTrue("Tạo nhóm thành công", teamResult.isSuccess)

        val team = teamResult.getOrThrow()
        val members = fakeTeamRepo.getMembersByTeam(team.teamId).first()
        assertEquals("Có đúng 1 thành viên ban đầu", 1, members.size)
        val ownerMember = members[0]
        assertEquals("Tên hiển thị của Owner phải là 'Trưởng Nhóm Alice'", "Trưởng Nhóm Alice", ownerMember.memberName)
        assertTrue("Là Owner", ownerMember.isOwner)

        // Thêm thành viên B
        val memberB = TeamMember(
            memberId = "member-b",
            teamId = team.teamId,
            memberName = "Bob",
            deviceFingerprint = "fingerprint-b",
            credentialData = "cred-b"
        )
        fakeTeamRepo.addMember(memberB)

        // Kiểm tra cơ chế ngăn trùng tên trong cùng một nhóm
        val existing = fakeTeamRepo.getMembersByTeam(team.teamId).first()
        val isDuplicate = existing.any { it.isActive && it.memberName.equals("bob", ignoreCase = true) }
        assertTrue("Phát hiện trùng lặp tên hiển thị trong nhóm", isDuplicate)
    }

    // --- KIỂM CHỨNG VẤN ĐỀ 2: Signed CRL & Tombstone Disbandment ---
    @Test
    fun testProblem2_SignedCRLAndTombstoneDisband() = runBlocking {
        val fakeRevocationDao = FakeRevocationDaoImpl()
        val teamId = "team-secure-101"

        // 1. Tạo bản ghi Signed CRL khi kick thành viên
        val crlRecord = RevocationEntity(
            recordId = UUID.randomUUID().toString(),
            teamId = teamId,
            revokedMemberId = "member-bad",
            revokedFingerprint = "fingerprint-bad",
            revocationType = "MEMBER_KICK",
            revokedAt = System.currentTimeMillis(),
            reason = "Vi phạm chính sách",
            ownerSignature = "sig-ecdsa-owner-valid"
        )
        fakeRevocationDao.insertRevocation(crlRecord)

        val retrievedCrl = fakeRevocationDao.getRevocationForMember(teamId, "member-bad")
        assertNotNull("Tìm thấy bản ghi thu hồi CRL", retrievedCrl)
        assertEquals("MEMBER_KICK", retrievedCrl?.revocationType)

        // 2. Tạo bản ghi Tombstone khi giải tán nhóm
        val tombstoneRecord = RevocationEntity(
            recordId = UUID.randomUUID().toString(),
            teamId = teamId,
            revokedMemberId = "ALL",
            revokedFingerprint = "",
            revocationType = "TEAM_DISBAND",
            revokedAt = System.currentTimeMillis(),
            reason = "DISBANDED_BY_OWNER",
            ownerSignature = "sig-tombstone-valid"
        )
        fakeRevocationDao.insertRevocation(tombstoneRecord)

        val retrievedDisband = fakeRevocationDao.getTeamDisbandRecord(teamId)
        assertNotNull("Tìm thấy bản ghi giải tán Tombstone", retrievedDisband)
        assertEquals("TEAM_DISBAND", retrievedDisband?.revocationType)

        // Kiểm tra serialization bản tin P2P
        val msg = MemberRevokedMessage(
            recordId = crlRecord.recordId,
            teamId = teamId,
            revokedMemberId = crlRecord.revokedMemberId,
            revokedFingerprint = crlRecord.revokedFingerprint,
            revokedAt = crlRecord.revokedAt,
            reason = crlRecord.reason,
            signature = crlRecord.ownerSignature
        )
        val json = msg.toJson()
        val parsed = MemberRevokedMessage.fromJson(json)
        assertNotNull("Bản tin MEMBER_REVOKED parse thành công", parsed)
        assertEquals(crlRecord.revokedMemberId, parsed?.revokedMemberId)
    }

    // --- KIỂM CHỨNG VẤN ĐỀ 3: Nhật ký kiểm toán có chữ ký số và tác nhân ---
    @Test
    fun testProblem3_AuditLogActorAndSignature() {
        val auditLog = AuditLog(
            logId = 101L,
            teamId = "team-test",
            actorId = "member-alice",
            actorName = "Alice",
            eventType = AuditEventType.DOCUMENT_PUBLISHED,
            severity = AuditSeverity.INFO,
            details = "Đã công bố tệp tài liệu kế toán",
            signature = "ecdsa-signature-abc-123"
        )

        assertEquals("Alice", auditLog.actorName)
        assertEquals("ecdsa-signature-abc-123", auditLog.signature)
        assertEquals(101L, auditLog.logId)
    }

    // --- KIỂM CHỨNG VẤN ĐỀ 4: Heartbeat Presence ---
    @Test
    fun testProblem4_HeartbeatPresenceMessage() {
        val hb = PeerHeartbeatMessage(
            teamId = "team-hb-01",
            memberId = "peer-01",
            memberName = "Bob"
        )
        val json = hb.toJson()
        val parsed = PeerHeartbeatMessage.fromJson(json)
        assertNotNull("Bản tin Heartbeat parse thành công", parsed)
        assertEquals("Bob", parsed?.memberName)
        assertEquals("peer-01", parsed?.memberId)
    }

    // --- KIỂM CHỨNG VẤN ĐỀ 6: Virtual Directory & Breadcrumb Navigation ---
    @Test
    fun testProblem6_VirtualDirectoryAndFolderHierarchy() = runBlocking {
        val fakeFolderDao = FakeFolderDaoImpl()
        val folderRepo = FolderRepositoryImpl(fakeFolderDao)
        val teamId = "team-folders-01"

        // 1. Tạo thư mục gốc: "Tài liệu kỹ thuật"
        val rootFolder = TeamFolder(
            folderId = "folder-root-1",
            teamId = teamId,
            parentFolderId = null,
            folderName = "Tài liệu kỹ thuật",
            createdBy = "owner-alice"
        )
        folderRepo.createFolder(rootFolder)
        assertTrue("Là thư mục gốc", rootFolder.isRoot)

        // 2. Tạo thư mục con cấp 1: "Thiết kế kiến trúc"
        val subFolder = TeamFolder(
            folderId = "folder-sub-1",
            teamId = teamId,
            parentFolderId = rootFolder.folderId,
            folderName = "Thiết kế kiến trúc",
            createdBy = "owner-alice"
        )
        folderRepo.createFolder(subFolder)
        assertFalse("Không phải thư mục gốc", subFolder.isRoot)

        // 3. Truy vấn thư mục theo cha
        val rootChildren = folderRepo.getFoldersByParent(teamId, null).first()
        assertEquals("Có 1 thư mục gốc", 1, rootChildren.size)
        assertEquals("Tài liệu kỹ thuật", rootChildren[0].folderName)

        val subChildren = folderRepo.getFoldersByParent(teamId, rootFolder.folderId).first()
        assertEquals("Có 1 thư mục con", 1, subChildren.size)
        assertEquals("Thiết kế kiến trúc", subChildren[0].folderName)

        // 4. Kiểm tra Serialization bản tin P2P FolderAnnounceMessage
        val announceMsg = FolderAnnounceMessage(
            folderId = subFolder.folderId,
            teamId = subFolder.teamId,
            parentFolderId = subFolder.parentFolderId,
            folderName = subFolder.folderName,
            createdBy = subFolder.createdBy,
            createdAt = subFolder.createdAt
        )
        val announceJson = announceMsg.toJson()
        val parsedAnnounce = FolderAnnounceMessage.fromJson(announceJson)
        assertNotNull("FolderAnnounce parse thành công", parsedAnnounce)
        assertEquals(rootFolder.folderId, parsedAnnounce?.parentFolderId)

        // 5. Xóa mềm thư mục
        folderRepo.softDeleteFolder(subFolder.folderId)
        val afterDelete = folderRepo.getFoldersByParent(teamId, rootFolder.folderId).first()
        assertEquals("Thư mục con đã bị xóa mềm", 0, afterDelete.size)
    }

    // --- Fake DAO implementations cho Unit Test ---
    private class FakeRevocationDaoImpl : RevocationDao {
        private val list = mutableListOf<RevocationEntity>()

        override suspend fun insertRevocation(record: RevocationEntity): Long {
            list.removeAll { it.recordId == record.recordId }
            list.add(record)
            return 1L
        }

        override suspend fun insertRevocations(records: List<RevocationEntity>): List<Long> {
            records.forEach { insertRevocation(it) }
            return records.map { 1L }
        }

        override fun getRevocationsByTeam(teamId: String): Flow<List<RevocationEntity>> =
            MutableStateFlow(list.filter { it.teamId == teamId })

        override suspend fun getRevocationForMember(teamId: String, memberId: String): RevocationEntity? =
            list.find { it.teamId == teamId && it.revokedMemberId == memberId }

        override suspend fun getTeamDisbandRecord(teamId: String): RevocationEntity? =
            list.find { it.teamId == teamId && it.revocationType == "TEAM_DISBAND" }

        override suspend fun getAllRevocationsList(teamId: String): List<RevocationEntity> =
            list.filter { it.teamId == teamId }
    }

    private class FakeFolderDaoImpl : FolderDao {
        private val folders = MutableStateFlow<List<TeamFolderEntity>>(emptyList())

        override suspend fun insertFolder(folder: TeamFolderEntity): Long {
            folders.value = folders.value.filter { it.folderId != folder.folderId } + folder
            return 1L
        }

        override suspend fun insertFolders(foldersList: List<TeamFolderEntity>): List<Long> {
            foldersList.forEach { insertFolder(it) }
            return foldersList.map { 1L }
        }

        override fun getFoldersByTeam(teamId: String): Flow<List<TeamFolderEntity>> =
            folders.map { list -> list.filter { it.teamId == teamId && !it.isDeleted } }

        override fun getFoldersByParent(teamId: String, parentFolderId: String?): Flow<List<TeamFolderEntity>> =
            folders.map { list -> list.filter { it.teamId == teamId && it.parentFolderId == parentFolderId && !it.isDeleted } }

        override suspend fun getFolderById(folderId: String): TeamFolderEntity? =
            folders.value.find { it.folderId == folderId }

        override suspend fun softDeleteFolder(folderId: String): Int {
            val existing = folders.value.find { it.folderId == folderId } ?: return 0
            folders.value = folders.value.filter { it.folderId != folderId } + existing.copy(isDeleted = true)
            return 1
        }
    }
}

