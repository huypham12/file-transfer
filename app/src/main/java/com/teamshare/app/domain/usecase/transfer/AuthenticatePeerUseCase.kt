package com.teamshare.app.domain.usecase.transfer

import com.teamshare.app.core.security.crypto.CryptoEngine
import com.teamshare.app.core.security.keystore.IKeyManager
import com.teamshare.app.domain.model.ErrorCode
import com.teamshare.app.domain.model.TeamMembershipCredential
import com.teamshare.app.domain.repository.ITeamRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.security.PublicKey

/**
 * UC-11: Yêu cầu tải tài liệu & Thẩm định chứng chỉ thành viên 2 chiều (SRS FR-TRF-01).
 * Kiểm tra chữ ký số của Owner trên chứng chỉ TeamMembershipCredential,
 * đối chiếu danh sách thành viên hợp lệ và loại bỏ mọi nỗ lực truy cập từ thiết bị bị thu hồi hoặc ngoài nhóm.
 */
class AuthenticatePeerUseCase(
    private val teamRepository: ITeamRepository,
    private val keyManager: IKeyManager? = null
) {
    operator fun invoke(peerCred: TeamMembershipCredential, ownerPublicKey: PublicKey? = null): Flow<Result<Boolean>> = flow {
        val result: Result<Boolean> = try {
            val pubKey = ownerPublicKey
                ?: keyManager?.getPublicKey(IKeyManager.MASTER_KEY_PREFIX + peerCred.teamId)
                ?: keyManager?.getPublicKey(peerCred.teamId)
                ?: throw IllegalStateException("Cần ownerPublicKey để thẩm định chứng chỉ thành viên")

            // 1. Kiểm tra trạng thái hoạt động trong CSDL (loại bỏ thành viên bị thu hồi)
            val isActive = teamRepository.isMemberActive(peerCred.teamId, peerCred.memberId)
            if (!isActive) {
                Result.failure(SecurityException("Thành viên đã bị thu hồi quyền hoặc không tồn tại (Mã lỗi: ${ErrorCode.ERR_MEMBER_REVOKED})"))
            } else {
                // 2. Thẩm định chữ ký số của Owner trên chứng chỉ
                val signatureBytes = parseHex(peerCred.ownerSignature)
                val payload1 = "${peerCred.teamId}|${peerCred.memberId}|${peerCred.role.name}"
                val payload2 = "${peerCred.teamId}|${peerCred.deviceFingerprint}|${peerCred.role.name}"

                val isValid1 = CryptoEngine.verifySignature(pubKey, payload1.toByteArray(), signatureBytes)
                val isValid2 = if (!isValid1) {
                    CryptoEngine.verifySignature(pubKey, payload2.toByteArray(), signatureBytes)
                } else true

                if (isValid1 || isValid2) {
                    Result.success(true)
                } else {
                    Result.failure(SecurityException("Chứng thư thành viên không hợp lệ (Mã lỗi: ${ErrorCode.ERR_INVALID_CREDENTIAL})"))
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

