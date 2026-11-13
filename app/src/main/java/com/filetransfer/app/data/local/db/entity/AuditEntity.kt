package com.filetransfer.app.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Thực thể CSDL bảng 'security_audit_logs' lưu nhật ký kiểm toán an ninh bất biến (SDD Chương 5).
 * Ràng buộc khóa ngoại [session_id] trỏ tới [TransferSessionEntity] với quy tắc ON DELETE SET NULL.
 * Tối ưu hóa chỉ mục [idx_audit_timestamp] để truy vấn nhanh danh sách kiểm toán gần nhất theo thời gian.
 */
@Entity(
    tableName = "security_audit_logs",
    foreignKeys = [
        ForeignKey(
            entity = TransferSessionEntity::class,
            parentColumns = ["session_id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(name = "idx_audit_timestamp", value = ["timestamp"]),
        Index(name = "idx_audit_session_id", value = ["session_id"])
    ]
)
data class SecurityAuditLogEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "log_id")
    val logId: Long = 0L,

    @ColumnInfo(name = "session_id")
    val sessionId: String? = null,

    @ColumnInfo(name = "timestamp")
    val timestamp: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "event_type")
    val eventType: String,

    @ColumnInfo(name = "actor_device")
    val actorDevice: String = "",

    @ColumnInfo(name = "channel")
    val channel: String = "",

    @ColumnInfo(name = "severity")
    val severity: String,

    @ColumnInfo(name = "details")
    val details: String
)

/**
 * Typealias tương thích ngược với các thành phần cũ trong quá trình refactor.
 */
typealias AuditEntity = SecurityAuditLogEntity
