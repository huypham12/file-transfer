package com.teamshare.app.domain.usecase.document

import android.net.Uri
import com.teamshare.app.core.storage.saf.FileProviderGateway
import com.teamshare.app.core.storage.saf.StorageDirectoryManager
import com.teamshare.app.domain.model.DownloadStatus
import com.teamshare.app.domain.repository.IDocumentRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File

/**
 * UC-09: Mở tài liệu an toàn trên Android qua FileProvider (SRS FR-DOC-03).
 * Xác thực tệp đã tải hoàn tất và cấp phát content:// URI đọc tạm thời
 * qua FileProviderGateway để bảo vệ đường dẫn tệp thực tế trên đĩa flash.
 */
class OpenSecureDocumentUseCase(
    private val documentRepository: IDocumentRepository,
    private val fileProviderGateway: FileProviderGateway? = null,
    private val directoryManager: StorageDirectoryManager? = null,
    private val uriConverter: ((File) -> Uri)? = null
) {
    operator fun invoke(docId: String): Flow<Result<Uri>> = flow {
        if (docId.isBlank()) {
            emit(Result.failure(IllegalArgumentException("docId không được để trống")))
            return@flow
        }

        val result: Result<Uri> = try {
            val doc = documentRepository.getDocumentById(docId)
            if (doc == null) {
                Result.failure(NoSuchElementException("Không tìm thấy tài liệu với ID: $docId"))
            } else if (doc.downloadStatus != DownloadStatus.COMPLETED) {
                Result.failure(IllegalStateException("Tài liệu chưa được tải về máy (Trạng thái: ${doc.downloadStatus})"))
            } else {
                // Định vị tệp vật lý trên đĩa
                val physicalFile = directoryManager?.getCompletedFile(doc.teamId, doc.fileName)
                    ?: doc.localUri?.let { File(it) }

                val uri = if (physicalFile != null && physicalFile.exists()) {
                    if (fileProviderGateway != null) {
                        fileProviderGateway.getShareableUri(physicalFile)
                    } else if (uriConverter != null) {
                        uriConverter.invoke(physicalFile)
                    } else {
                        // Fallback cho môi trường không có FileProvider (ví dụ JVM test)
                        try {
                            Uri.parse("file://${physicalFile.absolutePath}")
                        } catch (e: Exception) {
                            throw IllegalStateException("Không thể tạo URI mở tệp: ${e.message}")
                        }
                    }
                } else if (!doc.localUri.isNullOrBlank()) {
                    if (uriConverter != null) {
                        uriConverter.invoke(File(doc.localUri))
                    } else {
                        try {
                            Uri.parse(doc.localUri)
                        } catch (e: Exception) {
                            throw IllegalStateException("Không thể tạo URI mở tệp: ${e.message}")
                        }
                    }
                } else {
                    throw IllegalStateException("Không tìm thấy tệp vật lý của tài liệu: ${doc.fileName}")
                }

                Result.success(uri)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

        emit(result)
    }
}
