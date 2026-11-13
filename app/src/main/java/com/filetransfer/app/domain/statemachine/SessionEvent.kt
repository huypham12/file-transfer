package com.filetransfer.app.domain.statemachine

import com.filetransfer.app.domain.model.DeviceProfile

/**
 * Các sự kiện tác động lên máy trạng thái phiên truyền tải tài liệu 1-N Secure Drop.
 * Tuân thủ SDD mục 7.6.
 */
sealed class SessionEvent {
    // 1-N Drop Session Events
    object CreateSession : SessionEvent()
    data class PeerConnecting(val peerProfile: DeviceProfile? = null) : SessionEvent()
    data class RequireApproval(val peerProfile: DeviceProfile) : SessionEvent()
    object ApproveConnection : SessionEvent()
    data class RejectConnection(val reason: String = "") : SessionEvent()

    // Data Transfer Events
    object StartTransfer : SessionEvent()
    data class ChunkAckReceived(val confirmedOffset: Long) : SessionEvent()
    object Pause : SessionEvent()
    object Resume : SessionEvent()
    object AllBytesReceived : SessionEvent()
    object VerifySuccess : SessionEvent()
    object VerifyFailed : SessionEvent()
    object Cancel : SessionEvent()
    data class Fail(val reason: String) : SessionEvent()

    // Backward compatibility aliases
    object StartHandshake : SessionEvent()
    object HandshakeSuccess : SessionEvent()
    object AuthenticationFailed : SessionEvent()
    object RevokeMember : SessionEvent()
}
