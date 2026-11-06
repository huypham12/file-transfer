package com.teamshare.app.domain.usecase.document

import android.net.Uri
import com.teamshare.app.core.storage.saf.FileProviderGateway
import com.teamshare.app.core.storage.saf.StorageDirectoryManager
import com.teamshare.app.domain.model.DownloadStatus
import com.teamshare.app.domain.model.SharedDocument
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
                // 1. Định vị tệp vật lý trên đĩa
                val physicalFile = findExistingFile(doc)

                val uri: Uri = if (physicalFile != null && physicalFile.exists()) {
                    if (fileProviderGateway != null) {
                        fileProviderGateway.getShareableUri(physicalFile)
                    } else if (uriConverter != null) {
                        uriConverter.invoke(physicalFile)
                    } else {
                        try {
                            Uri.parse("file://${physicalFile.absolutePath}")
                        } catch (e: Exception) {
                            throw IllegalStateException("Không thể tạo URI mở tệp: ${e.message}")
                        }
                    }
                } else if (!doc.localUri.isNullOrBlank()) {
                    val fallbackFile = File(doc.localUri.removePrefix("file://"))
                    if (fallbackFile.exists() && fileProviderGateway != null) {
                        fileProviderGateway.getShareableUri(fallbackFile)
                    } else if (uriConverter != null) {
                        uriConverter.invoke(fallbackFile)
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

    /**
     * Tìm kiếm toàn diện tệp vật lý trên hệ thống tệp cục bộ (đối chiếu cả bản sao Publisher và Receiver).
     */
    private fun findExistingFile(doc: SharedDocument): File? {
        // 1. Kiểm tra đường dẫn lưu trong localUri nếu không phải là content:// URI
        if (!doc.localUri.isNullOrBlank() && !doc.localUri.startsWith("content://")) {
            val cleanPath = doc.localUri.removePrefix("file://")
            val directFile = File(cleanPath)
            if (directFile.exists() && directFile.isFile) {
                return directFile
            }
        }

        // 2. Kiểm tra tệp người công bố: "${docId}_${fileName}" trong thư mục nhóm
        val publisherFile = directoryManager?.getCompletedFile(doc.teamId, "${doc.docId}_${doc.fileName}")
        if (publisherFile != null && publisherFile.exists() && publisherFile.isFile) {
            return publisherFile
        }

        // 3. Kiểm tra tệp người nhận: "${fileName}" trong thư mục nhóm
        val receiverFile = directoryManager?.getCompletedFile(doc.teamId, doc.fileName)
        if (receiverFile != null && receiverFile.exists() && receiverFile.isFile) {
            return receiverFile
        }

        // 4. Tìm kiếm mở rộng trong thư mục hoàn chỉnh của nhóm theo tên tệp
        directoryManager?.getTeamCompletedDir(doc.teamId)?.listFiles()?.firstOrNull {
            it.isFile && (it.name == doc.fileName || it.name.endsWith("_${doc.fileName}"))
        }?.let { return it }

        // 5. Kiểm tra các thư mục tải tạm/cache hỗ trợ tương thích ngược
        val legacyDirs = listOfNotNull(
            File(System.getProperty("java.io.tmpdir") ?: "/tmp", "teamshare_downloads"),
            File(System.getProperty("java.io.tmpdir") ?: "/tmp", "teamshare_recv"),
            directoryManager?.completedRootDir,
            directoryManager?.rootFilesDir
        )
        for (dir in legacyDirs) {
            val target = File(dir, doc.fileName)
            if (target.exists() && target.isFile) return target
            val targetPrefixed = File(dir, "${doc.docId}_${doc.fileName}")
            if (targetPrefixed.exists() && targetPrefixed.isFile) return targetPrefixed
        }

        return null
    }

    /**
     * Chuyển đổi đối tượng File thành Content URI an toàn có cờ cấp quyền đọc cho ứng dụng ngoài.
     */
    private fun toShareableUri(file: File): Uri {
        return if (fileProviderGateway != null) {
            fileProviderGateway.getShareableUri(file)
        } else if (uriConverter != null) {
            uriConverter.invoke(file)
        } else {
            Uri.parse("file://${file.absolutePath}")
        }
    }
}
