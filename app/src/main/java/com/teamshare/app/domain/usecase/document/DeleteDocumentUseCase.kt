package com.teamshare.app.domain.usecase.document

import com.teamshare.app.core.storage.saf.SafeFileDeleter
import com.teamshare.app.core.storage.saf.StorageDirectoryManager
import com.teamshare.app.domain.model.AuditLog
import com.teamshare.app.domain.model.AuditEventType
import com.teamshare.app.domain.model.AuditSeverity
import com.teamshare.app.domain.model.ErrorCode
import com.teamshare.app.domain.model.MemberRole
import com.teamshare.app.domain.repository.IAuditRepository
import com.teamshare.app.domain.repository.IDocumentRepository
import com.teamshare.app.domain.repository.ITeamRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import java.io.File

/**
 * UC-10: Xóa tài liệu khỏi danh mục nhóm & Dọn dẹp an toàn tệp cục bộ (SRS FR-DOC-04).
 * Tiêu hủy an toàn byte 0x00 trên đĩa vật lý (Anti-Forensics) trước khi xóa inode,
 * thẩm định quyền (chỉ Publisher hoặc Owner mới có quyền xóa), xóa bản ghi tài liệu khỏi CSDL Room và ghi nhật ký kiểm toán.
 */
class DeleteDocumentUseCase(
    private val documentRepository: IDocumentRepository,
    private val directoryManager: StorageDirectoryManager? = null,
    private val auditRepository: IAuditRepository? = null,
    private val teamRepository: ITeamRepository? = null
) {
    operator fun invoke(
        docId: String,
        requesterId: String,
        requesterRole: MemberRole? = null,
        wipeLocally: Boolean = true
    ): Flow<Result<Unit>> = flow {
        if (docId.isBlank() || requesterId.isBlank()) {
            emit(Result.failure(IllegalArgumentException("docId và requesterId không được để trống")))
            return@flow
        }

        val result: Result<Unit> = try {
            val doc = documentRepository.getDocumentById(docId)
            if (doc == null) {
                Result.failure(NoSuchElementException("Không tìm thấy tài liệu với ID: $docId"))
            } else {
                // Kiểm tra quyền xóa: Chỉ Owner hoặc Publisher mới được xóa
                val isOwner = requesterRole == MemberRole.OWNER ||
                    (teamRepository != null && teamRepository.getMembersByTeam(doc.teamId).firstOrNull()?.find { it.memberId == requesterId }?.isOwner == true)
                val isPublisher = doc.publisherId == requesterId

                if (!isOwner && !isPublisher) {
                    Result.failure(SecurityException("Chỉ người công bố tài liệu hoặc Trưởng nhóm (Owner) mới có quyền xóa tài liệu này (Mã lỗi: ${ErrorCode.ERR_INVALID_ROLE})"))
                } else {
                    // 1. Tiêu hủy tệp vật lý an toàn chống khôi phục nếu yêu cầu
                    if (wipeLocally) {
                        val physicalFile = directoryManager?.getCompletedFile(doc.teamId, doc.fileName)
                            ?: doc.localUri?.let { File(it) }

                        if (physicalFile != null && physicalFile.exists()) {
                            SafeFileDeleter.wipeAndSanitizeFile(physicalFile)
                        }
                    }

                    // 2. Xóa bản ghi trong CSDL Room
                    val deleteResult = documentRepository.deleteDocument(docId)
                    if (deleteResult.isFailure) {
                        Result.failure(deleteResult.exceptionOrNull() ?: Exception("Không thể xóa tài liệu trong CSDL"))
                    } else {
                        auditRepository?.logEvent(
                            AuditLog(
                                teamId = doc.teamId,
                                actorId = requesterId,
                                eventType = AuditEventType.SECURITY_ALERT,
                                severity = AuditSeverity.INFO,
                                details = "Đã dọn dẹp an toàn tài liệu ${doc.fileName} bởi $requesterId (Wipe locally: $wipeLocally)"
                            )
                        )
                        Result.success(Unit)
                    }
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
