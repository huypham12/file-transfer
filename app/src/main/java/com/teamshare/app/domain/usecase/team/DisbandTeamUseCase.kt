package com.teamshare.app.domain.usecase.team

import com.teamshare.app.core.security.keystore.IKeyManager
import com.teamshare.app.core.transfer.TransferCoordinator
import com.teamshare.app.domain.model.AuditLog
import com.teamshare.app.domain.model.AuditEventType
import com.teamshare.app.domain.model.AuditSeverity
import com.teamshare.app.domain.repository.IAuditRepository
import com.teamshare.app.domain.repository.ITeamRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow

/**
 * UC-02: Cập nhật thông tin & Quản lý vòng đời nhóm (Giải tán nhóm) (SRS FR-TEAM-02).
 * Cập nhật trạng thái nhóm sang DISBANDED, xóa sạch Master Key trong Android Keystore,
 * hủy các phiên truyền đang chạy và ghi nhận nhật ký kiểm toán.
 */
class DisbandTeamUseCase(
    private val teamRepository: ITeamRepository,
    private val keyManager: IKeyManager,
    private val auditRepository: IAuditRepository? = null,
    private val transferCoordinator: TransferCoordinator? = null
) {
    operator fun invoke(teamId: String): Flow<Result<Unit>> = flow {
        if (teamId.isBlank()) {
            emit(Result.failure(IllegalArgumentException("teamId không được để trống")))
            return@flow
        }

        val result: Result<Unit> = try {
            val team = teamRepository.getTeamById(teamId).firstOrNull()
            if (team == null) {
                Result.failure(NoSuchElementException("Không tìm thấy nhóm với ID: $teamId"))
            } else {
                val disbandResult = teamRepository.disbandTeam(teamId)
                if (disbandResult.isFailure) {
                    Result.failure(disbandResult.exceptionOrNull() ?: Exception("Không thể giải tán nhóm trong CSDL"))
                } else {
                    // Hủy cưỡng bức toàn bộ các phiên truyền tệp đang hoạt động của nhóm bị giải tán
                    transferCoordinator?.cancelSessionsForTeam(teamId)

                    // Xóa khóa Master Key trong Android Keystore
                    keyManager.deleteKey(IKeyManager.MASTER_KEY_PREFIX + teamId)

                    auditRepository?.logEvent(
                        AuditLog(
                            teamId = teamId,
                            actorId = team.ownerFingerprint,
                            eventType = AuditEventType.TEAM_DISBANDED,
                            severity = AuditSeverity.WARNING,
                            details = "Nhóm ${team.teamName} đã được giải tán và tiêu hủy khóa bí mật Keystore"
                        )
                    )

                    Result.success(Unit)
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
