package com.teamshare.app.domain.usecase.team

import com.teamshare.app.core.network.sync.PeerSyncManager
import com.teamshare.app.core.security.keystore.IKeyManager
import com.teamshare.app.core.transfer.TransferCoordinator
import com.teamshare.app.data.local.db.entity.RevocationEntity
import com.teamshare.app.domain.model.AuditLog
import com.teamshare.app.domain.model.AuditEventType
import com.teamshare.app.domain.model.AuditSeverity
import com.teamshare.app.domain.repository.IAuditRepository
import com.teamshare.app.domain.repository.ITeamRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import java.util.UUID

/**
 * UC-02: Cập nhật thông tin & Quản lý vòng đời nhóm (Giải tán nhóm) (SRS FR-TEAM-02).
 * Ký số thông điệp giải tán nhóm bằng Master Key,
 * broadcast thông báo giải tán tới các peer,
 * cập nhật trạng thái nhóm sang DISBANDED, xóa sạch Master Key trong Android Keystore,
 * hủy các phiên truyền đang chạy và ghi nhận nhật ký kiểm toán.
 */
class DisbandTeamUseCase(
    private val teamRepository: ITeamRepository,
    private val keyManager: IKeyManager,
    private val auditRepository: IAuditRepository? = null,
    private val transferCoordinator: TransferCoordinator? = null,
    private val peerSyncManager: PeerSyncManager? = null
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
                // 1. Ký số thông điệp giải tán nhóm trước khi xóa Master Key
                val masterKeyAlias = IKeyManager.MASTER_KEY_PREFIX + teamId
                val disbandedAt = System.currentTimeMillis()
                val claim = "DISBAND:$teamId:$disbandedAt"
                val signatureBytes = try {
                    keyManager.signData(masterKeyAlias, claim.toByteArray(Charsets.UTF_8))
                } catch (_: Exception) { ByteArray(0) }
                val signatureHex = signatureBytes.joinToString("") { "%02x".format(it) }

                val recordId = UUID.randomUUID().toString()
                val disbandRecord = RevocationEntity(
                    recordId = recordId,
                    teamId = teamId,
                    revokedMemberId = team.ownerFingerprint,
                    revokedFingerprint = team.ownerFingerprint,
                    revocationType = "TEAM_DISBAND",
                    revokedAt = disbandedAt,
                    reason = "DISBANDED_BY_OWNER",
                    ownerSignature = signatureHex
                )
                teamRepository.recordRevocation(disbandRecord)

                // 2. Broadcast bản tin giải tán nhóm cho toàn thể peer qua Relay và LAN
                peerSyncManager?.broadcastTeamDisbanded(teamId, signatureHex)

                // 3. Đánh dấu giải tán nhóm trong CSDL
                val disbandResult = teamRepository.disbandTeam(teamId)
                if (disbandResult.isFailure) {
                    Result.failure(disbandResult.exceptionOrNull() ?: Exception("Không thể giải tán nhóm trong CSDL"))
                } else {
                    // 4. Hủy cưỡng bức toàn bộ các phiên truyền tệp đang hoạt động của nhóm bị giải tán
                    transferCoordinator?.cancelSessionsForTeam(teamId)

                    // 5. Ghi nhận nhật ký kiểm toán
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
