package com.teamshare.app.core.transfer.lan

/**
 * Trạng thái vòng đời của Động cơ Truyền LAN (LanTransferEngine).
 */
enum class TransferEngineState {
    IDLE,
    CONNECTING,
    HANDSHAKING,
    TRANSFERRING,
    PAUSED,
    VERIFYING,
    COMPLETED,
    FAILED,
    CANCELLED
}

/**
 * Giao diện lắng nghe sự kiện tiến trình truyền tải dữ liệu thời gian thực.
 */
interface TransferProgressCallback {
    /**
     * Thông báo tiến trình truyền byte và tốc độ ước tính.
     */
    fun onProgress(bytesTransferred: Long, totalBytes: Long, speedBytesPerSec: Long) {}

    /**
     * Thông báo sự thay đổi trạng thái của động cơ.
     */
    fun onStateChanged(newState: TransferEngineState) {}

    /**
     * Thông báo hoàn tất truyền và xác minh toàn vẹn tệp thành công.
     */
    fun onCompleted(docId: String, finalSha256: String) {}

    /**
     * Thông báo mã xác thực ngắn SAS 6 chữ số khi bắt tay E2EE hoàn tất.
     */
    fun onSasGenerated(sasCode: String) {}

    /**
     * Thông báo lỗi trong quá trình truyền tải.
     */
    fun onError(errorCode: String, message: String) {}
}

