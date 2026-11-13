package com.filetransfer.app.core.platform.service

/**
 * Giao diện trừu tượng hóa việc khởi chạy và điều khiển dịch vụ Android Foreground Service.
 * Tuân thủ Clean Architecture: Tách biệt logic điều phối (TransferCoordinator) khỏi Android Context.
 */
interface ITransferForegroundServiceLauncher {
    /**
     * Khởi chạy TransferForegroundService với thông tin phiên truyền tải.
     */
    fun startService(
        sessionId: String,
        documentName: String,
        totalBytes: Long,
        isLan: Boolean
    )

    /**
     * Tạm dừng truyền tải từ phía Service.
     */
    fun pauseService()

    /**
     * Tiếp tục truyền tải từ phía Service.
     */
    fun resumeService()

    /**
     * Hủy bỏ phiên truyền và dừng Service.
     */
    fun cancelService()

    /**
     * Dừng Foreground Service và giải phóng toàn bộ khóa WakeLock/WifiLock.
     */
    fun stopService()
}

