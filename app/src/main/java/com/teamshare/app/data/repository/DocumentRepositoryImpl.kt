package com.teamshare.app.data.repository

import android.net.Uri
import com.teamshare.app.core.storage.saf.SafeFileDeleter
import com.teamshare.app.data.local.db.dao.DocumentDao
import com.teamshare.app.data.local.db.mapper.toDomain
import com.teamshare.app.data.local.db.mapper.toEntity
import com.teamshare.app.domain.model.DownloadStatus
import com.teamshare.app.domain.model.SharedDocument
import com.teamshare.app.domain.repository.IDocumentRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Hiện thực hóa IDocumentRepository quản lý Tài liệu trong Không gian Nhóm (Team Documents).
 * Tích hợp SafeFileDeleter để tiêu hủy tệp an toàn chống khôi phục pháp chứng số.
 */
class DocumentRepositoryImpl(
    private val documentDao: DocumentDao,
    private val safeFileDeleter: SafeFileDeleter = SafeFileDeleter
) : IDocumentRepository {

    override suspend fun publishDocument(document: SharedDocument): Result<SharedDocument> = withContext(Dispatchers.IO) {
        runCatching {
            documentDao.insertDocument(document.toEntity())
            document
        }
    }

    override fun getDocumentsByTeam(teamId: String): Flow<List<SharedDocument>> {
        return documentDao.getDocumentsByTeam(teamId).map { list -> list.map { it.toDomain() } }
    }

    override suspend fun getDocumentById(docId: String): SharedDocument? = withContext(Dispatchers.IO) {
        documentDao.getDocumentById(docId)?.toDomain()
    }

    override suspend fun updateDownloadStatus(
        docId: String,
        status: DownloadStatus,
        localUri: String?
    ): Unit = withContext(Dispatchers.IO) {
        if (localUri != null) {
            documentDao.updateDownloadSuccess(docId, status.name, localUri)
        } else {
            documentDao.updateDownloadStatus(docId, status.name)
        }
    }

    override suspend fun deleteDocument(docId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val existing = documentDao.getDocumentById(docId)
            val rawPath = existing?.localUri
            if (!rawPath.isNullOrBlank()) {
                try {
                    val directFile = File(rawPath)
                    val fileToWipe = if (directFile.exists()) {
                        directFile
                    } else {
                        val parsed = Uri.parse(rawPath)
                        File(parsed.path ?: rawPath)
                    }
                    if (fileToWipe.exists()) {
                        safeFileDeleter.wipeAndSanitizeFile(fileToWipe)
                    }
                } catch (ignored: Exception) {
                    val fallback = File(rawPath)
                    if (fallback.exists()) {
                        safeFileDeleter.wipeAndSanitizeFile(fallback)
                    }
                }
            }
            documentDao.deleteDocumentById(docId)
            Unit
        }
    }
}

