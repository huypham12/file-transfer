package com.teamshare.app.core.platform.network

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL

/**
 * Trợ thủ xác định địa chỉ mạng nội bộ và môi trường thực thi (Thiết bị thật vs Máy ảo Android Emulator).
 */
object NetworkAddressHelper {

    const val DEFAULT_SYNC_PORT = 8988
    const val DEFAULT_RELAY_PORT = 8080
    private const val PREFS_NAME = "teamshare_network_config"
    private const val KEY_RELAY_HOST = "relay_host"

    /**
     * Địa chỉ IP của máy tính chạy Go Relay Server (tự động nạp từ SharedPreferences hoặc tự phát hiện, không fix cứng).
     */
    @Volatile
    var configuredRelayHost: String = ""

    /**
     * Tự động truy vấn Relay Server tại 10.0.2.2:8080/health để lấy IP Wi-Fi thật của máy tính khi chạy trên máy ảo.
     * Hoàn toàn tự động, người dùng không cần biết hay gõ IP.
     */
    suspend fun autoDetectRelayHostFromEmulator(): String? = withContext(Dispatchers.IO) {
        if (!isEmulator()) return@withContext null
        try {
            val url = URL("http://10.0.2.2:$DEFAULT_RELAY_PORT/health")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 1500
            conn.readTimeout = 1500
            if (conn.responseCode == 200) {
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(text)
                val detectedIp = json.optString("hostIp", "").trim()
                if (detectedIp.isNotBlank()) {
                    configuredRelayHost = detectedIp
                    return@withContext detectedIp
                }
            }
        } catch (_: Exception) {}
        return@withContext null
    }

    /**
     * Nạp cấu hình mạng đã lưu từ SharedPreferences khi ứng dụng khởi chạy.
     */
    fun init(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val saved = prefs.getString(KEY_RELAY_HOST, "")?.trim() ?: ""
            if (saved.isNotBlank()) {
                configuredRelayHost = saved
            }
        } catch (_: Exception) {}
    }

    /**
     * Lưu địa chỉ IP Relay Server vào SharedPreferences để ghi nhớ qua các lần mở app.
     */
    fun saveConfiguredRelayHost(context: Context, host: String) {
        val cleanHost = host.trim()
        configuredRelayHost = cleanHost
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_RELAY_HOST, cleanHost).apply()
        } catch (_: Exception) {}
    }

    /**
     * Lấy địa chỉ IPv4 nội bộ đang hoạt động (ưu tiên card Wi-Fi wlan hoặc Ethernet eth).
     */
    fun getLocalIpv4Address(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            val candidates = mutableListOf<Pair<String, String>>()

            while (interfaces.hasMoreElements()) {
                val netIf = interfaces.nextElement()
                if (!netIf.isUp || netIf.isLoopback) continue

                val addresses = netIf.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val hostAddress = addr.hostAddress ?: continue
                        candidates.add(netIf.name to hostAddress)
                    }
                }
            }

            // Ưu tiên wlan (Wi-Fi), tiếp theo eth, cuối cùng là bất kỳ interface nào
            val wifiCandidate = candidates.firstOrNull { it.first.contains("wlan", ignoreCase = true) }
            if (wifiCandidate != null) return wifiCandidate.second

            val ethCandidate = candidates.firstOrNull { it.first.contains("eth", ignoreCase = true) }
            if (ethCandidate != null) return ethCandidate.second

            return candidates.firstOrNull()?.second
        } catch (_: Exception) {
            return null
        }
    }

    /**
     * Nhận diện xem ứng dụng có đang chạy trên Android Studio Emulator hay không.
     */
    fun isEmulator(): Boolean {
        return try {
            val fingerprint = Build.FINGERPRINT ?: ""
            val model = Build.MODEL ?: ""
            val hardware = Build.HARDWARE ?: ""
            val brand = Build.BRAND ?: ""
            val device = Build.DEVICE ?: ""
            val product = Build.PRODUCT ?: ""

            fingerprint.startsWith("generic") ||
                    fingerprint.startsWith("unknown") ||
                    model.contains("google_sdk", ignoreCase = true) ||
                    model.contains("Emulator", ignoreCase = true) ||
                    model.contains("Android SDK built for", ignoreCase = true) ||
                    hardware.contains("goldfish", ignoreCase = true) ||
                    hardware.contains("ranchu", ignoreCase = true) ||
                    brand.startsWith("generic") && device.startsWith("generic") ||
                    product.contains("sdk_gphone", ignoreCase = true)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Xác định URL của Zero-Knowledge Relay Server tùy theo môi trường.
     * @param customHost Địa chỉ máy chủ tùy chỉnh
     * @param endpoint Điểm cuối API ("v1/sync" hoặc "v1/stream")
     */
    fun getDefaultRelayUrl(customHost: String? = null, endpoint: String = "v1/sync"): String {
        val trimmed = customHost?.trim()?.takeIf { it.isNotBlank() } ?: configuredRelayHost.trim()
        if (trimmed.isNotBlank()) {
            val hostWithPort = if (trimmed.contains(":")) trimmed else "$trimmed:$DEFAULT_RELAY_PORT"
            val cleanHost = hostWithPort.removePrefix("ws://").removePrefix("wss://")
            return "ws://$cleanHost/$endpoint"
        }

        if (isEmulator()) {
            return "ws://10.0.2.2:$DEFAULT_RELAY_PORT/$endpoint"
        }

        val localIp = getLocalIpv4Address()
        return if (!localIp.isNullOrBlank()) {
            "ws://$localIp:$DEFAULT_RELAY_PORT/$endpoint"
        } else {
            "ws://127.0.0.1:$DEFAULT_RELAY_PORT/$endpoint"
        }
    }
}
