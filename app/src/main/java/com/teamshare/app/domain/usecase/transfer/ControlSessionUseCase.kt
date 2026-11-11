package com.teamshare.app.domain.usecase.transfer

import com.teamshare.app.core.transfer.TransferCoordinator
import com.teamshare.app.domain.model.AuditLog
import com.teamshare.app.domain.model.AuditEventType
import com.teamshare.app.domain.model.AuditSeverity
import com.teamshare.app.domain.model.SessionCmd
import com.teamshare.app.domain.repository.IAuditRepository
import com.teamshare.app.domain.repository.ITransferRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * UC-15: Tạm dừng, Tiếp tục theo Checkpoint và Hủy phiên truyền (SRS FR-TRF-05).
 * Nhận lệnh điều khiển (PAUSE, RESUME, CANCEL) từ UI hoặc Notification Quick Action,
 * điều phối qua TransferCoordinator và cập nhật trạng thái trong CSDL.
 */
class ControlSessionUseCase(
    private val transferCoordinator: TransferCoordinator,
    private val transferRepository: ITransferRepository,
    private val auditRepository: IAuditRepository? = null
) {
    operator fun invoke(sessionId: String, command: SessionCmd): Flow<Result<Unit>> = flow {
        if (sessionId.isBlank()) {
            emit(Result.failure(IllegalArgumentException("sessionId không được để trống")))
            return@flow
        }

        val result: Result<Unit> = try {
            when (command) {
                SessionCmd.PAUSE -> {
                    transferCoordinator.pauseSession(sessionId)
                    auditRepository?.logEvent(
                        AuditLog(
                            teamId = "",
                            actorId = "user_control",
                            eventType = AuditEventType.TRANSFER_PAUSED,
                            severity = AuditSeverity.INFO,
                            details = "Người dùng tạm dừng phiên truyền $sessionId"
                        )
                    )
                }
                SessionCmd.RESUME -> {
                    transferCoordinator.resumeSession(sessionId)
                }
                SessionCmd.CANCEL -> {
                    transferCoordinator.cancelSession(sessionId)
                }
                SessionCmd.REVOKE -> {
                    transferCoordinator.cancelSession(sessionId)
                }
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

        emit(result)
    }
}

