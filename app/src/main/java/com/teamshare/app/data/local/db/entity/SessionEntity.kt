package com.teamshare.app.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Thực thể CSDL bảng 'transfer_sessions' quản lý tiến trình các phiên truyền tải Secure Drop (SDD Chương 5).
 * Tối ưu hóa với 2 chỉ mục: [idx_sessions_status_dir] phục vụ resume/active sessions và [idx_sessions_token] cho tra cứu O(1).
 */
@Entity(
    tableName = "transfer_sessions",
    indices = [
        Index(name = "idx_sessions_status_dir", value = ["status", "direction"]),
        Index(name = "idx_sessions_token", value = ["session_token"])
    ]
)
data class TransferSessionEntity(
    @PrimaryKey
    @ColumnInfo(name = "session_id")
    val sessionId: String,

    @ColumnInfo(name = "session_token")
    val sessionToken: String = "",

    @ColumnInfo(name = "direction")
    val direction: String,

    @ColumnInfo(name = "channel")
    val channel: String = "DIRECT_LAN",

    @ColumnInfo(name = "peer_device_model")
    val peerDeviceModel: String = "",

    @ColumnInfo(name = "peer_fingerprint")
    val peerFingerprint: String = "",

    @ColumnInfo(name = "approval_mode", defaultValue = "MANUAL_APPROVAL")
    val approvalMode: String = "MANUAL_APPROVAL",

    @ColumnInfo(name = "transfer_type", defaultValue = "SINGLE_FILE")
    val transferType: String = "SINGLE_FILE",

    @ColumnInfo(name = "manifest_json")
    val manifestJson: String = "[]",

    @ColumnInfo(name = "total_bytes")
    val totalBytes: Long = 0L,

    @ColumnInfo(name = "confirmed_offset", defaultValue = "0")
    val confirmedOffset: Long = 0L,

    @ColumnInfo(name = "temp_file_path")
    val tempFilePath: String = "",

    @ColumnInfo(name = "expected_sha256")
    val expectedSha256: String = "",

    @ColumnInfo(name = "status", defaultValue = "PENDING")
    val status: String = "PENDING",

    @ColumnInfo(name = "error_code")
    val errorCode: String? = null,

    @ColumnInfo(name = "started_at")
    val startedAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "completed_at")
    val completedAt: Long? = null
)

/**
 * Typealias đảm bảo tương thích ngược với các thành phần cũ trong quá trình refactor.
 */
typealias SessionEntity = TransferSessionEntity
