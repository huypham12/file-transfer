package com.teamshare.app.data.local.db.mapper

import com.teamshare.app.data.local.db.entity.AuditEntity
import com.teamshare.app.data.local.db.entity.DocumentEntity
import com.teamshare.app.data.local.db.entity.MemberEntity
import com.teamshare.app.data.local.db.entity.SessionEntity
import com.teamshare.app.data.local.db.entity.TeamEntity
import com.teamshare.app.domain.model.AuditLog
import com.teamshare.app.domain.model.AuditSeverity
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
import org.junit.Assert.*
import org.junit.Test

class MapperTest {

    @Test
    fun testTeamMapperBidirectional() {
        val domain = Team(
            teamId = "t-1",
            teamName = "Bảo mật",
            description = "Mô tả",
            ownerFingerprint = "fp-owner",
            status = TeamStatus.ACTIVE,
            createdAt = 1000L,
            updatedAt = 2000L
        )

        val entity = domain.toEntity()
        assertEquals("t-1", entity.teamId)
        assertEquals("Bảo mật", entity.teamName)
        assertEquals("ACTIVE", entity.status)

        val mappedBack = entity.toDomain()
        assertEquals(domain, mappedBack)
    }

    @Test
    fun testMemberMapperBidirectional() {
        val domain = TeamMember(
            memberId = "m-1",
            teamId = "t-1",
            memberName = "Nguyen Van A",
            deviceFingerprint = "fp-a",
            role = MemberRole.OWNER,
            credentialData = "cred-owner",
            isActive = true,
            joinedAt = 123456L
        )

        val entity = domain.toEntity()
        assertEquals("m-1", entity.memberId)
        assertEquals("OWNER", entity.role)
        assertTrue(entity.isActive)

        val mappedBack = entity.toDomain()
        assertEquals(domain, mappedBack)
    }

    @Test
    fun testDocumentMapperBidirectional() {
        val domain = SharedDocument(
            docId = "doc-1",
            teamId = "t-1",
            publisherId = "m-1",
            fileName = "bao_cao.pdf",
            fileSize = 1024L,
            mimeType = "application/pdf",
            expectedSha256 = "abc123sha",
            requireBiometric = true,
            localUri = "/path/to/file",
            downloadStatus = DownloadStatus.COMPLETED,
            createdAt = 5000L
        )

        val entity = domain.toEntity()
        assertEquals("doc-1", entity.docId)
        assertEquals("COMPLETED", entity.downloadStatus)
        assertTrue(entity.requireBiometric)

        val mappedBack = entity.toDomain()
        assertEquals(domain, mappedBack)
    }

    @Test
    fun testSessionMapperBidirectional() {
        val domain = TransferSession(
            sessionId = "s-1",
            docId = "doc-1",
            peerMemberId = "m-2",
            direction = TransferDirection.INCOMING,
            channel = TransferChannel.DIRECT_LAN,
            totalBytes = 2048L,
            bytesTransferred = 1024L,
            confirmedOffset = 1024L,
            tempFilePath = "/tmp/part",
            status = SessionStatus.TRANSFERRING,
            errorCode = null,
            startedAt = 1000L,
            completedAt = null
        )

        val entity = domain.toEntity()
        assertEquals("s-1", entity.sessionId)
        assertEquals("INCOMING", entity.direction)
        assertEquals("DIRECT_LAN", entity.channel)
        assertEquals(1024L, entity.confirmedOffset)

        val mappedBack = entity.toDomain()
        assertEquals(domain.sessionId, mappedBack.sessionId)
        assertEquals(domain.docId, mappedBack.docId)
        assertEquals(domain.direction, mappedBack.direction)
        assertEquals(domain.channel, mappedBack.channel)
        assertEquals(domain.confirmedOffset, mappedBack.confirmedOffset)
        assertEquals(domain.status, mappedBack.status)
    }

    @Test
    fun testAuditMapperBidirectional() {
        val domain = AuditLog(
            logId = 42L,
            teamId = "t-1",
            actorId = "m-1",
            timestamp = 9999L,
            eventType = AuditEventType.DOCUMENT_PUBLISHED,
            severity = AuditSeverity.WARNING,
            details = "Tài liệu mật đã được công bố"
        )

        val entity = domain.toEntity()
        assertEquals(42L, entity.logId)
        assertEquals("DOCUMENT_PUBLISHED", entity.eventType)
        assertEquals("WARNING", entity.severity)

        val mappedBack = entity.toDomain()
        assertEquals(domain, mappedBack)
    }
}

