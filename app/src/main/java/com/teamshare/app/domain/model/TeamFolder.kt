package com.teamshare.app.domain.model

/**
 * Thực thể miền đại diện cho Thư mục ảo (Virtual Directory) trong Không gian Nhóm.
 * Tổ chức tài liệu theo cây thư mục phân cấp nhưng lưu trữ vật lý phẳng (Flat Storage) theo docId.
 */
data class TeamFolder(
    val folderId: String,
    val teamId: String,
    val parentFolderId: String? = null,
    val folderName: String,
    val createdBy: String,
    val createdAt: Long = System.currentTimeMillis(),
    val isDeleted: Boolean = false
) {
    val isRoot: Boolean get() = parentFolderId == null
}

