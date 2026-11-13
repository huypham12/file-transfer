package com.filetransfer.app.core.security.sanitizer

import java.io.File

/**
 * Bộ kiểm tra an toàn chống tấn công Path Traversal / Zip Slip khi giải nén tệp tin hoặc thư mục.
 * Tuân thủ chuẩn OWASP MASVS-STORAGE và SDD mục 4.5.3, 9.5.
 *
 * Kiểm tra nghiêm ngặt đường dẫn đích định vị:
 * destinationFile.canonicalPath.startsWith(destinationDir.canonicalPath + File.separator)
 * Ngăn chặn tuyệt đối các kỹ thuật vượt ranh giới Sandbox:
 * - Ký tự "../", "..\"
 * - Ký tự rỗng null byte (\u0000)
 * - Đường dẫn tuyệt đối của hệ điều hành (/etc/..., C:\...)
 */
object ZipSlipValidator {

    /**
     * Thẩm định và trả về đường dẫn File đích an toàn.
     *
     * @param destinationDir Thư mục gốc Sandbox tiếp nhận giải nén
     * @param entryName Tên đường dẫn tương đối của phần tử trong file zip
     * @return File đối tượng tệp hợp lệ nằm bên trong destinationDir
     * @throws SecurityException Nếu phát hiện entry vi phạm nguyên tắc an toàn Zip Slip
     */
    @Throws(SecurityException::class)
    fun validatePath(destinationDir: File, entryName: String): File {
        if (entryName.isBlank()) {
            throw SecurityException("Cảnh báo Zip Slip: Tên entry không được để trống")
        }

        // 1. Kiểm tra ký tự null byte độc hại
        if (entryName.contains('\u0000')) {
            throw SecurityException("Cảnh báo Zip Slip: Tên entry chứa ký tự null byte: $entryName")
        }

        // 2. Chặn các chuỗi leo thang thư mục tường minh
        val normalizedEntry = entryName.replace('\\', '/')
        val pathSegments = normalizedEntry.split('/')
        if (pathSegments.any { it == ".." }) {
            throw SecurityException("Cảnh báo Zip Slip: Entry chứa đoạn đường dẫn leo thang '..': $entryName")
        }

        // 3. Chặn đường dẫn tuyệt đối root
        if (normalizedEntry.startsWith("/") || Regex("^[a-zA-Z]:").containsMatchIn(entryName)) {
            throw SecurityException("Cảnh báo Zip Slip: Entry chỉ định đường dẫn tuyệt đối hệ thống: $entryName")
        }

        // 4. Kiểm tra đường dẫn chính tắc (Canonical Path Verification)
        val canonicalDestDir = destinationDir.canonicalFile
        val targetFile = File(canonicalDestDir, entryName).canonicalFile

        val canonicalDestDirPath = canonicalDestDir.path.let {
            if (it.endsWith(File.separator)) it else it + File.separator
        }

        // Đảm bảo targetFile nằm trọn vẹn bên trong canonicalDestDirPath
        if (!targetFile.path.startsWith(canonicalDestDirPath) && targetFile.path != canonicalDestDir.path) {
            throw SecurityException("Cảnh báo Zip Slip: Entry nằm ngoài thư mục đích an toàn: $entryName")
        }

        return targetFile
    }

    /**
     * Kiểm tra nhanh một entry có an toàn hay không mà không ném exception.
     */
    fun isPathSafe(destinationDir: File, entryName: String): Boolean {
        return try {
            validatePath(destinationDir, entryName)
            true
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            false
        }
    }
}

