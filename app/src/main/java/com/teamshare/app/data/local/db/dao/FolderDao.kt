package com.teamshare.app.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.teamshare.app.data.local.db.entity.TeamFolderEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FolderDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFolder(folder: TeamFolderEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFolders(folders: List<TeamFolderEntity>): List<Long>

    @Query("SELECT * FROM team_folders WHERE team_id = :teamId AND is_deleted = 0 ORDER BY folder_name ASC")
    fun getFoldersByTeam(teamId: String): Flow<List<TeamFolderEntity>>

    @Query("SELECT * FROM team_folders WHERE team_id = :teamId AND ((:parentFolderId IS NULL AND parent_folder_id IS NULL) OR parent_folder_id = :parentFolderId) AND is_deleted = 0 ORDER BY folder_name ASC")
    fun getFoldersByParent(teamId: String, parentFolderId: String?): Flow<List<TeamFolderEntity>>

    @Query("SELECT * FROM team_folders WHERE folder_id = :folderId LIMIT 1")
    suspend fun getFolderById(folderId: String): TeamFolderEntity?

    @Query("UPDATE team_folders SET is_deleted = 1 WHERE folder_id = :folderId")
    suspend fun softDeleteFolder(folderId: String): Int
}
