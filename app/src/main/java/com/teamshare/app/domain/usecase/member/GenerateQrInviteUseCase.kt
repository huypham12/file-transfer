package com.teamshare.app.domain.usecase.member

import com.teamshare.app.core.platform.network.NetworkAddressHelper
import com.teamshare.app.core.security.crypto.CryptoEngine
import com.teamshare.app.core.security.keystore.IKeyManager
import com.teamshare.app.domain.model.AuditLog
import com.teamshare.app.domain.model.AuditEventType
import com.teamshare.app.domain.model.AuditSeverity
import com.teamshare.app.domain.model.QrInvitePayload
import com.teamshare.app.domain.repository.IAuditRepository
import com.teamshare.app.domain.repository.ITeamRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow

/**
 * UC-03: Tạo lời mời tham gia nhóm qua mã QR động (SRS FR-MEM-01).
 * Sinh token ngẫu nhiên 128-bit, gán TTL (mặc định 600s = 10 phút),
 * Owner dùng Master Key ký số lên dữ liệu lời mời để chống giả mạo.
 */
class GenerateQrInviteUseCase(
    private val teamRepository: ITeamRepository,
    private val keyManager: IKeyManager,
    private val auditRepository: IAuditRepository? = null
) {
    operator fun invoke(
        teamId: String,
        ttlSec: Long = 600L,
        customHost: String? = null,
        port: Int = NetworkAddressHelper.DEFAULT_SYNC_PORT,
        customRelayUrl: String? = null
    ): Flow<Result<QrInvitePayload>> = flow {
        if (teamId.isBlank()) {
            emit(Result.failure(IllegalArgumentException("teamId không được để trống")))
            return@flow
        }

        val result: Result<QrInvitePayload> = try {
            val team = teamRepository.getTeamById(teamId).firstOrNull()
            if (team == null || !team.isActive) {
                Result.failure(IllegalStateException("Nhóm không tồn tại hoặc không ở trạng thái ACTIVE"))
            } else {
                val tokenBytes = CryptoEngine.generateRandomBytes(16)
                val invitationToken = tokenBytes.joinToString("") { "%02x".format(it) }
                val expiresAt = (System.currentTimeMillis() / 1000L) + ttlSec

                val masterKeyAlias = IKeyManager.MASTER_KEY_PREFIX + teamId
                val ownerPublicKey = keyManager.getPublicKey(masterKeyAlias)
                    ?: throw IllegalStateException("Không tìm thấy Master Key của Owner cho nhóm $teamId")

                val ownerPublicKeyHex = ownerPublicKey.encoded.joinToString("") { "%02x".format(it) }
                val payloadData = "$teamId|$invitationToken|$expiresAt"
                val signatureBytes = keyManager.signData(masterKeyAlias, payloadData.toByteArray())
                val signatureHex = signatureBytes.joinToString("") { "%02x".format(it) }

                val ownerHost = customHost ?: NetworkAddressHelper.getLocalIpv4Address()
                val relayUrl = customRelayUrl ?: NetworkAddressHelper.getDefaultRelayUrl(ownerHost)

                val qrPayload = QrInvitePayload(
                    teamId = teamId,
                    invitationToken = invitationToken,
                    expiresAt = expiresAt,
                    ownerPublicKey = ownerPublicKeyHex,
                    signature = signatureHex,
                    teamName = team.teamName,
                    ownerHost = ownerHost,
                    ownerPort = port,
                    relayUrl = relayUrl
                )

                auditRepository?.logEvent(
                    AuditLog(
                        teamId = teamId,
                        actorId = team.ownerFingerprint,
                        eventType = AuditEventType.INVITATION_GENERATED,
                        severity = AuditSeverity.INFO,
                        details = "Sinh mã QR lời mời mới có thời hạn đến $expiresAt (TTL $ttlSec giây)"
                    )
                )

                Result.success(qrPayload)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

        emit(result)
    }
}
