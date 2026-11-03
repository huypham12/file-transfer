package com.teamshare.app.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Thực thể CSDL bảng 'teams' lưu thông tin Không gian nhóm cộng tác.
 */
@Entity(tableName = "teams")
data class TeamEntity(
    @PrimaryKey
    @ColumnInfo(name = "team_id")
    val teamId: String,

    @ColumnInfo(name = "team_name")
    val teamName: String,

    @ColumnInfo(name = "description")
    val description: String,

    @ColumnInfo(name = "owner_fingerprint")
    val ownerFingerprint: String,

    @ColumnInfo(name = "status")
    val status: String = "ACTIVE",

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
)

