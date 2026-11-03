package com.example.file_tranfer.domain.statemachine

/**
 * 12 trạng thái trong vòng đời của một phiên chia sẻ P2P SecureShare.
 */
enum class SessionState {
    IDLE,
    QR_GENERATED,
    QR_SCANNED,
    PAIRING_NEGOTIATING,
    SAS_VERIFYING,
    POLICY_NEGOTIATING,
    TRANSFERRING,
    PAUSED,
    VERIFYING_HASH,
    COMPLETED,
    REVOKED,
    FAILED;

    /**
     * Kiểm tra trạng thái hiện tại có phải trạng thái kết thúc (không thể chuyển tiếp trừ khi Reset) hay không.
     */
    val isTerminal: Boolean
        get() = this == COMPLETED || this == REVOKED || this == FAILED

    /**
     * Kiểm tra phiên có đang trong quá trình truyền dữ liệu hay không.
     */
    val isActiveTransfer: Boolean
        get() = this == TRANSFERRING || this == VERIFYING_HASH
}

