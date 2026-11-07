package com.teamshare.app.core.network.tls

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import javax.net.ServerSocketFactory
import javax.net.SocketFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/**
 * Quản lý cấp phát cổng TCP ngẫu nhiên trong dải Private Port (49152..65535) theo IANA và SDD Chương 6.2,
 * đồng thời cung cấp các Socket/ServerSocket hỗ trợ kênh truyền bảo mật TLS 1.3 hoặc kênh TCP Socket LAN.
 */
class TlsSocketFactory(
    private val sslContext: SSLContext? = null
) {
    companion object {
        const val DYNAMIC_PORT_START = 49152
        const val DYNAMIC_PORT_END = 65535
        private val random = SecureRandom()

        /**
         * Tìm một cổng TCP còn trống trong dải Private 49152..65535.
         */
        fun findAvailablePort(
            rangeStart: Int = DYNAMIC_PORT_START,
            rangeEnd: Int = DYNAMIC_PORT_END,
            maxAttempts: Int = 100
        ): Int {
            require(rangeStart in 1024..65535 && rangeEnd in rangeStart..65535) {
                "Dải cổng không hợp lệ: $rangeStart..$rangeEnd"
            }

            for (i in 0 until maxAttempts) {
                val candidatePort = rangeStart + random.nextInt(rangeEnd - rangeStart + 1)
                try {
                    ServerSocket(candidatePort).use { socket ->
                        socket.reuseAddress = true
                        return candidatePort
                    }
                } catch (_: IOException) {
                    // Cổng đang bận, thử cổng khác
                }
            }

            // Fallback mở ServerSocket(0) để OS tự gán một ephemeral port
            ServerSocket(0).use { socket ->
                return socket.localPort
            }
        }
    }

    /**
     * Tạo ServerSocket lắng nghe trên cổng chỉ định (hoặc tự động cấp phát trong dải private nếu port = 0).
     * @param port Cổng TCP (mặc định 0 để tìm cổng ngẫu nhiên trong dải private)
     * @param backlog Số lượng kết nối chờ tối đa
     * @param useTls true để bật mã hóa TLS 1.3, false cho kết nối TCP thuần
     */
    fun createServerSocket(
        port: Int = 0,
        backlog: Int = 50,
        useTls: Boolean = false
    ): ServerSocket {
        if (useTls) {
            checkNotNull(sslContext) { "Yêu cầu useTls = true nhưng SSLContext chưa được cấu hình cho TlsSocketFactory" }
        }
        val bindPort = if (port == 0) findAvailablePort() else port

        val serverSocket = if (useTls && sslContext != null) {
            val sslServerSocket = sslContext.serverSocketFactory.createServerSocket(bindPort, backlog) as SSLServerSocket
            // Cấu hình giao thức TLS 1.3 nếu runtime hỗ trợ
            val supportedProtocols = sslServerSocket.supportedProtocols.toList()
            if (supportedProtocols.contains("TLSv1.3")) {
                sslServerSocket.enabledProtocols = arrayOf("TLSv1.3")
            }
            sslServerSocket
        } else {
            ServerSocketFactory.getDefault().createServerSocket(bindPort, backlog)
        }
        try {
            serverSocket.reuseAddress = true
            serverSocket.receiveBufferSize = 512 * 1024
        } catch (_: Exception) {}
        return serverSocket
    }

    /**
     * Tạo kết nối Client Socket tới địa chỉ máy chủ IP và Port.
     */
    fun createSocket(
        host: String,
        port: Int,
        useTls: Boolean = false,
        timeoutMs: Int = 15000
    ): Socket {
        if (useTls) {
            checkNotNull(sslContext) { "Yêu cầu useTls = true nhưng SSLContext chưa được cấu hình cho TlsSocketFactory" }
        }
        val socket = if (useTls && sslContext != null) {
            val sslSocket = sslContext.socketFactory.createSocket(host, port) as SSLSocket
            val supportedProtocols = sslSocket.supportedProtocols.toList()
            if (supportedProtocols.contains("TLSv1.3")) {
                sslSocket.enabledProtocols = arrayOf("TLSv1.3")
            }
            sslSocket
        } else {
            SocketFactory.getDefault().createSocket(host, port)
        }
        socket.soTimeout = timeoutMs
        socket.tcpNoDelay = true // Tắt thuật toán Nagle để giảm độ trễ đóng gói frame
        try {
            socket.sendBufferSize = 512 * 1024
            socket.receiveBufferSize = 512 * 1024
        } catch (_: Exception) {}
        return socket
    }
}

