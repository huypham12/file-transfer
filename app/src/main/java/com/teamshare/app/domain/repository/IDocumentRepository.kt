package com.teamshare.app.domain.repository

import com.teamshare.app.domain.model.DownloadStatus
import com.teamshare.app.domain.model.SharedDocument
import kotlinx.coroutines.flow.Flow

/**
 * Hợp đồng dữ liệu quản lý Tài liệu trong Không gian Nhóm (Team Documents).
 */
interface IDocumentRepository {
    suspend fun publishDocument(document: SharedDocument): Result<SharedDocument>
    fun getDocumentsByTeam(teamId: String): Flow<List<SharedDocument>>
    suspend fun getDocumentById(docId: String): SharedDocument?
    suspend fun updateDownloadStatus(docId: String, status: DownloadStatus, localUri: String? = null)
    suspend fun deleteDocument(docId: String): Result<Unit>
}

