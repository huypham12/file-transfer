package com.teamshare.app.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Thực thể CSDL bảng 'security_audit_logs' lưu nhật ký kiểm toán an ninh bất biến.
 */
@Entity(
    tableName = "security_audit_logs",
    indices = [
        Index(name = "idx_audit_timestamp", value = ["timestamp"])
    ]
)
data class AuditEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "log_id")
    val logId: Long = 0L,

    @ColumnInfo(name = "team_id")
    val teamId: String = "",

    @ColumnInfo(name = "actor_id")
    val actorId: String = "",

    @ColumnInfo(name = "timestamp")
    val timestamp: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "event_type")
    val eventType: String,

    @ColumnInfo(name = "severity")
    val severity: String,

    @ColumnInfo(name = "details")
    val details: String,

    @ColumnInfo(name = "actor_name")
    val actorName: String = "",

    @ColumnInfo(name = "signature")
    val signature: String = ""
)
