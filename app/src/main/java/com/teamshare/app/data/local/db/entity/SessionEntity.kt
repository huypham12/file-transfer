package com.teamshare.app.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Thực thể CSDL bảng 'transfer_sessions' quản lý tiến trình phiên truyền tải P2P và Checkpoint.
 */
@Entity(
    tableName = "transfer_sessions",
    indices = [
        Index(name = "idx_sessions_status", value = ["status"]),
        Index(name = "idx_sessions_peer_member", value = ["peer_member_id"])
    ]
)
data class SessionEntity(
    @PrimaryKey
    @ColumnInfo(name = "session_id")
    val sessionId: String,

    @ColumnInfo(name = "doc_id")
    val docId: String = "",

    @ColumnInfo(name = "peer_member_id")
    val peerMemberId: String = "",

    @ColumnInfo(name = "direction")
    val direction: String,

    @ColumnInfo(name = "channel")
    val channel: String,

    @ColumnInfo(name = "total_bytes")
    val totalBytes: Long,

    @ColumnInfo(name = "confirmed_offset")
    val confirmedOffset: Long = 0L,

    @ColumnInfo(name = "temp_file_path")
    val tempFilePath: String = "",

    @ColumnInfo(name = "status")
    val status: String = "TRANSFERRING",

    @ColumnInfo(name = "error_code")
    val errorCode: String? = null,

    @ColumnInfo(name = "started_at")
    val startedAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "completed_at")
    val completedAt: Long? = null
)
