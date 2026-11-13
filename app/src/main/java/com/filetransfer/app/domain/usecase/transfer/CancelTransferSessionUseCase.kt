package com.filetransfer.app.domain.usecase.transfer

import com.filetransfer.app.core.storage.saf.SafeFileDeleter
import com.filetransfer.app.core.transfer.TransferCoordinator
import com.filetransfer.app.domain.model.AuditEventType
import com.filetransfer.app.domain.model.AuditLog
import com.filetransfer.app.domain.model.AuditSeverity
import com.filetransfer.app.domain.model.SessionStatus
import com.filetransfer.app.domain.repository.IAuditRepository
import com.filetransfer.app.domain.repository.ITransferRepository
import java.io.File

/**
 * FR-SND-06: Giám sát tiến độ & Hủy phiên an toàn (Anti-Forensics Wipe).
 * Tuân thủ SDD Mục 7.5, 8.5 và Bảng 12.1.
 */
class CancelTransferSessionUseCase(
    private val transferRepository: ITransferRepository,
    private val transferCoordinator: TransferCoordinator? = null,
    private val auditRepository: IAuditRepository? = null
) {
    /**
     * Hủy phiên truyền tải, dừng engine, và tiêu hủy tệp tạm dở dang bằng byte 0x00.
     *
     * @param sessionId Định danh phiên cần hủy
     * @param tempFile Tệp tạm .part đang ghi dở (tùy chọn)
     */
    suspend fun execute(sessionId: String, tempFile: File? = null): Result<Unit> {
        if (sessionId.isBlank()) {
            return Result.failure(IllegalArgumentException("sessionId không được để trống"))
        }

        return try {
            // 1. Dừng coordinator điều phối luồng socket
            transferCoordinator?.cancelSession(sessionId)

            // 2. Tiêu hủy an toàn tệp tạm dở dang (Wipe 0x00 theo OWASP MASVS-STORAGE)
            val session = transferRepository.getSessionById(sessionId)
            val fileToWipe = tempFile ?: session?.tempFilePath?.takeIf { it.isNotBlank() }?.let { File(it) }

            if (fileToWipe != null && fileToWipe.exists()) {
                SafeFileDeleter.wipeAndSanitizeFile(fileToWipe)
            }

            // 3. Cập nhật trạng thái CSDL Room
            transferRepository.updateSessionStatus(sessionId, SessionStatus.CANCELLED)

            // 4. Ghi vết kiểm toán an ninh
            auditRepository?.logEvent(
                AuditLog(
                    sessionId = sessionId,
                    actorId = "user_cancel",
                    eventType = AuditEventType.TRANSFER_CANCELLED,
                    severity = AuditSeverity.INFO,
                    details = "Người dùng đã chủ động hủy phiên truyền tải. Tệp tạm đã được tiêu hủy sạch."
                )
            )

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend operator fun invoke(sessionId: String, tempFile: File? = null): Result<Unit> =
        execute(sessionId, tempFile)
}

