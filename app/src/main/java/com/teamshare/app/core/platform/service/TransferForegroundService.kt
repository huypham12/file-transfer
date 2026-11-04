package com.teamshare.app.core.platform.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.teamshare.app.core.transfer.lan.TransferEngineState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Dịch vụ Nền tảng Di động duy trì tiến trình truyền tệp chống Doze Mode và LMK.
 * Tuân thủ:
 * - SRS FR-MOB-03 & SAD ADR-08: Khai báo android:foregroundServiceType="dataSync", hiển thị Sticky Notification.
 * - Quản lý an toàn PowerManager.WakeLock (PARTIAL_WAKE_LOCK) và WifiManager.WifiLock (WIFI_MODE_FULL_HIGH_PERF).
 * - Tự động giải phóng 100% tài nguyên khóa khi kết thúc hoặc gặp lỗi (Zero WakeLock Leak).
 */
class TransferForegroundService : Service() {

    companion object {
        const val ACTION_START_TRANSFER = "com.teamshare.app.service.START_TRANSFER"
        const val ACTION_PAUSE_TRANSFER = "com.teamshare.app.service.PAUSE_TRANSFER"
        const val ACTION_RESUME_TRANSFER = "com.teamshare.app.service.RESUME_TRANSFER"
        const val ACTION_CANCEL_TRANSFER = "com.teamshare.app.service.CANCEL_TRANSFER"
        const val ACTION_STOP_SERVICE = "com.teamshare.app.service.STOP_SERVICE"

        const val EXTRA_SESSION_ID = "extra_session_id"
        const val EXTRA_DOC_NAME = "extra_doc_name"
        const val EXTRA_TOTAL_BYTES = "extra_total_bytes"
        const val EXTRA_IS_LAN = "extra_is_lan"

        const val MAX_WAKELOCK_TIMEOUT_MS = 2 * 60 * 60 * 1000L // Tối đa 2 giờ

        /**
         * Hàm tiện ích khởi chạy dịch vụ truyền tệp từ UI hoặc UseCase.
         */
        fun start(
            context: Context,
            sessionId: String,
            documentName: String,
            totalBytes: Long,
            isLan: Boolean
        ) {
            val intent = Intent(context, TransferForegroundService::class.java).apply {
                action = ACTION_START_TRANSFER
                putExtra(EXTRA_SESSION_ID, sessionId)
                putExtra(EXTRA_DOC_NAME, documentName)
                putExtra(EXTRA_TOTAL_BYTES, totalBytes)
                putExtra(EXTRA_IS_LAN, isLan)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun pause(context: Context) {
            val intent = Intent(context, TransferForegroundService::class.java).apply {
                action = ACTION_PAUSE_TRANSFER
            }
            context.startService(intent)
        }

        fun resume(context: Context) {
            val intent = Intent(context, TransferForegroundService::class.java).apply {
                action = ACTION_RESUME_TRANSFER
            }
            context.startService(intent)
        }

        fun cancel(context: Context) {
            val intent = Intent(context, TransferForegroundService::class.java).apply {
                action = ACTION_CANCEL_TRANSFER
            }
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, TransferForegroundService::class.java).apply {
                action = ACTION_STOP_SERVICE
            }
            context.startService(intent)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var stateObserverJob: Job? = null

    private lateinit var notificationHelper: NotificationManagerHelper

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private var currentSessionId: String? = null
    private var currentDocName: String = "Tài liệu nhóm"
    private var currentTotalBytes: Long = 0L
    private var isLanTransfer: Boolean = true

    override fun onCreate() {
        super.onCreate()
        notificationHelper = NotificationManagerHelper(applicationContext)
        initializeLocks()
        observeControllerState()
    }

    private fun initializeLocks() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = powerManager?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "TeamShare:TransferWakeLock"
            )?.apply {
                setReferenceCounted(false)
            }
        } catch (e: Exception) {
            wakeLock = null
        }

        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifiLock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                wifiManager?.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "TeamShare:TransferWifiLock")
            } else {
                @Suppress("DEPRECATION")
                wifiManager?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "TeamShare:TransferWifiLock")
            }?.apply {
                setReferenceCounted(false)
            }
        } catch (e: Exception) {
            wifiLock = null
        }
    }

    private fun acquireLocks() {
        try {
            wakeLock?.let {
                if (!it.isHeld) {
                    it.acquire(MAX_WAKELOCK_TIMEOUT_MS)
                }
            }
        } catch (e: Exception) {
        }

        try {
            wifiLock?.let {
                if (!it.isHeld) {
                    it.acquire()
                }
            }
        } catch (e: Exception) {
        }
    }

    private fun releaseLocks() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
        } catch (e: Exception) {
        }

        try {
            wifiLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
        } catch (e: Exception) {
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        when (action) {
            ACTION_START_TRANSFER -> {
                currentSessionId = intent.getStringExtra(EXTRA_SESSION_ID)
                currentDocName = intent.getStringExtra(EXTRA_DOC_NAME) ?: "Tài liệu nhóm"
                currentTotalBytes = intent.getLongExtra(EXTRA_TOTAL_BYTES, 0L)
                isLanTransfer = intent.getBooleanExtra(EXTRA_IS_LAN, true)

                acquireLocks()
                startForegroundWithNotification()

                currentSessionId?.let { sessionId ->
                    TransferServiceController.startSession(
                        sessionId = sessionId,
                        documentName = currentDocName,
                        totalBytes = currentTotalBytes,
                        isLan = isLanTransfer
                    )
                }
            }

            ACTION_PAUSE_TRANSFER, NotificationManagerHelper.ACTION_PAUSE -> {
                releaseLocks()
                TransferServiceController.requestPause()
                notificationHelper.notifyTransferProgress(
                    docName = currentDocName,
                    bytesTransferred = TransferServiceController.serviceState.value.bytesTransferred,
                    totalBytes = currentTotalBytes,
                    speedBytesPerSec = 0L,
                    isPaused = true,
                    isLan = isLanTransfer,
                    force = true
                )
            }

            ACTION_RESUME_TRANSFER, NotificationManagerHelper.ACTION_RESUME -> {
                acquireLocks()
                TransferServiceController.requestResume()
                notificationHelper.notifyTransferProgress(
                    docName = currentDocName,
                    bytesTransferred = TransferServiceController.serviceState.value.bytesTransferred,
                    totalBytes = currentTotalBytes,
                    speedBytesPerSec = 0L,
                    isPaused = false,
                    isLan = isLanTransfer,
                    force = true
                )
            }

            ACTION_CANCEL_TRANSFER, NotificationManagerHelper.ACTION_CANCEL -> {
                releaseLocks()
                TransferServiceController.requestCancel()
                stopForegroundAndSelf()
            }

            ACTION_STOP_SERVICE -> {
                releaseLocks()
                stopForegroundAndSelf()
            }
        }

        return START_NOT_STICKY
    }

    private fun startForegroundWithNotification() {
        val initialNotification = notificationHelper.buildTransferNotification(
            docName = currentDocName,
            bytesTransferred = 0L,
            totalBytes = currentTotalBytes,
            speedBytesPerSec = 0L,
            isPaused = false,
            isLan = isLanTransfer
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NotificationManagerHelper.NOTIFICATION_ID_TRANSFER,
                initialNotification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NotificationManagerHelper.NOTIFICATION_ID_TRANSFER, initialNotification)
        }
    }

    private fun observeControllerState() {
        stateObserverJob = serviceScope.launch {
            TransferServiceController.serviceState.collectLatest { state ->
                when (state.engineState) {
                    TransferEngineState.TRANSFERRING -> {
                        notificationHelper.notifyTransferProgress(
                            docName = state.documentName ?: currentDocName,
                            bytesTransferred = state.bytesTransferred,
                            totalBytes = state.totalBytes,
                            speedBytesPerSec = state.speedBytesPerSec,
                            isPaused = false,
                            isLan = state.isLan
                        )
                    }

                    TransferEngineState.PAUSED -> {
                        releaseLocks()
                        notificationHelper.notifyTransferProgress(
                            docName = state.documentName ?: currentDocName,
                            bytesTransferred = state.bytesTransferred,
                            totalBytes = state.totalBytes,
                            speedBytesPerSec = 0L,
                            isPaused = true,
                            isLan = state.isLan,
                            force = true
                        )
                    }

                    TransferEngineState.COMPLETED -> {
                        releaseLocks()
                        val hash = state.finalSha256 ?: ""
                        notificationHelper.notifyCompleted(
                            docName = state.documentName ?: currentDocName,
                            totalBytes = state.totalBytes,
                            finalSha256 = hash
                        )
                        stopForegroundAndSelf()
                    }

                    TransferEngineState.FAILED -> {
                        releaseLocks()
                        notificationHelper.notifyError(
                            docName = state.documentName ?: currentDocName,
                            errorCode = state.errorCode ?: "ERR_UNKNOWN",
                            errorMessage = state.errorMessage ?: "Lỗi truyền tải không xác định"
                        )
                        stopForegroundAndSelf()
                    }

                    TransferEngineState.CANCELLED -> {
                        releaseLocks()
                        stopForegroundAndSelf()
                    }

                    else -> {
                        // IDLE, CONNECTING, HANDSHAKING...
                    }
                }
            }
        }
    }

    private fun stopForegroundAndSelf() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseLocks()
        notificationHelper.cancelTransferNotification()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    // Testing hooks
    internal fun isWakeLockHeld(): Boolean = wakeLock?.isHeld == true
    internal fun isWifiLockHeld(): Boolean = wifiLock?.isHeld == true
}

