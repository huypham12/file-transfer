package com.teamshare.app.data.local.db.mapper

import com.teamshare.app.data.local.db.entity.AuditEntity
import com.teamshare.app.data.local.db.entity.SessionEntity
import com.teamshare.app.domain.model.AuditLog
import com.teamshare.app.domain.model.AuditSeverity
import com.teamshare.app.domain.model.AuditEventType
import com.teamshare.app.domain.model.SessionStatus
import com.teamshare.app.domain.model.TransferChannel
import com.teamshare.app.domain.model.TransferDirection
import com.teamshare.app.domain.model.TransferSession

/**
 * Các hàm tiện ích chuyển đổi giữa Room Entity (Data Layer) và Domain Model (Domain Layer).
 * Tinh gọn cho 2 thực thể cốt lõi: TransferSession và SecurityAuditLog.
 */

// Session Mappings
fun SessionEntity.toDomain(): TransferSession = TransferSession(
    sessionId = sessionId,
    docId = docId,
    peerMemberId = peerMemberId,
    direction = runCatching { TransferDirection.valueOf(direction) }.getOrDefault(TransferDirection.INCOMING),
    channel = runCatching { TransferChannel.valueOf(channel) }.getOrDefault(TransferChannel.DIRECT_LAN),
    totalBytes = totalBytes,
    bytesTransferred = confirmedOffset,
    confirmedOffset = confirmedOffset,
    tempFilePath = tempFilePath,
    status = runCatching { SessionStatus.valueOf(status) }.getOrDefault(SessionStatus.TRANSFERRING),
    errorCode = errorCode,
    startedAt = startedAt,
    completedAt = completedAt
)

fun TransferSession.toEntity(): SessionEntity = SessionEntity(
    sessionId = sessionId,
    docId = docId,
    peerMemberId = peerMemberId,
    direction = direction.name,
    channel = channel.name,
    totalBytes = totalBytes,
    confirmedOffset = confirmedOffset,
    tempFilePath = tempFilePath,
    status = status.name,
    errorCode = errorCode,
    startedAt = startedAt,
    completedAt = completedAt
)

// Audit Mappings
fun AuditEntity.toDomain(): AuditLog = AuditLog(
    logId = logId,
    teamId = teamId,
    actorId = actorId,
    timestamp = timestamp,
    eventType = runCatching { AuditEventType.valueOf(eventType) }.getOrDefault(AuditEventType.SECURITY_ALERT),
    severity = runCatching { AuditSeverity.valueOf(severity) }.getOrDefault(AuditSeverity.INFO),
    details = details
)

fun AuditLog.toEntity(): AuditEntity = AuditEntity(
    logId = logId,
    teamId = teamId,
    actorId = actorId,
    timestamp = timestamp,
    eventType = eventType.name,
    severity = severity.name,
    details = details
)
