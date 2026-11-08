package com.teamshare.app.domain.usecase.transfer

import com.teamshare.app.core.transfer.TransferCoordinator
import com.teamshare.app.domain.model.AuditEventType
import com.teamshare.app.domain.model.AuditLog
import com.teamshare.app.domain.model.AuditSeverity
import com.teamshare.app.domain.model.SessionStatus
import com.teamshare.app.domain.model.TransferSession
import com.teamshare.app.domain.repository.IAuditRepository
import com.teamshare.app.domain.repository.ITransferRepository

/**
 * FR-SND-06: Tái tục phiên truyền tải từ Checkpoint đã lưu.
 * Tuân thủ SDD Mục 7.5 và Bảng 12.1.
 */
class ResumeTransferUseCase(
    private val transferRepository: ITransferRepository,
    private val transferCoordinator: TransferCoordinator? = null,
    private val auditRepository: IAuditRepository? = null
) {
    /**
     * Tái tục phiên truyền tải từ tọa độ checkpoint confirmedOffset an toàn.
     *
     * @param sessionId Định danh phiên cần khôi phục
     * @return Result chứa TransferSession đã được chuyển sang trạng thái TRANSFERRING
     */
    suspend fun execute(sessionId: String): Result<TransferSession> {
        if (sessionId.isBlank()) {
            return Result.failure(IllegalArgumentException("sessionId không được để trống"))
        }

        return try {
            val session = transferRepository.getSessionById(sessionId)
                ?: return Result.failure(IllegalArgumentException("Không tìm thấy phiên với sessionId: $sessionId"))

            if (!session.canResume) {
                return Result.failure(
                    IllegalStateException(
                        "Phiên $sessionId không ở trạng thái cho phép khôi phục (PAUSED hoặc FAILED). Trạng thái hiện tại: ${session.status}"
                    )
                )
            }

            // 1. Khôi phục điều phối phiên nếu có coordinator
            transferCoordinator?.resumeSession(sessionId)

            // 2. Cập nhật trạng thái CSDL Room
            transferRepository.updateSessionStatus(sessionId, SessionStatus.TRANSFERRING)

            // 3. Ghi vết kiểm toán an ninh
            auditRepository?.logEvent(
                AuditLog(
                    sessionId = sessionId,
                    actorDevice = "LOCAL_DEVICE",
                    eventType = AuditEventType.TRANSFER_RESUMED,
                    severity = AuditSeverity.INFO,
                    details = "Khôi phục phiên truyền tải từ vị trí checkpoint byte: ${session.confirmedOffset}/${session.totalBytes}"
                )
            )

            val updatedSession = session.copy(status = SessionStatus.TRANSFERRING)
            Result.success(updatedSession)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend operator fun invoke(sessionId: String): Result<TransferSession> = execute(sessionId)
}

