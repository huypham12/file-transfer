package com.teamshare.app.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Thực thể CSDL bảng 'revocation_records' lưu trữ danh sách thu hồi quyền (Signed CRL)
 * và đánh dấu giải tán nhóm (Tombstone) có chữ ký số của Trưởng nhóm.
 */
@Entity(
    tableName = "revocation_records",
    foreignKeys = [
        ForeignKey(
            entity = TeamEntity::class,
            parentColumns = ["team_id"],
            childColumns = ["team_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(name = "idx_revocation_team_member", value = ["team_id", "revoked_member_id"])
    ]
)
data class RevocationEntity(
    @PrimaryKey
    @ColumnInfo(name = "record_id")
    val recordId: String,

    @ColumnInfo(name = "team_id")
    val teamId: String,

    @ColumnInfo(name = "revoked_member_id")
    val revokedMemberId: String,

    @ColumnInfo(name = "revoked_fingerprint")
    val revokedFingerprint: String,

    @ColumnInfo(name = "revocation_type")
    val revocationType: String, // "MEMBER_KICK" hoặc "TEAM_DISBAND"

    @ColumnInfo(name = "revoked_at")
    val revokedAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "reason")
    val reason: String = "",

    @ColumnInfo(name = "owner_signature")
    val ownerSignature: String
)

