package com.filetransfer.app.core.platform.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.file_tranfer.R
import com.filetransfer.app.core.constant.AppConstants
import java.util.Locale

/**
 * Lớp hỗ trợ quản lý Notification và Notification Channel cho tiến trình truyền tệp.
 * Tuân thủ:
 * - SRS FR-MOB-03: Hiển thị Sticky Notification với tiến độ %, tốc độ truyền và nút điều khiển.
 * - NFR-PERF-01 & SDD 4.2.2: Điều tiết tần suất cập nhật thông báo (Throttling >= 500ms) chống nghẽn IPC hệ thống.
 */
class NotificationManagerHelper(
    private val context: Context? = null,
    private val notificationManager: NotificationManager? = context?.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
) {
    companion object {
        const val CHANNEL_TRANSFER_ID = AppConstants.NotificationChannel.TRANSFER_ID
        const val CHANNEL_COMPLETED_ID = AppConstants.NotificationChannel.EVENTS_ID

        const val NOTIFICATION_ID_TRANSFER = AppConstants.NotificationChannel.ID_TRANSFER
        const val NOTIFICATION_ID_COMPLETED = AppConstants.NotificationChannel.ID_COMPLETED

        const val EXTRA_SESSION_ID = "extra_session_id"

        const val ACTION_PAUSE = AppConstants.ServiceAction.PAUSE
        const val ACTION_RESUME = AppConstants.ServiceAction.RESUME
        const val ACTION_CANCEL = AppConstants.ServiceAction.CANCEL

        const val THROTTLE_INTERVAL_MS = AppConstants.Timeout.PROGRESS_THROTTLE_INTERVAL_MS
    }

    private var lastNotifyTimestamp = 0L

    init {
        createNotificationChannels()
    }

    /**
     * Khởi tạo các Notification Channel theo chuẩn Android 8.0+ (API 26+).
     */
    fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val transferName = context?.getString(R.string.notification_channel_transfer_name) ?: "Tiến trình truyền tệp"
            val transferDesc = context?.getString(R.string.notification_channel_transfer_desc) ?: "Hiển thị tiến độ truyền tải tệp tin P2P và Relay thời gian thực"
            val eventsName = context?.getString(R.string.notification_channel_events_name) ?: "Thông báo hoàn tất & Sự kiện"
            val eventsDesc = context?.getString(R.string.notification_channel_events_desc) ?: "Thông báo sự kiện hoàn tất hoặc lỗi truyền tệp"

            val transferChannel = NotificationChannel(
                CHANNEL_TRANSFER_ID,
                transferName,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = transferDesc
                setShowBadge(false)
                enableVibration(false)
                enableLights(false)
            }

            val completedChannel = NotificationChannel(
                CHANNEL_COMPLETED_ID,
                eventsName,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = eventsDesc
                setShowBadge(true)
                enableVibration(true)
            }

            notificationManager?.createNotificationChannel(transferChannel)
            notificationManager?.createNotificationChannel(completedChannel)
        }
    }

    /**
     * Kiểm tra xem đã đủ điều kiện thời gian để gửi thông báo tiếp theo chưa (Throttling).
     */
    fun shouldUpdate(force: Boolean = false): Boolean {
        val now = System.currentTimeMillis()
        if (force || now - lastNotifyTimestamp >= THROTTLE_INTERVAL_MS) {
            lastNotifyTimestamp = now
            return true
        }
        return false
    }

    /**
     * Xây dựng Sticky Notification cho phiên truyền tệp đang hoạt động.
     */
    fun buildTransferNotification(
        docName: String,
        bytesTransferred: Long,
        totalBytes: Long,
        speedBytesPerSec: Long,
        isPaused: Boolean,
        isLan: Boolean,
        sessionId: String = ""
    ): Notification {
        val ctx = context ?: throw IllegalStateException("Context is required to build Notification")
        val percent = if (totalBytes > 0) ((bytesTransferred * 100) / totalBytes).toInt().coerceIn(0, 100) else 0
        val channelTag = if (isLan) ctx.getString(R.string.tag_lan_p2p) else ctx.getString(R.string.tag_internet_e2ee)
        val speedStr = formatSpeed(speedBytesPerSec)
        val transferredStr = formatBytes(bytesTransferred)
        val totalStr = formatBytes(totalBytes)

        val title = "$channelTag $docName"
        val content = if (isPaused) {
            ctx.getString(R.string.status_paused_format, transferredStr, totalStr, percent)
        } else {
            ctx.getString(R.string.status_progress_format, speedStr, transferredStr, totalStr, percent)
        }

        val builder = NotificationCompat.Builder(ctx, CHANNEL_TRANSFER_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(createContentIntent(ctx, sessionId))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)

        if (totalBytes > 0) {
            builder.setProgress(100, percent, false)
        } else {
            builder.setProgress(0, 0, true)
        }

        // Action buttons
        if (isPaused) {
            val resumeIntent = PendingIntent.getService(
                ctx,
                1,
                Intent(ctx, TransferForegroundService::class.java).setAction(ACTION_RESUME),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                android.R.drawable.ic_media_play,
                ctx.getString(R.string.action_resume),
                resumeIntent
            )
        } else {
            val pauseIntent = PendingIntent.getService(
                ctx,
                2,
                Intent(ctx, TransferForegroundService::class.java).setAction(ACTION_PAUSE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                android.R.drawable.ic_media_pause,
                ctx.getString(R.string.action_pause),
                pauseIntent
            )
        }

        val cancelIntent = PendingIntent.getService(
            ctx,
            3,
            Intent(ctx, TransferForegroundService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        builder.addAction(
            android.R.drawable.ic_menu_close_clear_cancel,
            ctx.getString(R.string.action_cancel),
            cancelIntent
        )

        return builder.build()
    }

    private fun createContentIntent(ctx: Context, sessionId: String = ""): PendingIntent {
        val intent = Intent(ctx, com.filetransfer.app.presentation.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (sessionId.isNotBlank()) {
                putExtra(EXTRA_SESSION_ID, sessionId)
            }
        }
        return PendingIntent.getActivity(
            ctx,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Xây dựng thông báo khi truyền tệp hoàn tất thành công.
     */
    fun buildCompletedNotification(
        docName: String,
        totalBytes: Long,
        finalSha256: String
    ): Notification {
        val ctx = context ?: throw IllegalStateException("Context is required to build Notification")
        val shortHash = if (finalSha256.length >= 8) finalSha256.substring(0, 8) else finalSha256
        return NotificationCompat.Builder(ctx, CHANNEL_COMPLETED_ID)
            .setContentTitle(ctx.getString(R.string.notification_completed_title, docName))
            .setContentText(ctx.getString(R.string.notification_completed_content, formatBytes(totalBytes), shortHash))
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(createContentIntent(ctx))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
    }

    /**
     * Xây dựng thông báo khi gặp sự cố truyền tải.
     */
    fun buildErrorNotification(
        docName: String,
        errorCode: String,
        errorMessage: String
    ): Notification {
        val ctx = context ?: throw IllegalStateException("Context is required to build Notification")
        return NotificationCompat.Builder(ctx, CHANNEL_COMPLETED_ID)
            .setContentTitle(ctx.getString(R.string.notification_error_title, docName))
            .setContentText(ctx.getString(R.string.notification_error_content, errorCode, errorMessage))
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentIntent(createContentIntent(ctx))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
    }

    /**
     * Cập nhật thông báo nếu thỏa mãn điều kiện throttling.
     */
    fun notifyTransferProgress(
        docName: String,
        bytesTransferred: Long,
        totalBytes: Long,
        speedBytesPerSec: Long,
        isPaused: Boolean,
        isLan: Boolean,
        force: Boolean = false,
        sessionId: String = ""
    ) {
        if (shouldUpdate(force)) {
            val notification = buildTransferNotification(
                docName,
                bytesTransferred,
                totalBytes,
                speedBytesPerSec,
                isPaused,
                isLan,
                sessionId
            )
            try {
                notificationManager?.notify(NOTIFICATION_ID_TRANSFER, notification)
            } catch (e: Exception) {
                // Ignore if security/permission restriction
            }
        }
    }

    fun notifyCompleted(docName: String, totalBytes: Long, finalSha256: String) {
        val notif = buildCompletedNotification(docName, totalBytes, finalSha256)
        try {
            notificationManager?.notify(NOTIFICATION_ID_COMPLETED, notif)
        } catch (e: Exception) {
        }
    }

    fun notifyError(docName: String, errorCode: String, errorMessage: String) {
        val notif = buildErrorNotification(docName, errorCode, errorMessage)
        try {
            notificationManager?.notify(NOTIFICATION_ID_COMPLETED, notif)
        } catch (e: Exception) {
        }
    }

    fun cancelTransferNotification() {
        try {
            notificationManager?.cancel(NOTIFICATION_ID_TRANSFER)
        } catch (e: Exception) {
        }
    }

    // Các hàm format tiện ích
    fun formatSpeed(bytesPerSec: Long): String {
        return when {
            bytesPerSec >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB/s", bytesPerSec / (1024.0 * 1024.0))
            bytesPerSec >= 1024 -> String.format(Locale.US, "%.0f KB/s", bytesPerSec / 1024.0)
            bytesPerSec > 0 -> "$bytesPerSec B/s"
            else -> "0 KB/s"
        }
    }

    fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 * 1024 -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
            bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
            bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}

