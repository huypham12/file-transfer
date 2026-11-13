package com.filetransfer.app.core.platform.share

import android.content.Intent
import android.net.Uri

/**
 * Cấu trúc dữ liệu chứa danh sách Uri và thông tin tệp được trích xuất từ Android Share Intent.
 */
data class ParsedShareData(
    val uris: List<Uri>,
    val mimeType: String?,
    val action: String
)

/**
 * Nguồn dữ liệu chia sẻ trừu tượng hóa để hỗ trợ Clean Architecture và kiểm thử độc lập trên JVM.
 */
interface IShareIntentSource {
    val action: String?
    val mimeType: String?
    fun getSingleStreamUri(): Uri?
    fun getMultipleStreamUris(): List<Uri>?
}

/**
 * Nguồn dữ liệu chuyển tiếp từ android.content.Intent thật của hệ điều hành.
 */
class AndroidIntentSource(private val intent: Intent?) : IShareIntentSource {
    override val action: String? get() = intent?.action
    override val mimeType: String? get() = intent?.type

    @Suppress("DEPRECATION")
    override fun getSingleStreamUri(): Uri? {
        if (intent == null) return null
        val stream = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        if (stream != null) return stream
        if (intent.data != null) return intent.data
        if (intent.clipData != null && (intent.clipData?.itemCount ?: 0) > 0) {
            return intent.clipData?.getItemAt(0)?.uri
        }
        return null
    }

    @Suppress("DEPRECATION")
    override fun getMultipleStreamUris(): List<Uri>? {
        if (intent == null) return null
        val list = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
        if (!list.isNullOrEmpty()) return list.filterNotNull()
        if (intent.clipData != null) {
            val count = intent.clipData?.itemCount ?: 0
            val extracted = mutableListOf<Uri>()
            for (i in 0 until count) {
                intent.clipData?.getItemAt(i)?.uri?.let { extracted.add(it) }
            }
            if (extracted.isNotEmpty()) return extracted
        }
        return null
    }
}

/**
 * Tiện ích phân tích và trích xuất danh sách Uri từ Intent chia sẻ hệ thống (ACTION_SEND / ACTION_SEND_MULTIPLE).
 * Tuân thủ SRS FR-MOB-01 (UC-16).
 */
object ShareIntentParser {

    /**
     * Phân tích từ nguồn IShareIntentSource trừu tượng (dùng cho cả app thật lẫn unit test).
     */
    fun parse(source: IShareIntentSource): ParsedShareData {
        val action = source.action ?: ""
        val mimeType = source.mimeType

        val uris = when (action) {
            Intent.ACTION_SEND -> {
                val uri = source.getSingleStreamUri()
                if (uri != null) listOf(uri) else emptyList()
            }

            Intent.ACTION_SEND_MULTIPLE -> {
                source.getMultipleStreamUris() ?: emptyList()
            }

            else -> emptyList()
        }

        // Lọc các URI hợp lệ (chỉ chấp nhận content:// hoặc file://)
        val validUris = uris.filter { uri ->
            try {
                isValidScheme(uri.scheme)
            } catch (e: Exception) {
                false
            }
        }

        return ParsedShareData(validUris, mimeType, action)
    }

    /**
     * Phân tích trực tiếp từ Android Intent.
     */
    fun parse(intent: Intent?): ParsedShareData {
        if (intent == null) {
            return ParsedShareData(emptyList(), null, "")
        }
        return parse(AndroidIntentSource(intent))
    }

    /**
     * Kiểm tra tính hợp lệ của scheme URI (chỉ cho phép content và file).
     */
    fun isValidScheme(scheme: String?): Boolean {
        if (scheme == null) return false
        return scheme.equals("content", ignoreCase = true) || scheme.equals("file", ignoreCase = true)
    }

    /**
     * Kiểm tra nhanh xem Intent có mang dữ liệu chia sẻ tệp hợp lệ hay không.
     */
    fun hasValidShareData(intent: Intent?): Boolean {
        return parse(intent).uris.isNotEmpty()
    }

    fun hasValidShareData(source: IShareIntentSource): Boolean {
        return parse(source).uris.isNotEmpty()
    }
}
