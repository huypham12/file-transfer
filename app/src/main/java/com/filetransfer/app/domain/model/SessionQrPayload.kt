package com.filetransfer.app.domain.model

import com.filetransfer.app.core.constant.AppConstants
import com.filetransfer.app.core.platform.share.ShareCodeHelper
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Cấu trúc dữ liệu mã QR động phục vụ ghép đôi ngoài băng (Out-of-Band Dynamic QR).
 * Tuân thủ SAD ADR-04 và SDD mục 8.1 (FR-SND-03 & FR-RCV-01).
 *
 * @property sessionId Định danh phiên truyền (UUID)
 * @property sessionToken Mã bí mật ngoài băng dùng để xác thực kết nối
 * @property senderIp Địa chỉ IPv4 mạng nội bộ của máy phát (vd: 192.168.1.15)
 * @property senderPort Cổng ServerSocket lắng nghe (mặc định 8889)
 * @property channel Kênh truyền tải (DIRECT_LAN hoặc RELAY_E2EE)
 * @property transferType Loại tệp (SINGLE_FILE, BATCH_FILES, FOLDER_ZIP)
 * @property totalBytes Tổng dung lượng truyền tải tính theo byte
 * @property itemCount Số lượng phần tử tệp tin
 * @property senderDeviceModel Tên phần cứng máy phát (vd: Xiaomi Redmi Turbo 4 Pro)
 * @property relayUrl URL máy chủ Go Relay nếu dùng kênh chuyển tiếp
 * @property streamId Định danh luồng Relay (streamId)
 * @property expiresAt Dấu thời gian hết hạn hiệu lực (TTL mặc định 5 phút)
 * @property signature Chữ ký số từ Keystore TEE (tùy chọn)
 */
data class SessionQrPayload(
    val sessionId: String,
    val sessionToken: String,
    val senderIp: String,
    val senderPort: Int = AppConstants.Network.DEFAULT_LAN_PORT,
    val channel: TransferChannel = TransferChannel.DIRECT_LAN,
    val transferType: TransferType = TransferType.SINGLE_FILE,
    val totalBytes: Long = 0L,
    val itemCount: Int = 1,
    val senderDeviceModel: String = "",
    val relayUrl: String = "",
    val streamId: String = "",
    val fileName: String = "",
    val expiresAt: Long = System.currentTimeMillis() + DEFAULT_TTL_MS,
    val signature: String = ""
) {
    companion object {
        const val DEFAULT_TTL_MS = AppConstants.Timeout.QR_TTL_MS
        const val URI_SCHEME_PREFIX = "filetransfer://drop?payload="

        /**
         * Phân giải chuỗi văn bản QR nhận từ Camera (hỗ trợ cả JSON thuần và URI prefix).
         */
        @Throws(IllegalArgumentException::class)
        fun parse(qrRawText: String): SessionQrPayload {
            val extracted = ShareCodeHelper.extractPayload(qrRawText)
            val trimmed = extracted.trim()
            if (trimmed.isEmpty()) {
                throw IllegalArgumentException("Mã chia sẻ không có nội dung")
            }

            val jsonString = if (trimmed.startsWith(URI_SCHEME_PREFIX)) {
                val base64Payload = trimmed.substring(URI_SCHEME_PREFIX.length)
                try {
                    val decodedBytes = Base64.getUrlDecoder().decode(base64Payload)
                    String(decodedBytes, StandardCharsets.UTF_8)
                } catch (e: Exception) {
                    throw IllegalArgumentException("Dữ liệu mã QR mã hóa base64 không hợp lệ: ${e.message}")
                }
            } else if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                trimmed
            } else {
                throw IllegalArgumentException("Định dạng mã QR không được hỗ trợ: $trimmed")
            }

            return fromJson(jsonString)
        }

        fun fromJson(jsonStr: String): SessionQrPayload {
            val json = JSONObject(jsonStr)

            val sessionId = json.optString("sid", "")
                .takeIf { it.isNotBlank() } ?: json.optString("sessionId", "")
            val token = json.optString("tok", "")
                .takeIf { it.isNotBlank() } ?: json.optString("sessionToken", "")
            val ip = json.optString("ip", "")
                .takeIf { it.isNotBlank() } ?: json.optString("senderIp", "")
            val port = if (json.has("port")) json.optInt("port", AppConstants.Network.DEFAULT_LAN_PORT) else json.optInt("senderPort", AppConstants.Network.DEFAULT_LAN_PORT)

            if (sessionId.isBlank() || token.isBlank()) {
                throw IllegalArgumentException("Mã QR thiếu trường bắt buộc: sid/sessionId hoặc tok/sessionToken")
            }

            val channelStr = json.optString("ch", json.optString("channel", "DIRECT_LAN"))
            val channel = try {
                TransferChannel.valueOf(channelStr)
            } catch (_: Exception) {
                TransferChannel.DIRECT_LAN
            }

            val typeStr = json.optString("type", json.optString("transferType", "SINGLE_FILE"))
            val transferType = try {
                TransferType.valueOf(typeStr)
            } catch (_: Exception) {
                TransferType.SINGLE_FILE
            }

            val totalBytes = if (json.has("sz")) json.optLong("sz", 0L) else json.optLong("totalBytes", 0L)
            val itemCount = if (json.has("cnt")) json.optInt("cnt", 1) else json.optInt("itemCount", 1)
            val model = json.optString("model", json.optString("senderDeviceModel", ""))
            val relayUrl = json.optString("relay", json.optString("relayUrl", ""))
            val streamId = json.optString("streamId", "")
            val fileName = json.optString("fn", json.optString("fileName", ""))
            val exp = if (json.has("exp")) json.optLong("exp", 0L) else json.optLong("expiresAt", 0L)
            val sig = json.optString("sig", json.optString("signature", ""))

            return SessionQrPayload(
                sessionId = sessionId,
                sessionToken = token,
                senderIp = ip,
                senderPort = port,
                channel = channel,
                transferType = transferType,
                totalBytes = totalBytes,
                itemCount = itemCount,
                senderDeviceModel = model,
                relayUrl = relayUrl,
                streamId = streamId,
                fileName = fileName,
                expiresAt = if (exp > 0L) exp else System.currentTimeMillis() + DEFAULT_TTL_MS,
                signature = sig
            )
        }
    }

    /**
     * Kiểm tra thời hạn hiệu lực của mã QR (chống Replay Attack).
     */
    val isExpired: Boolean get() = System.currentTimeMillis() > expiresAt

    /**
     * Tuần tự hóa đối tượng thành chuỗi JSON nhỏ gọn.
     */
    fun toJson(): String {
        return JSONObject().apply {
            put("sid", sessionId)
            put("tok", sessionToken)
            put("ip", senderIp)
            put("port", senderPort)
            put("ch", channel.name)
            put("type", transferType.name)
            put("sz", totalBytes)
            put("cnt", itemCount)
            put("model", senderDeviceModel)
            if (relayUrl.isNotBlank()) put("relay", relayUrl)
            if (streamId.isNotBlank()) put("streamId", streamId)
            if (fileName.isNotBlank()) put("fn", fileName)
            put("exp", expiresAt)
            if (signature.isNotBlank()) put("sig", signature)
        }.toString()
    }

    /**
     * Định dạng chuỗi mã QR có tiền tố URI schema chuẩn.
     */
    fun toQrString(): String {
        val jsonBytes = toJson().toByteArray(StandardCharsets.UTF_8)
        val base64 = Base64.getUrlEncoder().withoutPadding().encodeToString(jsonBytes)
        return "$URI_SCHEME_PREFIX$base64"
    }
}

