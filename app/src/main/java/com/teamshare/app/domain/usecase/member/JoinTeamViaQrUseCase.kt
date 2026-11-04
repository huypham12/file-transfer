package com.teamshare.app.domain.usecase.member

import com.teamshare.app.core.security.crypto.CryptoEngine
import com.teamshare.app.core.security.crypto.Sha256StreamCalculator
import com.teamshare.app.core.security.keystore.IKeyManager
import com.teamshare.app.domain.model.AuditLog
import com.teamshare.app.domain.model.AuditEventType
import com.teamshare.app.domain.model.AuditSeverity
import com.teamshare.app.domain.model.ErrorCode
import com.teamshare.app.domain.model.MemberRole
import com.teamshare.app.domain.model.QrInvitePayload
import com.teamshare.app.domain.model.Team
import com.teamshare.app.domain.model.TeamMember
import com.teamshare.app.domain.model.TeamStatus
import com.teamshare.app.domain.repository.IAuditRepository
import com.teamshare.app.domain.repository.ITeamRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import java.util.UUID

/**
 * UC-04: Quét QR xin gia nhập & Cấp phát TeamMembershipCredential (SRS FR-MEM-02).
 * Thẩm định chữ ký số Owner trên payload QR, sinh khóa danh tính phần cứng của Member,
 * lưu trữ thành viên vào CSDL và phát luồng kết quả.
 */
class JoinTeamViaQrUseCase(
    private val teamRepository: ITeamRepository,
    private val keyManager: IKeyManager,
    private val auditRepository: IAuditRepository? = null
) {
    operator fun invoke(qrPayload: QrInvitePayload, memberName: String): Flow<Result<TeamMember>> = flow {
        val trimmedName = memberName.trim()
        if (trimmedName.isBlank()) {
            emit(Result.failure(IllegalArgumentException("Tên thành viên không được để trống")))
            return@flow
        }

        // 1. Kiểm tra hết hạn TTL
        if (qrPayload.isExpired()) {
            emit(Result.failure(IllegalStateException("Lời mời tham gia nhóm đã hết hạn (Mã lỗi: ${ErrorCode.ERR_INVITATION_EXPIRED})")))
            return@flow
        }

        val result: Result<TeamMember> = try {
            // 2. Thẩm định chữ ký số của Owner
            val ownerPubKeyBytes = parseHex(qrPayload.ownerPublicKey)
            val ownerPubKey = CryptoEngine.decodeEcPublicKey(ownerPubKeyBytes)
            val payloadData = "${qrPayload.teamId}|${qrPayload.invitationToken}|${qrPayload.expiresAt}"
            val signatureBytes = parseHex(qrPayload.signature)

            val isSignatureValid = CryptoEngine.verifySignature(ownerPubKey, payloadData.toByteArray(), signatureBytes)
            if (!isSignatureValid) {
                Result.failure(SecurityException("Chữ ký số Owner trên mã QR không hợp lệ (Mã lỗi: ${ErrorCode.ERR_INVALID_CREDENTIAL})"))
            } else {
                val ownerFingerprint = Sha256StreamCalculator.calculateBytesSha256(ownerPubKeyBytes)

                // 3. Đảm bảo bản ghi Không gian Nhóm (Team) tồn tại trên thiết bị này trước khi thêm thành viên (chống lỗi FOREIGN KEY SQLite code 19/787)
                val existingTeam = teamRepository.getTeamById(qrPayload.teamId).firstOrNull()
                if (existingTeam == null) {
                    val teamNameToUse = qrPayload.teamName.ifBlank { "Không gian nhóm (${qrPayload.teamId.take(8)})" }
                    val newTeam = Team(
                        teamId = qrPayload.teamId,
                        teamName = teamNameToUse,
                        description = "Gia nhập qua mã QR",
                        ownerFingerprint = ownerFingerprint,
                        status = TeamStatus.ACTIVE
                    )
                    val createResult = teamRepository.createTeam(newTeam)
                    if (createResult.isFailure) {
                        return@flow emit(Result.failure(createResult.exceptionOrNull() ?: Exception("Không thể khởi tạo nhóm trên thiết bị")))
                    }

                    // Lưu thông tin Trưởng nhóm (Owner) vào danh bạ thành viên nhóm trên thiết bị này
                    val ownerMember = TeamMember(
                        memberId = ownerFingerprint,
                        teamId = qrPayload.teamId,
                        memberName = "Trưởng nhóm (Owner)",
                        deviceFingerprint = ownerFingerprint,
                        role = MemberRole.OWNER,
                        credentialData = qrPayload.ownerPublicKey,
                        isActive = true
                    )
                    teamRepository.addMember(ownerMember)
                }

                // 4. Khởi tạo danh tính thiết bị của Member
                val memberPubKey = keyManager.generateMemberIdentityKey()
                val deviceFingerprint = Sha256StreamCalculator.calculateBytesSha256(memberPubKey.encoded)
                val memberId = UUID.randomUUID().toString()

                val member = TeamMember(
                    memberId = memberId,
                    teamId = qrPayload.teamId,
                    memberName = trimmedName,
                    deviceFingerprint = deviceFingerprint,
                    role = MemberRole.MEMBER,
                    credentialData = qrPayload.signature,
                    isActive = true
                )

                val addResult = teamRepository.addMember(member)
                if (addResult.isFailure) {
                    Result.failure(addResult.exceptionOrNull() ?: Exception("Không thể lưu thành viên vào CSDL"))
                } else {
                    auditRepository?.logEvent(
                        AuditLog(
                            teamId = qrPayload.teamId,
                            actorId = memberId,
                            eventType = AuditEventType.MEMBER_JOINED,
                            severity = AuditSeverity.INFO,
                            details = "Thành viên $trimmedName (Fingerprint: $deviceFingerprint) đã gia nhập nhóm thành công"
                        )
                    )
                    Result.success(member)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

        emit(result)
    }

    private fun parseHex(hex: String): ByteArray {
        val cleanHex = hex.trim()
        val len = cleanHex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(cleanHex[i], 16) shl 4) + Character.digit(cleanHex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }
}
