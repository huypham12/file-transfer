package com.teamshare.app.core.platform.network

import android.content.Context

/**
 * Trợ thủ xác định địa chỉ mạng nội bộ và môi trường thực thi.
 * Chuyển giao toàn bộ xử lý về [NetworkConfig] để tập trung quản lý.
 */
object NetworkAddressHelper {

    const val DEFAULT_SYNC_PORT = NetworkConfig.DEFAULT_SYNC_PORT
    val DEFAULT_RELAY_PORT = NetworkConfig.DEFAULT_RELAY_PORT
    val DEFAULT_PRODUCTION_RELAY_HOST = NetworkConfig.DEFAULT_PRODUCTION_RELAY_HOST

    var configuredRelayHost: String
        get() = NetworkConfig.configuredRelayHost
        set(value) {
            NetworkConfig.configuredRelayHost = value
        }

    suspend fun autoDetectRelayHostFromEmulator(): String? {
        return NetworkConfig.autoDetectRelayHostFromEmulator()
    }

    fun init(context: Context) {
        NetworkConfig.init(context)
    }

    fun saveConfiguredRelayHost(context: Context, host: String) {
        NetworkConfig.saveConfiguredRelayHost(context, host)
    }

    fun getLocalIpv4Address(): String? {
        return NetworkConfig.getLocalIpv4Address()
    }

    fun isEmulator(): Boolean {
        return NetworkConfig.isEmulator()
    }

    fun getDefaultRelayUrl(customHost: String? = null, endpoint: String = "v1/sync"): String {
        val trimmed = customHost?.trim()?.takeIf { it.isNotBlank() } ?: configuredRelayHost.trim()
        if (trimmed.isNotBlank()) {
            val scheme = if (trimmed.startsWith("wss://") || trimmed.endsWith(":443")) "wss" else "ws"
            val hostWithPort = if (trimmed.contains(":")) trimmed else "$trimmed:$DEFAULT_RELAY_PORT"
            val cleanHost = hostWithPort.removePrefix("ws://").removePrefix("wss://")
            return "$scheme://$cleanHost/$endpoint"
        }

        if (isEmulator()) {
            return "ws://${NetworkConfig.EMULATOR_LOOPBACK_HOST}:$DEFAULT_RELAY_PORT/$endpoint"
        }

        return "wss://$DEFAULT_PRODUCTION_RELAY_HOST/$endpoint"
    }
}
