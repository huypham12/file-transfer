package com.teamshare.app.domain.statemachine

/**
 * Các trạng thái trong vòng đời của một phiên truyền tải tài liệu 1-N Secure Drop.
 * Tuân thủ SAD mục 9.1 và SDD mục 7.6.
 */
enum class SessionState {
    PENDING,
    CONNECTING,
    APPROVING,
    REJECTED,
    TRANSFERRING,
    PAUSED,
    VERIFYING,
    COMPLETED,
    CANCELLED,
    FAILED,
    CHECKSUM_FAILED,

    // Backward compatibility aliases
    @Deprecated("Dùng PENDING thay thế") IDLE,
    @Deprecated("Dùng CONNECTING thay thế") HANDSHAKING
}
