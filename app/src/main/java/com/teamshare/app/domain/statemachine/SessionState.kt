package com.teamshare.app.domain.statemachine

/**
 * Các trạng thái trong vòng đời của một phiên truyền tải tài liệu P2P.
 * Tuân thủ SAD mục 9.1 và SDD mục 7.6.
 */
enum class SessionState {
    IDLE,
    HANDSHAKING,
    TRANSFERRING,
    PAUSED,
    VERIFYING,
    COMPLETED,
    CANCELLED,
    FAILED,
    CHECKSUM_FAILED
}

