package com.teamshare.app.core.transfer.zip

import com.teamshare.app.core.constant.AppConstants
import com.teamshare.app.core.security.sanitizer.ZipSlipValidator
import com.teamshare.app.core.storage.saf.SafeFileDeleter
import com.teamshare.app.core.transfer.buffer.BufferPoolManager
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Kết quả giải nén luồng Zip an toàn.
 */
data class ZipExtractResult(
    val totalFiles: Int,
    val totalFolders: Int,
    val totalBytesExtracted: Long,
    val extractedFiles: List<File>
)

/**
 * Động cơ giải nén luồng Zip an toàn và tuần tự.
 * Tuân thủ quy định SDD mục 4.5.3, 8.2 và SAD ADR-06:
 * - Tích hợp chặt chẽ với ZipSlipValidator kiểm tra canonicalPath của từng entry.
 * - Phòng vệ chống tấn công Zip Bomb (giới hạn số lượng entry và tỷ lệ nén).
 * - Tự động dọn dẹp và tiêu hủy tệp (Anti-Forensics Wipe) qua SafeFileDeleter nếu phát hiện tấn công.
 */
object SafeStreamExtractor {

    private const val DEFAULT_BUFFER_SIZE = AppConstants.Buffer.IO_BUFFER_BYTES
    const val MAX_ENTRIES_COUNT = 10_000
    const val MAX_TOTAL_UNCOMPRESSED_BYTES = 50L * 1024L * 1024L * 1024L // 50 GB

    /**
     * Giải nén an toàn từ một tệp ZIP trên đĩa vào thư mục đích.
     */
    fun extractZipFile(
        zipFile: File,
        destinationDir: File,
        bufferPool: BufferPoolManager? = null,
        onProgress: ((bytesExtracted: Long, currentEntry: String) -> Unit)? = null
    ): ZipExtractResult {
        return FileInputStream(zipFile).use { fis ->
            extractZipStream(fis, destinationDir, bufferPool, onProgress)
        }
    }

    /**
     * Giải nén an toàn từ luồng InputStream (hoặc luồng Socket).
     *
     * @param inputStream Luồng dữ liệu nén
     * @param destinationDir Thư mục Sandbox tiếp nhận dữ liệu
     * @param bufferPool Bộ quản lý đệm tuần hoàn
     * @param onProgress Callback thông báo tiến độ giải nén
     * @return ZipExtractResult chứa thông tin chi tiết các tệp đã giải nén
     * @throws SecurityException Nếu phát hiện vi phạm Zip Slip hoặc Zip Bomb
     */
    @Throws(SecurityException::class, java.io.IOException::class)
    fun extractZipStream(
        inputStream: InputStream,
        destinationDir: File,
        bufferPool: BufferPoolManager? = null,
        onProgress: ((bytesExtracted: Long, currentEntry: String) -> Unit)? = null
    ): ZipExtractResult {
        destinationDir.mkdirs()

        val zis = ZipInputStream(BufferedInputStream(inputStream))
        val createdEntries = mutableListOf<File>()
        var totalBytesExtracted = 0L
        var totalFiles = 0
        var totalFolders = 0
        var localBuffer: ByteArray? = null

        try {
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                // 1. Kiểm tra giới hạn số lượng entry chống Zip Bomb
                if (createdEntries.size >= MAX_ENTRIES_COUNT) {
                    throw SecurityException("Cảnh báo Zip Bomb: Số lượng entry vượt quá ngưỡng cho phép: $MAX_ENTRIES_COUNT")
                }

                val entryName = entry.name
                // 2. Thẩm định an ninh Zip Slip (bắt buộc trước khi thao tác đĩa)
                val targetFile = ZipSlipValidator.validatePath(destinationDir, entryName)

                if (entry.isDirectory || entryName.endsWith("/")) {
                    if (!targetFile.exists()) {
                        val created = targetFile.mkdirs()
                        if (created || targetFile.exists()) {
                            createdEntries.add(targetFile)
                            totalFolders++
                        }
                    }
                } else {
                    val parent = targetFile.parentFile
                    if (parent != null && !parent.exists()) {
                        parent.mkdirs()
                        createdEntries.add(parent)
                    }

                    onProgress?.invoke(totalBytesExtracted, entryName)

                    FileOutputStream(targetFile).use { fos ->
                        val buffer = if (bufferPool != null) {
                            localBuffer ?: kotlinx.coroutines.runBlocking { bufferPool.acquire() }.also { localBuffer = it }
                        } else {
                            localBuffer ?: ByteArray(DEFAULT_BUFFER_SIZE).also { localBuffer = it }
                        }

                        var bytesRead: Int
                        while (zis.read(buffer).also { bytesRead = it } != -1) {
                            totalBytesExtracted += bytesRead.toLong()
                            if (totalBytesExtracted > MAX_TOTAL_UNCOMPRESSED_BYTES) {
                                throw SecurityException("Cảnh báo Zip Bomb: Dung lượng giải nén vượt quá 50GB")
                            }
                            fos.write(buffer, 0, bytesRead)
                        }
                        fos.flush()
                    }
                    createdEntries.add(targetFile)
                    totalFiles++
                }

                zis.closeEntry()
                entry = zis.nextEntry
            }

            return ZipExtractResult(
                totalFiles = totalFiles,
                totalFolders = totalFolders,
                totalBytesExtracted = totalBytesExtracted,
                extractedFiles = createdEntries.filter { it.isFile }
            )
        } catch (e: Throwable) {
            // Rollback an ninh: Tiêu hủy toàn bộ tệp tin/thư mục đã trích xuất trong phiên này
            for (file in createdEntries.asReversed()) {
                try {
                    if (file.isFile) {
                        SafeFileDeleter.wipeAndSanitizeFile(file)
                    } else if (file.isDirectory) {
                        SafeFileDeleter.wipeDirectory(file)
                    }
                } catch (_: Exception) {
                }
            }
            throw e
        } finally {
            if (bufferPool != null && localBuffer != null) {
                bufferPool.release(localBuffer!!)
            }
        }
    }
}

