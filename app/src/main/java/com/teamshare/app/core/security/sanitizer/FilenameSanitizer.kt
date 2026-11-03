package com.teamshare.app.core.security.sanitizer

/**
 * Bộ lọc an toàn tên tệp tin (Filename Sanitizer).
 * Tuân thủ đặc tả SDD mục 9.5:
 * 1. Triệt tiêu 100% tấn công leo thang thư mục (Path Traversal - "../").
 * 2. Loại bỏ các ký tự điều khiển và ký tự cấm của hệ thống tệp Windows/Linux/Android.
 * 3. Giới hạn độ dài tối đa 255 ký tự (chuẩn Linux ext4 / Android Scoped Storage).
 */
object FilenameSanitizer {

    private const val MAX_FILENAME_LENGTH = 255
    private val ILLEGAL_CHARACTERS = Regex("[/\\\\:*?\"<>|\\x00-\\x1F]")

    fun sanitize(rawFileName: String): String {
        // 1. Loại bỏ các ký tự điều khiển và ký tự đặc biệt nguy hiểm
        var cleaned = rawFileName.replace(ILLEGAL_CHARACTERS, "_").trim()

        // 2. Triệt tiêu hoàn toàn Path Traversal (chuỗi 2 hoặc nhiều dấu chấm liên tiếp)
        cleaned = cleaned.replace(Regex("\\.{2,}"), ".")

        // 3. Giới hạn độ dài tối đa 255 ký tự
        if (cleaned.length > MAX_FILENAME_LENGTH) {
            val dotIndex = cleaned.lastIndexOf('.')
            if (dotIndex != -1 && dotIndex > cleaned.length - 20) {
                val extension = cleaned.substring(dotIndex)
                val baseName = cleaned.substring(0, dotIndex)
                val allowedBaseLength = MAX_FILENAME_LENGTH - extension.length
                cleaned = baseName.take(allowedBaseLength) + extension
            } else {
                cleaned = cleaned.take(MAX_FILENAME_LENGTH)
            }
        }

        // 4. Nếu tên tệp rỗng hoặc chỉ chứa dấu chấm/gạch dưới, gán tên mặc định an toàn
        val effectiveText = cleaned.replace("_", "").replace(".", "").trim()
        if (cleaned.isBlank() || effectiveText.isEmpty()) {
            cleaned = "document_${System.currentTimeMillis()}"
        }

        return cleaned
    }
}

