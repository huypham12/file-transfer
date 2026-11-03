package com.teamshare.app.data.local.db.converter

import androidx.room.TypeConverter
import com.teamshare.app.domain.model.AuditSeverity
import com.teamshare.app.domain.model.AuditEventType
import com.teamshare.app.domain.model.DownloadStatus
import com.teamshare.app.domain.model.MemberRole
import com.teamshare.app.domain.model.SessionStatus
import com.teamshare.app.domain.model.TeamStatus
import com.teamshare.app.domain.model.TransferChannel
import com.teamshare.app.domain.model.TransferDirection

/**
 * TypeConverters cho Room Database hỗ trợ chuyển đổi giữa Enum Domain và String trong CSDL SQLite.
 */
class Converters {

    @TypeConverter
    fun fromTeamStatus(status: TeamStatus?): String? = status?.name

    @TypeConverter
    fun toTeamStatus(value: String?): TeamStatus? = value?.let {
        runCatching { TeamStatus.valueOf(it) }.getOrDefault(TeamStatus.ACTIVE)
    }

    @TypeConverter
    fun fromMemberRole(role: MemberRole?): String? = role?.name

    @TypeConverter
    fun toMemberRole(value: String?): MemberRole? = value?.let {
        runCatching { MemberRole.valueOf(it) }.getOrDefault(MemberRole.MEMBER)
    }

    @TypeConverter
    fun fromDownloadStatus(status: DownloadStatus?): String? = status?.name

    @TypeConverter
    fun toDownloadStatus(value: String?): DownloadStatus? = value?.let {
        runCatching { DownloadStatus.valueOf(it) }.getOrDefault(DownloadStatus.NOT_DOWNLOADED)
    }

    @TypeConverter
    fun fromTransferDirection(direction: TransferDirection?): String? = direction?.name

    @TypeConverter
    fun toTransferDirection(value: String?): TransferDirection? = value?.let {
        runCatching { TransferDirection.valueOf(it) }.getOrDefault(TransferDirection.INCOMING)
    }

    @TypeConverter
    fun fromTransferChannel(channel: TransferChannel?): String? = channel?.name

    @TypeConverter
    fun toTransferChannel(value: String?): TransferChannel? = value?.let {
        runCatching { TransferChannel.valueOf(it) }.getOrDefault(TransferChannel.DIRECT_LAN)
    }

    @TypeConverter
    fun fromSessionStatus(status: SessionStatus?): String? = status?.name

    @TypeConverter
    fun toSessionStatus(value: String?): SessionStatus? = value?.let {
        runCatching { SessionStatus.valueOf(it) }.getOrDefault(SessionStatus.TRANSFERRING)
    }

    @TypeConverter
    fun fromAuditSeverity(severity: AuditSeverity?): String? = severity?.name

    @TypeConverter
    fun toAuditSeverity(value: String?): AuditSeverity? = value?.let {
        runCatching { AuditSeverity.valueOf(it) }.getOrDefault(AuditSeverity.INFO)
    }

    @TypeConverter
    fun fromAuditEventType(eventType: AuditEventType?): String? = eventType?.name

    @TypeConverter
    fun toAuditEventType(value: String?): AuditEventType? = value?.let {
        runCatching { AuditEventType.valueOf(it) }.getOrDefault(AuditEventType.SECURITY_ALERT)
    }
}

