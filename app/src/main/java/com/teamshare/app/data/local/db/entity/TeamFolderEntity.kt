package com.teamshare.app.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Thực thể CSDL bảng 'team_folders' lưu cấu trúc thư mục ảo trong nhóm.
 */
@Entity(
    tableName = "team_folders",
    foreignKeys = [
        ForeignKey(
            entity = TeamEntity::class,
            parentColumns = ["team_id"],
            childColumns = ["team_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = TeamFolderEntity::class,
            parentColumns = ["folder_id"],
            childColumns = ["parent_folder_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(name = "idx_folders_team_parent", value = ["team_id", "parent_folder_id"]),
        Index(name = "idx_folders_parent", value = ["parent_folder_id"])
    ]
)
data class TeamFolderEntity(
    @PrimaryKey
    @ColumnInfo(name = "folder_id")
    val folderId: String,

    @ColumnInfo(name = "team_id")
    val teamId: String,

    @ColumnInfo(name = "parent_folder_id")
    val parentFolderId: String? = null,

    @ColumnInfo(name = "folder_name")
    val folderName: String,

    @ColumnInfo(name = "created_by")
    val createdBy: String,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "is_deleted")
    val isDeleted: Boolean = false
)
