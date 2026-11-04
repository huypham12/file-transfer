package com.teamshare.app.core.network.relay

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lớp bao đóng kết nối WebSocket OkHttp hỗ trợ truyền nhận bất đồng bộ theo luồng Coroutines.
 */
class RelayWebSocketConnection(
    private val client: OkHttpClient = defaultClient
) : Closeable {

    private var webSocket: WebSocket? = null
    private val isConnected = AtomicBoolean(false)
    private val isClosed = AtomicBoolean(false)

    private val connectDeferred = CompletableDeferred<Unit>()
    private val textChannel = Channel<String>(Channel.UNLIMITED)
    private val binaryChannel = Channel<ByteArray>(Channel.UNLIMITED)

    /**
     * Mở kết nối WebSocket tới [wsUrl].
     *
     * @param wsUrl Đường dẫn WebSocket (ws:// hoặc wss://)
     * @param timeoutMs Thời gian chờ tối đa để thiết lập kết nối (mặc định 15s)
     */
    suspend fun connect(wsUrl: String, timeoutMs: Long = 15_000L) {
        val request = Request.Builder().url(wsUrl).build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isConnected.set(true)
                if (!connectDeferred.isCompleted) {
                    connectDeferred.complete(Unit)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                textChannel.trySend(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                binaryChannel.trySend(bytes.toByteArray())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                markClosed()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!connectDeferred.isCompleted) {
                    connectDeferred.completeExceptionally(IOException("Kết nối WebSocket thất bại: ${t.message}", t))
                }
                textChannel.close(t)
                binaryChannel.close(t)
                markClosed()
            }
        }

        this.webSocket = client.newWebSocket(request, listener)

        withTimeout(timeoutMs) {
            connectDeferred.await()
        }
    }

    /**
     * Gửi tin nhắn văn bản (JSON).
     */
    fun sendText(text: String): Boolean {
        checkConnected()
        return webSocket?.send(text) ?: false
    }

    /**
     * Gửi tin nhắn nhị phân (Binary Frame).
     */
    fun sendBinary(data: ByteArray, offset: Int = 0, byteCount: Int = data.size): Boolean {
        checkConnected()
        val byteString = data.toByteString(offset, byteCount)
        return webSocket?.send(byteString) ?: false
    }

    /**
     * Đọc tin nhắn văn bản tiếp theo với thời gian chờ [timeoutMs].
     */
    suspend fun receiveText(timeoutMs: Long = 30_000L): String {
        return withTimeout(timeoutMs) {
            textChannel.receive()
        }
    }

    /**
     * Đọc tin nhắn nhị phân tiếp theo với thời gian chờ [timeoutMs].
     */
    suspend fun receiveBinary(timeoutMs: Long = 30_000L): ByteArray {
        return withTimeout(timeoutMs) {
            binaryChannel.receive()
        }
    }

    /**
     * Kiểm tra trạng thái kết nối đang mở.
     */
    fun isOpen(): Boolean {
        return isConnected.get() && !isClosed.get()
    }

    private fun checkConnected() {
        if (!isOpen()) {
            throw IOException("Kết nối WebSocket chưa sẵn sàng hoặc đã bị đóng")
        }
    }

    private fun markClosed() {
        isConnected.set(false)
        isClosed.set(true)
        textChannel.close()
        binaryChannel.close()
    }

    fun close(code: Int, reason: String) {
        if (isClosed.compareAndSet(false, true)) {
            isConnected.set(false)
            try {
                webSocket?.close(code, reason)
            } catch (_: Exception) {}
            textChannel.close()
            binaryChannel.close()
        }
    }

    override fun close() {
        close(1000, "Bình thường")
    }

    companion object {
        val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .pingInterval(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .connectTimeout(15, TimeUnit.SECONDS)
                .build()
        }
    }
}
