package com.teamshare.app.domain.statemachine

/**
 * Các sự kiện tác động lên máy trạng thái phiên truyền tải tài liệu.
 */
sealed class SessionEvent {
    object StartHandshake : SessionEvent()
    object HandshakeSuccess : SessionEvent()
    object AuthenticationFailed : SessionEvent()
    object StartTransfer : SessionEvent()
    data class ChunkAckReceived(val confirmedOffset: Long) : SessionEvent()
    object Pause : SessionEvent()
    object Resume : SessionEvent()
    object AllBytesReceived : SessionEvent()
    object VerifySuccess : SessionEvent()
    object VerifyFailed : SessionEvent()
    object Cancel : SessionEvent()
    data class Fail(val reason: String) : SessionEvent()
    object RevokeMember : SessionEvent()
}

