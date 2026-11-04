package com.teamshare.app.domain.usecase.team

import com.teamshare.app.core.security.crypto.Sha256StreamCalculator
import com.teamshare.app.core.security.keystore.IKeyManager
import com.teamshare.app.domain.model.AuditLog
import com.teamshare.app.domain.model.AuditEventType
import com.teamshare.app.domain.model.AuditSeverity
import com.teamshare.app.domain.model.MemberRole
import com.teamshare.app.domain.model.Team
import com.teamshare.app.domain.model.TeamMember
import com.teamshare.app.domain.model.TeamStatus
import com.teamshare.app.domain.repository.IAuditRepository
import com.teamshare.app.domain.repository.ITeamRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.UUID

/**
 * UC-01: Tạo nhóm cộng tác & Khởi tạo cặp khóa mật mã nhóm (SRS FR-TEAM-01).
 * Sinh cặp khóa bất đối xứng ECDSA trong Android Keystore TEE đại diện vai trò Owner,
 * băm Public Key ra ownerFingerprint, tạo thực thể Team và cấp Credential Owner ban đầu.
 */
class CreateTeamUseCase(
    private val teamRepository: ITeamRepository,
    private val keyManager: IKeyManager,
    private val auditRepository: IAuditRepository? = null
) {
    operator fun invoke(name: String, desc: String): Flow<Result<Team>> = flow {
        val trimmedName = name.trim()
        if (trimmedName.isBlank() || trimmedName.length > 50) {
            emit(Result.failure(IllegalArgumentException("Tên nhóm phải từ 1 đến 50 ký tự và không được để trống")))
            return@flow
        }

        val result: Result<Team> = try {
            val teamId = UUID.randomUUID().toString()
            val ownerPublicKey = keyManager.generateOwnerMasterKey(teamId)
            val ownerFingerprint = Sha256StreamCalculator.calculateBytesSha256(ownerPublicKey.encoded)

            val team = Team(
                teamId = teamId,
                teamName = trimmedName,
                description = desc.trim(),
                ownerFingerprint = ownerFingerprint,
                status = TeamStatus.ACTIVE
            )

            val createdTeamResult = teamRepository.createTeam(team)
            if (createdTeamResult.isFailure) {
                Result.failure(createdTeamResult.exceptionOrNull() ?: Exception("Không thể tạo nhóm trong CSDL"))
            } else {
                // Ký số cấp chứng thư cho chính Owner
                val masterKeyAlias = IKeyManager.MASTER_KEY_PREFIX + teamId
                val ownerCredData = "$teamId|$ownerFingerprint|OWNER"
                val ownerSignatureBytes = keyManager.signData(masterKeyAlias, ownerCredData.toByteArray())
                val ownerSignatureHex = ownerSignatureBytes.joinToString("") { "%02x".format(it) }

                val ownerMember = TeamMember(
                    memberId = ownerFingerprint,
                    teamId = teamId,
                    memberName = "Trưởng nhóm (Owner)",
                    deviceFingerprint = ownerFingerprint,
                    role = MemberRole.OWNER,
                    credentialData = ownerSignatureHex,
                    isActive = true
                )
                teamRepository.addMember(ownerMember)

                auditRepository?.logEvent(
                    AuditLog(
                        teamId = teamId,
                        actorId = ownerFingerprint,
                        eventType = AuditEventType.TEAM_CREATED,
                        severity = AuditSeverity.INFO,
                        details = "Khởi tạo thành công nhóm $trimmedName với danh tính phần cứng $ownerFingerprint"
                    )
                )

                Result.success(team)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

        emit(result)
    }
}
