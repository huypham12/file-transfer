package com.teamshare.app.domain.usecase.document

import android.net.Uri
import com.teamshare.app.core.security.crypto.Sha256StreamCalculator
import com.teamshare.app.core.security.sanitizer.FilenameSanitizer
import com.teamshare.app.core.storage.saf.SafStorageGateway
import com.teamshare.app.core.storage.saf.StorageDirectoryManager
import com.teamshare.app.domain.model.AuditLog
import com.teamshare.app.domain.model.AuditEventType
import com.teamshare.app.domain.model.AuditSeverity
import com.teamshare.app.domain.model.DownloadStatus
import com.teamshare.app.domain.model.SharedDocument
import com.teamshare.app.domain.repository.IAuditRepository
import com.teamshare.app.domain.repository.IDocumentRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.InputStream
import java.util.UUID

/**
 * UC-07: Chọn tệp & Công bố tài liệu vào không gian nhóm (SRS FR-DOC-01).
 * Duy trì Scoped Storage permission qua SAF, làm sạch tên tệp chống Path Traversal,
 * tính toán mã băm SHA-256 dạng stream chống tràn RAM, và lưu bản ghi SharedDocument.
 */
class PublishDocumentUseCase(
    private val documentRepository: IDocumentRepository,
    private val safStorageGateway: SafStorageGateway? = null,
    private val auditRepository: IAuditRepository? = null,
    private val directoryManager: StorageDirectoryManager? = null
) {
    operator fun invoke(
        teamId: String,
        publisherId: String,
        uri: Uri,
        requireBiometric: Boolean = false,
        overrideName: String? = null,
        overrideSize: Long? = null,
        overrideMime: String? = null,
        customInputStream: InputStream? = null,
        folderId: String? = null
    ): Flow<Result<SharedDocument>> = flow {
        if (teamId.isBlank() || publisherId.isBlank()) {
            emit(Result.failure(IllegalArgumentException("teamId và publisherId không được để trống")))
            return@flow
        }

        val result: Result<SharedDocument> = try {
            // 1. Duy trì quyền đọc bền vững
            safStorageGateway?.takePersistableUriPermission(uri)

            // 2. Trích xuất và khử độc siêu dữ liệu
            val metadata = safStorageGateway?.queryMetadata(uri)
            val rawName = overrideName ?: metadata?.fileName ?: uri.lastPathSegment ?: "unnamed_document"
            val sanitizedName = FilenameSanitizer.sanitize(rawName)
            val streamAvailable = try { customInputStream?.available()?.toLong() ?: 0L } catch (_: Exception) { 0L }
            val fileSize = overrideSize ?: metadata?.fileSize ?: if (streamAvailable > 0L) streamAvailable else 0L
            val mimeType = overrideMime ?: metadata?.mimeType ?: "application/octet-stream"

            if (fileSize <= 0L) {
                Result.failure(IllegalArgumentException("Tệp tin có kích thước 0 byte hoặc không hợp lệ: $sanitizedName"))
            } else {
                // 3. Sao chép vào thư mục sandbox an toàn và tính toán SHA-256 dạng streaming
                val docId = UUID.randomUUID().toString()
                val targetFile = directoryManager?.getCompletedFile(teamId, "${docId}_$sanitizedName")
                val expectedSha256: String
                val effectiveUri: String

                if (targetFile != null) {
                    targetFile.parentFile?.mkdirs()
                    val inStream = customInputStream
                        ?: safStorageGateway?.openInputStream(uri)
                        ?: throw IllegalStateException("Không thể mở luồng dữ liệu đọc tệp từ Content URI: $uri")
                    inStream.use { input ->
                        targetFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    expectedSha256 = targetFile.inputStream().use { stream ->
                        Sha256StreamCalculator.calculateStreamSha256(stream)
                    }
                    effectiveUri = targetFile.absolutePath
                } else {
                    val inStream = customInputStream
                        ?: safStorageGateway?.openInputStream(uri)
                        ?: throw IllegalStateException("Không thể mở luồng dữ liệu đọc tệp từ Content URI: $uri")
                    expectedSha256 = inStream.use { stream ->
                        Sha256StreamCalculator.calculateStreamSha256(stream)
                    }
                    effectiveUri = uri.toString()
                }

                // 4. Tạo thực thể SharedDocument và lưu CSDL
                val document = SharedDocument(
                    docId = docId,
                    teamId = teamId,
                    publisherId = publisherId,
                    folderId = folderId,
                    fileName = sanitizedName,
                    fileSize = fileSize,
                    mimeType = mimeType,
                    expectedSha256 = expectedSha256,
                    requireBiometric = requireBiometric,
                    localUri = effectiveUri,
                    downloadStatus = DownloadStatus.COMPLETED
                )

                val publishResult = documentRepository.publishDocument(document)
                if (publishResult.isFailure) {
                    Result.failure(publishResult.exceptionOrNull() ?: Exception("Không thể lưu tài liệu vào CSDL"))
                } else {
                    auditRepository?.logEvent(
                        AuditLog(
                            teamId = teamId,
                            actorId = publisherId,
                            eventType = AuditEventType.DOCUMENT_PUBLISHED,
                            severity = AuditSeverity.INFO,
                            details = "Công bố tài liệu $sanitizedName ($fileSize bytes, SHA-256: $expectedSha256)"
                        )
                    )
                    Result.success(document)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

        emit(result)
    }
}
