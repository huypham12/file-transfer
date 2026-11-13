package com.filetransfer.app.core.platform.storage

import java.io.File

/**
 * Kết quả kiểm toán dung lượng lưu trữ đĩa vật lý.
 *
 * @property availableBytes Dung lượng bộ nhớ trống khả dụng tính theo byte
 * @property totalBytes Tổng dung lượng bộ nhớ của phân vùng tính theo byte
 * @property requiredBytes Dung lượng tệp tin/thư mục cần tải về
 * @property safetyMarginBytes Vùng đệm an toàn tối thiểu (mặc định 50MB)
 * @property isSufficient True nếu dung lượng trống >= requiredBytes + safetyMarginBytes
 * @property shortfallBytes Số byte còn thiếu để đủ điều kiện tải (0 nếu đủ)
 */
data class StorageAuditResult(
    val availableBytes: Long,
    val totalBytes: Long,
    val requiredBytes: Long,
    val safetyMarginBytes: Long,
    val isSufficient: Boolean,
    val shortfallBytes: Long = maxOf(0L, (requiredBytes + safetyMarginBytes) - availableBytes)
)

/**
 * Giao diện kiểm toán dung lượng lưu trữ đĩa vật lý.
 * Tuân thủ yêu cầu FR-RCV-02 và SDD mục 4.6.
 */
interface IStorageAuditor {
    /**
     * Lấy dung lượng bộ nhớ trống thực tế.
     */
    fun getAvailableBytes(targetDir: File? = null): Long

    /**
     * Lấy tổng dung lượng bộ nhớ của phân vùng lưu trữ.
     */
    fun getTotalBytes(targetDir: File? = null): Long

    /**
     * Kiểm tra nhanh thiết bị có đủ dung lượng lưu trữ kèm vùng đệm an toàn hay không.
     */
    fun hasSufficientSpace(requiredBytes: Long, targetDir: File? = null): Boolean

    /**
     * Thực hiện kiểm toán chi tiết dung lượng lưu trữ.
     */
    fun auditStorage(requiredBytes: Long, targetDir: File? = null): StorageAuditResult
}

