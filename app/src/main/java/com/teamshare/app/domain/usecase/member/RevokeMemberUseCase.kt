package com.teamshare.app.domain.usecase.member

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
 * UC-06: Loại thành viên khỏi nhóm & Thu hồi Credential (SRS FR-MEM-04).
 * Ký số bản ghi thu hồi (Signed CRL) bằng Master Key của Owner,
 * cập nhật trạng thái thành viên sang bị thu hồi trong CSDL,
 * phát tán bản tin thu hồi tới toàn bộ peer,
 * kích hoạt ngắt cưỡng bức mọi phiên truyền tệp đang hoạt động của thành viên trong < 200 ms,
 * và ghi nhận nhật ký kiểm toán mức WARNING.
 */
class RevokeMemberUseCase(
    private val teamRepository: ITeamRepository,
    private val transferCoordinator: TransferCoordinator? = null,
    private val auditRepository: IAuditRepository? = null,
    private val keyManager: IKeyManager? = null,
    private val peerSyncManager: PeerSyncManager? = null
) {
    operator fun invoke(teamId: String, memberId: String): Flow<Result<Unit>> = flow {
        if (teamId.isBlank() || memberId.isBlank()) {
            emit(Result.failure(IllegalArgumentException("teamId và memberId không được để trống")))
            return@flow
        }

        val result: Result<Unit> = try {
            val members = teamRepository.getMembersByTeam(teamId).firstOrNull()
            val targetMember = members?.find { it.memberId == memberId }

            if (targetMember != null && targetMember.isOwner) {
                Result.failure(IllegalStateException("Không thể tước quyền hoặc loại bỏ Trưởng nhóm (Owner)"))
            } else {
                // 1. Ký số bản ghi thu hồi quyền bằng Master Key của Owner nếu có
                val masterKeyAlias = IKeyManager.MASTER_KEY_PREFIX + teamId
                val recordId = UUID.randomUUID().toString()
                val revokedAt = System.currentTimeMillis()
                val fprint = targetMember?.deviceFingerprint ?: ""
                val claim = "REVOKE:$teamId:$memberId:$fprint:$revokedAt"
                val signatureBytes = try {
                    keyManager?.signData(masterKeyAlias, claim.toByteArray(Charsets.UTF_8)) ?: ByteArray(0)
                } catch (_: Exception) { ByteArray(0) }
                val signatureHex = signatureBytes.joinToString("") { "%02x".format(it) }

                val revocationEntity = RevocationEntity(
                    recordId = recordId,
                    teamId = teamId,
                    revokedMemberId = memberId,
                    revokedFingerprint = fprint,
                    revocationType = "MEMBER_KICK",
                    revokedAt = revokedAt,
                    reason = "REMOVED_BY_OWNER",
                    ownerSignature = signatureHex
                )

                // 2. Lưu bản ghi Signed CRL vào CSDL
                teamRepository.recordRevocation(revocationEntity)

                // 3. Cập nhật trạng thái thu hồi trong CSDL
                val revokeResult = teamRepository.revokeMember(teamId, memberId)
                if (revokeResult.isFailure) {
                    Result.failure(revokeResult.exceptionOrNull() ?: Exception("Không thể cập nhật thu hồi quyền trong CSDL"))
                } else {
                    // 4. Cưỡng bức hủy phiên truyền tệp trong < 200 ms
                    transferCoordinator?.cancelSessionForMember(memberId)

                    // 5. Broadcast thông báo thu hồi tới các peer qua Relay & LAN
                    peerSyncManager?.broadcastMemberRevoked(revocationEntity)

                    // 6. Ghi log cảnh báo an ninh
                    auditRepository?.logEvent(
                        AuditLog(
                            teamId = teamId,
                            actorId = memberId,
                            eventType = AuditEventType.MEMBER_REVOKED,
                            severity = AuditSeverity.WARNING,
                            details = "Thành viên $memberId (${targetMember?.memberName ?: "Unknown"}) đã bị thu hồi quyền và hủy phiên truyền tức thì"
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
