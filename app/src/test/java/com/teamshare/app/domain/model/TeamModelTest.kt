package com.teamshare.app.domain.model

import org.junit.Assert.*
import org.junit.Test

class TeamModelTest {

    @Test
    fun testTeamCreationAndStatus() {
        val team = Team(
            teamId = "team-123",
            teamName = "Đồ án Tốt nghiệp",
            description = "Nhóm nghiên cứu bảo mật",
            ownerFingerprint = "abcd1234ef5678"
        )

        assertEquals("team-123", team.teamId)
        assertEquals(TeamStatus.ACTIVE, team.status)
        assertTrue(team.isActive)

        val disbandedTeam = team.copy(status = TeamStatus.DISBANDED)
        assertFalse(disbandedTeam.isActive)
    }

    @Test
    fun testTeamMemberRoleAndOwnership() {
        val owner = TeamMember(
            memberId = "mem-1",
            teamId = "team-123",
            memberName = "Trưởng Nhóm",
            deviceFingerprint = "fingerprint-owner",
            role = MemberRole.OWNER,
            credentialData = "credential-owner-signed"
        )

        val member = TeamMember(
            memberId = "mem-2",
            teamId = "team-123",
            memberName = "Thành Viên A",
            deviceFingerprint = "fingerprint-member",
            role = MemberRole.MEMBER,
            credentialData = "credential-member-signed"
        )

        assertTrue(owner.isOwner)
        assertTrue(owner.isActive)
        assertFalse(member.isOwner)
        assertTrue(member.isActive)
    }

    @Test
    fun testSharedDocumentProperties() {
        val doc = SharedDocument(
            docId = "doc-456",
            teamId = "team-123",
            publisherId = "mem-1",
            fileName = "BaoCaoTotNghiep.pdf",
            fileSize = 10_485_760L, // 10 MB
            mimeType = "application/pdf",
            expectedSha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            requireBiometric = true
        )

        assertFalse(doc.isDownloaded)
        assertFalse(doc.isDownloading)
        assertTrue(doc.requireBiometric)

        val downloadedDoc = doc.copy(downloadStatus = DownloadStatus.COMPLETED, localUri = "content://file")
        assertTrue(downloadedDoc.isDownloaded)
    }

    @Test
    fun testQrInvitePayloadExpiration() {
        val currentEpoch = System.currentTimeMillis() / 1000L
        val activePayload = QrInvitePayload(
            teamId = "team-123",
            invitationToken = "token-abc-123",
            expiresAt = currentEpoch + 600L, // Còn 10 phút
            ownerPublicKey = "owner-pub-key-hex",
            signature = "sig-hex"
        )

        assertFalse(activePayload.isExpired(currentEpoch))

        val expiredPayload = activePayload.copy(expiresAt = currentEpoch - 10L)
        assertTrue(expiredPayload.isExpired(currentEpoch))
    }

    @Test
    fun testChunkFrameIntegrity() {
        val payloadData = "Test Chunk Payload".toByteArray()
        val frame = ChunkFrame(
            msgType = FrameType.DATA_CHUNK,
            streamId = 1001,
            payloadLen = payloadData.size,
            payload = payloadData
        )

        assertTrue(frame.isValidMagic)
        assertEquals(ChunkFrame.MAGIC, frame.magic)
        assertEquals(FrameType.DATA_CHUNK, frame.msgType)
        assertEquals(1001, frame.streamId)
        assertArrayEquals(payloadData, frame.payload)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testChunkFramePayloadLengthMismatchThrows() {
        val payloadData = "Real Data".toByteArray()
        ChunkFrame(
            msgType = FrameType.DATA_CHUNK,
            streamId = 1002,
            payloadLen = 999, // Không khớp với payloadData.size (9 bytes)
            payload = payloadData
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun testChunkFrameExceedingMaxPayloadSizeThrows() {
        val oversizedLen = ChunkFrame.MAX_PAYLOAD_SIZE + 1
        ChunkFrame(
            msgType = FrameType.DATA_CHUNK,
            streamId = 1003,
            payloadLen = oversizedLen,
            payload = ByteArray(oversizedLen)
        )
    }
}

