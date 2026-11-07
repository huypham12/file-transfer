package com.teamshare.app.data.repository

import com.teamshare.app.data.local.db.dao.FolderDao
import com.teamshare.app.data.local.db.mapper.toDomain
import com.teamshare.app.data.local.db.mapper.toEntity
import com.teamshare.app.domain.model.TeamFolder
import com.teamshare.app.domain.repository.IFolderRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Hiện thực hóa IFolderRepository quản lý Thư mục ảo trong Không gian Nhóm.
 */
class FolderRepositoryImpl(
    private val folderDao: FolderDao
) : IFolderRepository {

    override suspend fun createFolder(folder: TeamFolder): Result<TeamFolder> = withContext(Dispatchers.IO) {
        runCatching {
            folderDao.insertFolder(folder.toEntity())
            folder
        }
    }

    override fun getFoldersByTeam(teamId: String): Flow<List<TeamFolder>> {
        return folderDao.getFoldersByTeam(teamId).map { list -> list.map { it.toDomain() } }
    }

    override fun getFoldersByParent(teamId: String, parentFolderId: String?): Flow<List<TeamFolder>> {
        return folderDao.getFoldersByParent(teamId, parentFolderId).map { list -> list.map { it.toDomain() } }
    }

    override suspend fun getFolderById(folderId: String): TeamFolder? = withContext(Dispatchers.IO) {
        folderDao.getFolderById(folderId)?.toDomain()
    }

    override suspend fun softDeleteFolder(folderId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            folderDao.softDeleteFolder(folderId)
            Unit
        }
    }

    override suspend fun saveFolders(folders: List<TeamFolder>): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            folderDao.insertFolders(folders.map { it.toEntity() })
            Unit
        }
    }
}

