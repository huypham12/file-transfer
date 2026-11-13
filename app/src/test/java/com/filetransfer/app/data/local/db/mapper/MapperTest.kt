package com.filetransfer.app.data.local.db.mapper

import com.filetransfer.app.data.local.db.entity.SecurityAuditLogEntity
import com.filetransfer.app.data.local.db.entity.TransferSessionEntity
import com.filetransfer.app.domain.model.ApprovalMode
import com.filetransfer.app.domain.model.AuditSeverity
import com.filetransfer.app.domain.model.AuditEventType
import com.filetransfer.app.domain.model.SecurityAuditLog
import com.filetransfer.app.domain.model.SessionStatus
import com.filetransfer.app.domain.model.TransferChannel
import com.filetransfer.app.domain.model.TransferDirection
import com.filetransfer.app.domain.model.TransferSession
import com.filetransfer.app.domain.model.TransferType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Kiểm thử tính toàn vẹn chuyển đổi 2 chiều giữa Entity (Data Layer) và Model (Domain Layer)
 * cho lược đồ CSDL Room mới theo SDD Chương 4 & 5.
 */
class MapperTest {

    @Test
    fun testTransferSessionMapperBidirectional() {
        val domain = TransferSession(
            sessionId = "sess_drop_001",
            sessionToken = "tok_sec_123456",
            direction = TransferDirection.SENDER,
            channel = TransferChannel.DIRECT_LAN,
            peerDeviceModel = "Xiaomi Redmi Turbo 4 Pro",
            peerFingerprint = "fp_abc1234567890",
            approvalMode = ApprovalMode.MANUAL_APPROVAL,
            transferType = TransferType.FOLDER_ZIP,
            manifestJson = "[{\"relativePath\":\"docs/report.pdf\",\"fileSize\":1048576}]",
            totalBytes = 10485760L,
            confirmedOffset = 5242880L,
            tempFilePath = "/data/user/0/com.filetransfer/files/transfers/.part_sess_drop_001",
            expectedSha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            status = SessionStatus.TRANSFERRING,
            errorCode = null,
            startedAt = 1730000000000L,
            completedAt = null
        )

        val entity = domain.toEntity()
        assertEquals("sess_drop_001", entity.sessionId)
        assertEquals("tok_sec_123456", entity.sessionToken)
        assertEquals("SENDER", entity.direction)
        assertEquals("DIRECT_LAN", entity.channel)
        assertEquals("Xiaomi Redmi Turbo 4 Pro", entity.peerDeviceModel)
        assertEquals("fp_abc1234567890", entity.peerFingerprint)
        assertEquals("MANUAL_APPROVAL", entity.approvalMode)
        assertEquals("FOLDER_ZIP", entity.transferType)
        assertEquals(10485760L, entity.totalBytes)
        assertEquals(5242880L, entity.confirmedOffset)
        assertEquals("TRANSFERRING", entity.status)

        val mappedBack = entity.toDomain()
        assertEquals(domain.sessionId, mappedBack.sessionId)
        assertEquals(domain.sessionToken, mappedBack.sessionToken)
        assertEquals(domain.direction, mappedBack.direction)
        assertEquals(domain.channel, mappedBack.channel)
        assertEquals(domain.peerDeviceModel, mappedBack.peerDeviceModel)
        assertEquals(domain.peerFingerprint, mappedBack.peerFingerprint)
        assertEquals(domain.approvalMode, mappedBack.approvalMode)
        assertEquals(domain.transferType, mappedBack.transferType)
        assertEquals(domain.manifestJson, mappedBack.manifestJson)
        assertEquals(domain.totalBytes, mappedBack.totalBytes)
        assertEquals(domain.confirmedOffset, mappedBack.confirmedOffset)
        assertEquals(domain.tempFilePath, mappedBack.tempFilePath)
        assertEquals(domain.expectedSha256, mappedBack.expectedSha256)
        assertEquals(domain.status, mappedBack.status)
        assertEquals(domain.startedAt, mappedBack.startedAt)
    }

    @Test
    fun testSecurityAuditLogMapperBidirectional() {
        val domain = SecurityAuditLog(
            logId = 42L,
            sessionId = "sess_drop_001",
            timestamp = 1730000500000L,
            eventType = AuditEventType.DEVICE_CONNECTED,
            actorDevice = "Xiaomi Redmi Turbo 4 Pro",
            channel = "DIRECT_LAN",
            severity = AuditSeverity.INFO,
            details = "Thiết bị đối tác đã bắt tay kết nối thành công và xác thực chữ ký TEE"
        )

        val entity = domain.toEntity()
        assertEquals(42L, entity.logId)
        assertEquals("sess_drop_001", entity.sessionId)
        assertEquals(1730000500000L, entity.timestamp)
        assertEquals("DEVICE_CONNECTED", entity.eventType)
        assertEquals("Xiaomi Redmi Turbo 4 Pro", entity.actorDevice)
        assertEquals("DIRECT_LAN", entity.channel)
        assertEquals("INFO", entity.severity)
        assertEquals("Thiết bị đối tác đã bắt tay kết nối thành công và xác thực chữ ký TEE", entity.details)

        val mappedBack = entity.toDomain()
        assertEquals(domain.logId, mappedBack.logId)
        assertEquals(domain.sessionId, mappedBack.sessionId)
        assertEquals(domain.timestamp, mappedBack.timestamp)
        assertEquals(domain.eventType, mappedBack.eventType)
        assertEquals(domain.actorDevice, mappedBack.actorDevice)
        assertEquals(domain.channel, mappedBack.channel)
        assertEquals(domain.severity, mappedBack.severity)
        assertEquals(domain.details, mappedBack.details)
    }

    @Test
    fun testEnumFallbackGraceful() {
        // Kiểm tra xử lý chuỗi enum không hợp lệ từ CSDL không gây crash mà fallback an toàn
        val corruptEntity = TransferSessionEntity(
            sessionId = "s_corrupt",
            direction = "UNKNOWN_DIR",
            channel = "UNKNOWN_CHAN",
            approvalMode = "UNKNOWN_MODE",
            transferType = "UNKNOWN_TYPE",
            status = "UNKNOWN_STATUS",
            totalBytes = 100L
        )

        val domain = corruptEntity.toDomain()
        assertNotNull(domain)
        assertEquals(TransferDirection.INCOMING, domain.direction)
        assertEquals(TransferChannel.DIRECT_LAN, domain.channel)
        assertEquals(ApprovalMode.MANUAL_APPROVAL, domain.approvalMode)
        assertEquals(TransferType.SINGLE_FILE, domain.transferType)
        assertEquals(SessionStatus.PENDING, domain.status)
    }
}

