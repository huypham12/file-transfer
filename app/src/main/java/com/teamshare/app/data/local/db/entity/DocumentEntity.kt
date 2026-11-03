package com.teamshare.app.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Thực thể CSDL bảng 'shared_documents' lưu danh mục tài liệu chia sẻ trong nhóm.
 */
@Entity(
    tableName = "shared_documents",
    foreignKeys = [
        ForeignKey(
            entity = TeamEntity::class,
            parentColumns = ["team_id"],
            childColumns = ["team_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = MemberEntity::class,
            parentColumns = ["member_id"],
            childColumns = ["publisher_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(name = "idx_docs_team_created", value = ["team_id", "created_at"]),
        Index(name = "idx_docs_publisher", value = ["publisher_id"])
    ]
)
data class DocumentEntity(
    @PrimaryKey
    @ColumnInfo(name = "doc_id")
    val docId: String,

    @ColumnInfo(name = "team_id")
    val teamId: String,

    @ColumnInfo(name = "publisher_id")
    val publisherId: String,

    @ColumnInfo(name = "file_name")
    val fileName: String,

    @ColumnInfo(name = "file_size")
    val fileSize: Long,

    @ColumnInfo(name = "mime_type")
    val mimeType: String,

    @ColumnInfo(name = "expected_sha256")
    val expectedSha256: String,

    @ColumnInfo(name = "require_biometric")
    val requireBiometric: Boolean = false,

    @ColumnInfo(name = "local_uri")
    val localUri: String? = null,

    @ColumnInfo(name = "download_status")
    val downloadStatus: String = "NOT_DOWNLOADED",

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)

