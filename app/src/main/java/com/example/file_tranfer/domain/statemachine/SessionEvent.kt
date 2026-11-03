package com.example.file_tranfer.domain.statemachine

import com.example.file_tranfer.domain.model.QrPayload

/**
 * Các sự kiện (Triggers) kích hoạt sự chuyển đổi trạng thái trong SessionStateMachine.
 */
sealed class SessionEvent {
    object GenerateQr : SessionEvent()
    data class ScanQr(val payload: QrPayload) : SessionEvent()
    object StartPairing : SessionEvent()
    data class SasCalculated(val sasCode: String) : SessionEvent()
    data class SasVerified(val isConfirmed: Boolean) : SessionEvent()
    object PolicyAccepted : SessionEvent()
    data class PolicyRejected(val reason: String) : SessionEvent()
    object StartTransfer : SessionEvent()
    object PauseTransfer : SessionEvent()
    object ResumeTransfer : SessionEvent()
    object StartHashVerification : SessionEvent()
    data class HashVerificationResult(val isSuccess: Boolean) : SessionEvent()
    data class Revoke(val reason: String) : SessionEvent()
    data class Fail(val throwable: Throwable) : SessionEvent()
    object Reset : SessionEvent()
}

