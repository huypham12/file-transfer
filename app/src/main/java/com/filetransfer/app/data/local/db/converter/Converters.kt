package com.filetransfer.app.data.local.db.converter

import androidx.room.TypeConverter
import com.filetransfer.app.domain.model.ApprovalMode
import com.filetransfer.app.domain.model.AuditSeverity
import com.filetransfer.app.domain.model.AuditEventType
import com.filetransfer.app.domain.model.SessionStatus
import com.filetransfer.app.domain.model.TransferChannel
import com.filetransfer.app.domain.model.TransferDirection
import com.filetransfer.app.domain.model.TransferType

/**
 * TypeConverters cho Room Database hỗ trợ chuyển đổi giữa Enum Domain và String trong CSDL SQLite.
 */
class Converters {

    @TypeConverter
    fun fromTransferDirection(direction: TransferDirection?): String? = direction?.name

    @TypeConverter
    fun toTransferDirection(value: String?): TransferDirection? = value?.let {
        when (it) {
            "SENDER", "OUTGOING" -> TransferDirection.SENDER
            "RECEIVER", "INCOMING" -> TransferDirection.RECEIVER
            else -> runCatching { TransferDirection.valueOf(it) }.getOrDefault(TransferDirection.RECEIVER)
        }
    }

    @TypeConverter
    fun fromApprovalMode(mode: ApprovalMode?): String? = mode?.name

    @TypeConverter
    fun toApprovalMode(value: String?): ApprovalMode? = value?.let {
        runCatching { ApprovalMode.valueOf(it) }.getOrDefault(ApprovalMode.MANUAL_APPROVAL)
    }

    @TypeConverter
    fun fromTransferType(type: TransferType?): String? = type?.name

    @TypeConverter
    fun toTransferType(value: String?): TransferType? = value?.let {
        when (it) {
            "SINGLE_FILE" -> TransferType.SINGLE_FILE
            "BATCH_FILES", "FOLDER_ZIP" -> TransferType.BATCH_FILES
            else -> runCatching { TransferType.valueOf(it) }.getOrDefault(TransferType.SINGLE_FILE)
        }
    }

    @TypeConverter
    fun fromTransferChannel(channel: TransferChannel?): String? = channel?.name

    @TypeConverter
    fun toTransferChannel(value: String?): TransferChannel? = value?.let {
        when (it) {
            "DIRECT_LAN" -> TransferChannel.DIRECT_LAN
            "RELAY_E2EE", "ZERO_KNOWLEDGE_RELAY" -> TransferChannel.RELAY_E2EE
            else -> runCatching { TransferChannel.valueOf(it) }.getOrDefault(TransferChannel.DIRECT_LAN)
        }
    }

    @TypeConverter
    fun fromSessionStatus(status: SessionStatus?): String? = status?.name

    @TypeConverter
    fun toSessionStatus(value: String?): SessionStatus? = value?.let {
        runCatching { SessionStatus.valueOf(it) }.getOrDefault(SessionStatus.PENDING)
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
