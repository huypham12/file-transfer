package com.teamshare.app.domain.repository

import com.teamshare.app.domain.model.TeamFolder
import kotlinx.coroutines.flow.Flow

/**
 * Hợp đồng dữ liệu quản lý Thư mục ảo trong Không gian Nhóm (Virtual Directory Repository).
 */
interface IFolderRepository {
    suspend fun createFolder(folder: TeamFolder): Result<TeamFolder>
    fun getFoldersByTeam(teamId: String): Flow<List<TeamFolder>>
    fun getFoldersByParent(teamId: String, parentFolderId: String?): Flow<List<TeamFolder>>
    suspend fun getFolderById(folderId: String): TeamFolder?
    suspend fun softDeleteFolder(folderId: String): Result<Unit>
    suspend fun saveFolders(folders: List<TeamFolder>): Result<Unit>
}

