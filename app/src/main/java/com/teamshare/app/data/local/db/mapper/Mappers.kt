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

/**
 * Các hàm tiện ích chuyển đổi giữa Room Entity (Data Layer) và Domain Model (Domain Layer).
 * Tuân thủ nguyên lý Clean Architecture: Tách biệt ranh giới dữ liệu và nghiệp vụ.
 */

// Team Mappings
fun TeamEntity.toDomain(): Team = Team(
    teamId = teamId,
    teamName = teamName,
    description = description,
    ownerFingerprint = ownerFingerprint,
    status = runCatching { TeamStatus.valueOf(status) }.getOrDefault(TeamStatus.ACTIVE),
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun Team.toEntity(): TeamEntity = TeamEntity(
    teamId = teamId,
    teamName = teamName,
    description = description,
    ownerFingerprint = ownerFingerprint,
    status = status.name,
    createdAt = createdAt,
    updatedAt = updatedAt
)

// Member Mappings
fun MemberEntity.toDomain(): TeamMember = TeamMember(
    memberId = memberId,
    teamId = teamId,
    memberName = memberName,
    deviceFingerprint = deviceFingerprint,
    role = runCatching { MemberRole.valueOf(role) }.getOrDefault(MemberRole.MEMBER),
    credentialData = credentialData,
    isActive = isActive,
    joinedAt = joinedAt
)

fun TeamMember.toEntity(): MemberEntity = MemberEntity(
    memberId = memberId,
    teamId = teamId,
    memberName = memberName,
    deviceFingerprint = deviceFingerprint,
    role = role.name,
    credentialData = credentialData,
    isActive = isActive,
    joinedAt = joinedAt
)

// Document Mappings
fun DocumentEntity.toDomain(): SharedDocument = SharedDocument(
    docId = docId,
    teamId = teamId,
    publisherId = publisherId,
    fileName = fileName,
    fileSize = fileSize,
    mimeType = mimeType,
    expectedSha256 = expectedSha256,
    requireBiometric = requireBiometric,
    localUri = localUri,
    downloadStatus = runCatching { DownloadStatus.valueOf(downloadStatus) }.getOrDefault(DownloadStatus.NOT_DOWNLOADED),
    createdAt = createdAt
)

fun SharedDocument.toEntity(): DocumentEntity = DocumentEntity(
    docId = docId,
    teamId = teamId,
    publisherId = publisherId,
    fileName = fileName,
    fileSize = fileSize,
    mimeType = mimeType,
    expectedSha256 = expectedSha256,
    requireBiometric = requireBiometric,
    localUri = localUri,
    downloadStatus = downloadStatus.name,
    createdAt = createdAt
)

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

