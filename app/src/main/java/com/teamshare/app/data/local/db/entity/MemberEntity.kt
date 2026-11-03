package com.teamshare.app.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Thực thể CSDL bảng 'team_members' lưu danh bạ thành viên trong nhóm.
 */
@Entity(
    tableName = "team_members",
    foreignKeys = [
        ForeignKey(
            entity = TeamEntity::class,
            parentColumns = ["team_id"],
            childColumns = ["team_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(name = "idx_members_team_active", value = ["team_id", "is_active"])
    ]
)
data class MemberEntity(
    @PrimaryKey
    @ColumnInfo(name = "member_id")
    val memberId: String,

    @ColumnInfo(name = "team_id")
    val teamId: String,

    @ColumnInfo(name = "member_name")
    val memberName: String,

    @ColumnInfo(name = "device_fingerprint")
    val deviceFingerprint: String,

    @ColumnInfo(name = "role")
    val role: String = "MEMBER",

    @ColumnInfo(name = "credential_data")
    val credentialData: String,

    @ColumnInfo(name = "is_active")
    val isActive: Boolean = true,

    @ColumnInfo(name = "joined_at")
    val joinedAt: Long = System.currentTimeMillis()
)

