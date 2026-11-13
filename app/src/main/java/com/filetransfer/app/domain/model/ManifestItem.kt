package com.filetransfer.app.domain.model

/**
 * Phần tử trong danh mục tệp tin/thư mục cần truyền tải (Transfer Manifest) theo SDD Chương 4.
 *
 * @property relativePath Đường dẫn tương đối của tệp trong gói truyền (vd: "subfolder/image.png")
 * @property fileSize Kích thước tệp tính theo byte
 * @property mimeType Định dạng MIME của tệp (mặc định: "* / *")
 * @property sha256 Mã băm SHA-256 kỳ vọng của từng tệp để kiểm tra toàn vẹn
 */
data class ManifestItem(
    val relativePath: String,
    val fileSize: Long,
    val mimeType: String = "*/*",
    val sha256: String = ""
)

