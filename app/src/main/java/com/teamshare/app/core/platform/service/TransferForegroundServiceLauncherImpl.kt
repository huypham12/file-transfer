package com.teamshare.app.core.platform.service

import android.content.Context
import android.content.Intent

/**
 * Hiện thực ITransferForegroundServiceLauncher kết nối trực tiếp với Android Context.
 * Tuân thủ SAD ADR-08 & SRS FR-MOB-03: Đảm bảo tiến trình sống sót khi ứng dụng chạy ngầm.
 */
class TransferForegroundServiceLauncherImpl(
    private val context: Context
) : ITransferForegroundServiceLauncher {

    override fun startService(
        sessionId: String,
        documentName: String,
        totalBytes: Long,
        isLan: Boolean
    ) {
        try {
            TransferForegroundService.start(
                context = context,
                sessionId = sessionId,
                documentName = documentName,
                totalBytes = totalBytes,
                isLan = isLan
            )
        } catch (e: Exception) {
            // Không làm sập ứng dụng nếu context bị hủy đột ngột
        }
    }

    override fun pauseService() {
        try {
            TransferForegroundService.pause(context)
        } catch (_: Exception) {}
    }

    override fun resumeService() {
        try {
            TransferForegroundService.resume(context)
        } catch (_: Exception) {}
    }

    override fun cancelService() {
        try {
            TransferForegroundService.cancel(context)
        } catch (_: Exception) {}
    }

    override fun stopService() {
        try {
            val intent = Intent(context, TransferForegroundService::class.java).apply {
                action = TransferForegroundService.ACTION_STOP_SERVICE
            }
            context.startService(intent)
        } catch (_: Exception) {}
    }
}

