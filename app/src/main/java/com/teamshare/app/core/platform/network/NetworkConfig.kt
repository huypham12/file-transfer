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
 * Trung tâm Quản lý Cấu hình Mạng Toàn diện (Network Configuration Center).
 * Gom toàn bộ các hằng số, cổng giao thức, thời gian chờ và cơ chế tự động nhận diện IP/Relay về một nơi duy nhất.
 */
object NetworkConfig {

    // =========================================================================
    // 1. CỔNG KẾT NỐI GIAO THỨC MẶC ĐỊNH
    // =========================================================================
    const val DEFAULT_LAN_PORT = 8889
    const val DEFAULT_RELAY_PORT = 8080
    const val DEFAULT_SYNC_PORT = 8988

    // =========================================================================
    // 2. MÁY CHỦ VÀ ĐỊA CHỈ MẠNG MẶC ĐỊNH
    // =========================================================================
    const val EMULATOR_LOOPBACK_HOST = "10.0.2.2"
    const val DEFAULT_PRODUCTION_RELAY_HOST = "relay.teamshare.app"

    // =========================================================================
    // 3. ENDPOINTS MÁY CHỦ ZERO-KNOWLEDGE RELAY
    // =========================================================================
    const val RELAY_STREAM_ENDPOINT = "v1/stream"
    const val RELAY_HEALTH_ENDPOINT = "health"

    // =========================================================================
    // 4. THỜI GIAN CHỜ VÀ HIỆU LỰC (TIMEOUTS)
    // =========================================================================
    /** Thời gian chờ thử nghiệm LAN siêu tốc (2.5 giây) trước khi tự động chuyển tiếp sang Relay */
    const val LAN_FAST_PROBE_TIMEOUT_MS = 2500

    /** Thời gian chờ socket kết nối chuẩn (15 giây) */
    const val DEFAULT_CONNECT_TIMEOUT_MS = 15000

    /** Thời hạn hiệu lực mã QR mặc định (5 phút) */
    const val DEFAULT_QR_TTL_MS = 300_000L

    // =========================================================================
    // 5. LƯU TRỮ VÀ BIẾN ĐỘNG
    // =========================================================================
    private const val PREFS_NAME = "teamshare_network_config"
    private const val KEY_RELAY_HOST = "relay_host"

    /** Địa chỉ IP/Domain Relay Server do người dùng thiết lập hoặc đã lưu */
    @Volatile
    var configuredRelayHost: String = ""

    /** Địa chỉ IP Wi-Fi thực tế của máy tính chạy server Go (tự động phát hiện khi chạy trên máy ảo) */
    @Volatile
    var detectedHostLanIp: String = ""

    /**
     * Khởi tạo và nạp cấu hình đã lưu từ SharedPreferences khi ứng dụng khởi chạy.
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
     * Lưu địa chỉ IP Relay Server vào SharedPreferences.
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
     * Tự động truy vấn Relay Server tại 10.0.2.2:8080/health để lấy IP Wi-Fi thật của máy tính khi chạy trên máy ảo.
     * Hoàn toàn tự động, ngầm trong suốt, người dùng không cần biết hay gõ IP.
     */
    suspend fun autoDetectRelayHostFromEmulator(): String? = withContext(Dispatchers.IO) {
        if (!isEmulator()) return@withContext null
        try {
            val url = URL("http://$EMULATOR_LOOPBACK_HOST:$DEFAULT_RELAY_PORT/$RELAY_HEALTH_ENDPOINT")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 1500
            conn.readTimeout = 1500
            if (conn.responseCode == 200) {
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(text)
                val detectedIp = json.optString("hostIp", "").trim()
                if (detectedIp.isNotBlank()) {
                    detectedHostLanIp = detectedIp
                    if (configuredRelayHost.isBlank()) {
                        configuredRelayHost = detectedIp
                    }
                    return@withContext detectedIp
                }
            }
        } catch (_: Exception) {}
        return@withContext null
    }

    /**
     * Xác định URL Relay Server chuẩn cho bên gửi kết nối vào.
     */
    fun resolveRelayUrlForSender(endpoint: String = RELAY_STREAM_ENDPOINT): String {
        if (isEmulator()) {
            return "ws://$EMULATOR_LOOPBACK_HOST:$DEFAULT_RELAY_PORT/$endpoint"
        }
        val targetHost = configuredRelayHost.ifBlank { DEFAULT_PRODUCTION_RELAY_HOST }
        val scheme = if (targetHost.startsWith("wss://") || targetHost.endsWith(":443")) "wss" else "ws"
        val hostWithPort = if (targetHost.contains(":")) targetHost else "$targetHost:$DEFAULT_RELAY_PORT"
        val cleanHost = hostWithPort.removePrefix("ws://").removePrefix("wss://")
        return "$scheme://$cleanHost/$endpoint"
    }

    /**
     * Xác định URL Relay Server nhúng vào mã QR để máy nhận quét và kết nối tới.
     */
    fun resolveRelayUrlForQr(endpoint: String = RELAY_STREAM_ENDPOINT): String {
        val host = when {
            configuredRelayHost.isNotBlank() -> configuredRelayHost
            detectedHostLanIp.isNotBlank() -> detectedHostLanIp
            isEmulator() -> EMULATOR_LOOPBACK_HOST
            else -> DEFAULT_PRODUCTION_RELAY_HOST
        }
        val scheme = if (host == DEFAULT_PRODUCTION_RELAY_HOST || host.endsWith(":443")) "wss" else "ws"
        val hostWithPort = if (host.contains(":")) host else "$host:$DEFAULT_RELAY_PORT"
        val cleanHost = hostWithPort.removePrefix("ws://").removePrefix("wss://")
        return "$scheme://$cleanHost/$endpoint"
    }

    /**
     * Chuẩn hóa URL Relay Server cho máy nhận: Nếu máy nhận đang chạy trên máy ảo,
     * tự động chuyển các địa chỉ máy tính host về 10.0.2.2.
     */
    fun resolveRelayUrlForReceiver(rawUrl: String, endpoint: String = RELAY_STREAM_ENDPOINT): String {
        val trimmed = rawUrl.trim()
        if (trimmed.isBlank()) {
            return resolveRelayUrlForSender(endpoint)
        }
        if (isEmulator()) {
            // Trên máy ảo, nếu URL trỏ về localhost hoặc IP Wi-Fi của chính máy host thì đổi sang 10.0.2.2
            if (trimmed.contains("127.0.0.1") || trimmed.contains("localhost") ||
                (detectedHostLanIp.isNotBlank() && trimmed.contains(detectedHostLanIp))
            ) {
                return "ws://$EMULATOR_LOOPBACK_HOST:$DEFAULT_RELAY_PORT/$endpoint"
            }
        }
        return trimmed
    }

    /**
     * Tự động lấy địa chỉ IPv4 nội bộ đang hoạt động (ưu tiên wlan -> eth).
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
     * Nhận diện xem thiết bị có đang chạy trên Android Studio Emulator hay không.
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
}

