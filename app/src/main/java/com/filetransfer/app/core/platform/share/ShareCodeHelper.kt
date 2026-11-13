package com.filetransfer.app.core.platform.share

import com.filetransfer.app.domain.model.SessionQrPayload
import java.util.regex.Pattern

/**
 * Lớp tiện ích hỗ trợ định dạng, chia sẻ và trích xuất mã nhận tệp an toàn (OOB Share Code).
 * Hỗ trợ trích xuất chính xác payload từ tin nhắn văn bản hỗn hợp (Zalo, Telegram, Messenger).
 */
object ShareCodeHelper {
    // Regex nhận diện chuỗi URI filetransfer://drop?payload=<base64>
    private val URI_PATTERN = Pattern.compile("filetransfer://drop\\?payload=[A-Za-z0-9_\\-]+={0,2}")
    
    // Regex nhận diện chuỗi JSON payload {"sid":...} hoặc {"sessionId":...}
    private val JSON_PATTERN = Pattern.compile("\\{[^\\{\\}]*(?:\"sid\"|\"sessionId\")[^\\{\\}]*\\}")

    /**
     * Trích xuất chuỗi payload hợp lệ từ văn bản người dùng dán hoặc truyền vào.
     * Tự động loại bỏ các văn bản thừa bao quanh từ tin nhắn chat mạng xã hội.
     *
     * @param rawText Văn bản thô (có thể chứa lời nhắn, khoảng trắng, xuống dòng)
     * @return Chuỗi payload sạch (URI schema hoặc JSON)
     */
    fun extractPayload(rawText: String): String {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) return ""

        // 1. Kiểm tra nếu có chứa filetransfer:// URI
        val uriMatcher = URI_PATTERN.matcher(trimmed)
        if (uriMatcher.find()) {
            return uriMatcher.group(0) ?: trimmed
        }

        // 2. Kiểm tra nếu có chứa JSON payload
        val jsonMatcher = JSON_PATTERN.matcher(trimmed)
        if (jsonMatcher.find()) {
            return jsonMatcher.group(0) ?: trimmed
        }

        return trimmed
    }

    /**
     * Định dạng tin nhắn văn bản thân thiện để chia sẻ qua Android Sharesheet (Zalo, Telegram, v.v.).
     *
     * @param payload Dữ liệu phiên truyền tệp
     * @return Chuỗi thông điệp hoàn chỉnh
     */
    fun formatShareMessage(payload: SessionQrPayload): String {
        val countText = if (payload.itemCount > 1) "${payload.itemCount} tệp" else "1 tệp"
        val sender = payload.senderDeviceModel.ifBlank { "Thiết bị filetransfer" }
        val ttlMinutes = ((payload.expiresAt - System.currentTimeMillis() + 59_999) / 60_000).coerceAtLeast(1)
        return """
            filetransfer - Mã nhận tệp an toàn
            Người gửi: $sender ($countText)
            Mã nhận tệp:
            ${payload.toQrString()}
            (Mã có hiệu lực trong $ttlMinutes phút)
        """.trimIndent()
    }
}

