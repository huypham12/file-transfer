package com.teamshare.app.core.platform.service

import com.teamshare.app.core.transfer.lan.TransferEngineState
import com.teamshare.app.core.transfer.lan.TransferProgressCallback
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Trạng thái của dịch vụ truyền tải nền tảng.
 */
data class TransferServiceState(
    val isRunning: Boolean = false,
    val sessionId: String? = null,
    val documentName: String? = null,
    val totalBytes: Long = 0L,
    val bytesTransferred: Long = 0L,
    val speedBytesPerSec: Long = 0L,
    val isLan: Boolean = true,
    val engineState: TransferEngineState = TransferEngineState.IDLE,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val finalSha256: String? = null
)

/**
 * Giao diện trừu tượng cho bộ điều khiển dịch vụ truyền tải nền tảng.
 */
interface ITransferServiceController {
    val serviceState: StateFlow<TransferServiceState>

    fun registerCallback(callback: TransferProgressCallback)
    fun unregisterCallback(callback: TransferProgressCallback)

    fun startSession(sessionId: String, documentName: String, totalBytes: Long, isLan: Boolean)
    fun notifyProgress(bytesTransferred: Long, totalBytes: Long, speedBytesPerSec: Long)
    fun notifyStateChanged(state: TransferEngineState)
    fun notifyCompleted(docId: String, finalSha256: String)
    fun notifyError(errorCode: String, message: String)

    fun requestPause()
    fun requestResume()
    fun requestCancel()

    fun reset()
}

/**
 * Bộ điều khiển tập trung (Singleton Controller) điều phối luồng trạng thái
 * giữa Android Foreground Service và các động cơ truyền tải LAN/Relay (ADR-SRV-01).
 */
object TransferServiceController : ITransferServiceController {

    private val _serviceState = MutableStateFlow(TransferServiceState())
    override val serviceState: StateFlow<TransferServiceState> = _serviceState.asStateFlow()

    private val callbacks = CopyOnWriteArrayList<TransferProgressCallback>()

    // Listener nhận lệnh điều khiển từ Service Notification
    var commandListener: CommandListener? = null

    interface CommandListener {
        fun onPauseRequested()
        fun onResumeRequested()
        fun onCancelRequested()
    }

    override fun registerCallback(callback: TransferProgressCallback) {
        if (!callbacks.contains(callback)) {
            callbacks.add(callback)
        }
    }

    override fun unregisterCallback(callback: TransferProgressCallback) {
        callbacks.remove(callback)
    }

    override fun startSession(sessionId: String, documentName: String, totalBytes: Long, isLan: Boolean) {
        _serviceState.update {
            TransferServiceState(
                isRunning = true,
                sessionId = sessionId,
                documentName = documentName,
                totalBytes = totalBytes,
                bytesTransferred = 0L,
                speedBytesPerSec = 0L,
                isLan = isLan,
                engineState = TransferEngineState.CONNECTING
            )
        }
    }

    override fun notifyProgress(bytesTransferred: Long, totalBytes: Long, speedBytesPerSec: Long) {
        _serviceState.update { current ->
            current.copy(
                bytesTransferred = bytesTransferred,
                totalBytes = if (totalBytes > 0) totalBytes else current.totalBytes,
                speedBytesPerSec = speedBytesPerSec,
                engineState = TransferEngineState.TRANSFERRING
            )
        }

        for (cb in callbacks) {
            try {
                cb.onProgress(bytesTransferred, totalBytes, speedBytesPerSec)
            } catch (e: Exception) {
            }
        }
    }

    override fun notifyStateChanged(state: TransferEngineState) {
        _serviceState.update { current ->
            current.copy(
                engineState = state,
                isRunning = state != TransferEngineState.COMPLETED &&
                        state != TransferEngineState.FAILED &&
                        state != TransferEngineState.CANCELLED &&
                        state != TransferEngineState.IDLE
            )
        }

        for (cb in callbacks) {
            try {
                cb.onStateChanged(state)
            } catch (e: Exception) {
            }
        }
    }

    override fun notifyCompleted(docId: String, finalSha256: String) {
        _serviceState.update { current ->
            current.copy(
                isRunning = false,
                bytesTransferred = current.totalBytes,
                engineState = TransferEngineState.COMPLETED,
                finalSha256 = finalSha256
            )
        }

        for (cb in callbacks) {
            try {
                cb.onCompleted(docId, finalSha256)
            } catch (e: Exception) {
            }
        }
    }

    override fun notifyError(errorCode: String, message: String) {
        _serviceState.update { current ->
            current.copy(
                isRunning = false,
                engineState = TransferEngineState.FAILED,
                errorCode = errorCode,
                errorMessage = message
            )
        }

        for (cb in callbacks) {
            try {
                cb.onError(errorCode, message)
            } catch (e: Exception) {
            }
        }
    }

    override fun requestPause() {
        commandListener?.onPauseRequested()
    }

    override fun requestResume() {
        commandListener?.onResumeRequested()
    }

    override fun requestCancel() {
        commandListener?.onCancelRequested()
    }

    override fun reset() {
        _serviceState.value = TransferServiceState()
        callbacks.clear()
        commandListener = null
    }
}

