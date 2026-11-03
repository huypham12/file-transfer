package com.teamshare.app.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.teamshare.app.data.local.db.entity.DocumentEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object cho bảng 'shared_documents'.
 */
@Dao
interface DocumentDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDocument(doc: DocumentEntity): Long

    @Query("SELECT * FROM shared_documents WHERE team_id = :teamId ORDER BY created_at DESC")
    fun getDocumentsByTeam(teamId: String): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM shared_documents WHERE doc_id = :docId LIMIT 1")
    suspend fun getDocumentById(docId: String): DocumentEntity?

    @Query("UPDATE shared_documents SET download_status = :status, local_uri = :localUri WHERE doc_id = :docId")
    suspend fun updateDownloadSuccess(docId: String, status: String, localUri: String): Int

    @Query("UPDATE shared_documents SET download_status = :status WHERE doc_id = :docId")
    suspend fun updateDownloadStatus(docId: String, status: String): Int

    @Query("DELETE FROM shared_documents WHERE doc_id = :docId")
    suspend fun deleteDocumentById(docId: String): Int
}

