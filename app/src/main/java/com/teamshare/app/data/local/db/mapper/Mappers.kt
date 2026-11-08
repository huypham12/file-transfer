package com.teamshare.app.data.local.db.mapper

import com.teamshare.app.data.local.db.entity.SecurityAuditLogEntity
import com.teamshare.app.data.local.db.entity.TransferSessionEntity
import com.teamshare.app.domain.model.ApprovalMode
import com.teamshare.app.domain.model.AuditLog
import com.teamshare.app.domain.model.AuditSeverity
import com.teamshare.app.domain.model.AuditEventType
import com.teamshare.app.domain.model.SecurityAuditLog
import com.teamshare.app.domain.model.SessionStatus
import com.teamshare.app.domain.model.TransferChannel
import com.teamshare.app.domain.model.TransferDirection
import com.teamshare.app.domain.model.TransferSession
import com.teamshare.app.domain.model.TransferType

/**
 * Các hàm tiện ích chuyển đổi giữa Room Entity (Data Layer) và Domain Model (Domain Layer) theo SDD Chương 5.
 */

// Session Mappings
fun TransferSessionEntity.toDomain(): TransferSession = TransferSession(
    sessionId = sessionId,
    sessionToken = sessionToken,
    direction = runCatching { TransferDirection.valueOf(direction) }.getOrDefault(TransferDirection.INCOMING),
    channel = runCatching { TransferChannel.valueOf(channel) }.getOrDefault(TransferChannel.DIRECT_LAN),
    peerDeviceModel = peerDeviceModel,
    peerFingerprint = peerFingerprint,
    approvalMode = runCatching { ApprovalMode.valueOf(approvalMode) }.getOrDefault(ApprovalMode.MANUAL_APPROVAL),
    transferType = runCatching { TransferType.valueOf(transferType) }.getOrDefault(TransferType.SINGLE_FILE),
    manifestJson = manifestJson,
    totalBytes = totalBytes,
    confirmedOffset = confirmedOffset,
    tempFilePath = tempFilePath,
    expectedSha256 = expectedSha256,
    status = runCatching { SessionStatus.valueOf(status) }.getOrDefault(SessionStatus.PENDING),
    errorCode = errorCode,
    startedAt = startedAt,
    completedAt = completedAt
)

fun TransferSession.toEntity(): TransferSessionEntity = TransferSessionEntity(
    sessionId = sessionId,
    sessionToken = sessionToken,
    direction = direction.name,
    channel = channel.name,
    peerDeviceModel = peerDeviceModel,
    peerFingerprint = peerFingerprint,
    approvalMode = approvalMode.name,
    transferType = transferType.name,
    manifestJson = manifestJson,
    totalBytes = totalBytes,
    confirmedOffset = confirmedOffset,
    tempFilePath = tempFilePath,
    expectedSha256 = expectedSha256,
    status = status.name,
    errorCode = errorCode,
    startedAt = startedAt,
    completedAt = completedAt
)

// Audit Mappings
fun SecurityAuditLogEntity.toDomain(): SecurityAuditLog = SecurityAuditLog(
    logId = logId,
    sessionId = sessionId,
    timestamp = timestamp,
    eventType = runCatching { AuditEventType.valueOf(eventType) }.getOrDefault(AuditEventType.SECURITY_ALERT),
    actorDevice = actorDevice,
    channel = channel,
    severity = runCatching { AuditSeverity.valueOf(severity) }.getOrDefault(AuditSeverity.INFO),
    details = details
)

fun SecurityAuditLog.toEntity(): SecurityAuditLogEntity = SecurityAuditLogEntity(
    logId = logId,
    sessionId = sessionId,
    timestamp = timestamp,
    eventType = eventType.name,
    actorDevice = actorDevice,
    channel = channel,
    severity = severity.name,
    details = details
)
