package com.filetransfer.app.core.transfer.zip

import com.filetransfer.app.core.constant.AppConstants
import com.filetransfer.app.core.transfer.buffer.BufferPoolManager
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Thông tin siêu dữ liệu về luồng đóng gói ZIP xác định.
 *
 * @property totalCompressedBytes Tổng số byte sau khi nén bằng thuật toán Deflate của Zip
 * @property sha256 Mã băm SHA-256 của toàn bộ luồng byte nén sinh ra
 * @property fileCount Tổng số lượng tệp tin có trong thư mục
 * @property folderCount Tổng số lượng thư mục con
 * @property totalUncompressedBytes Tổng kích thước dữ liệu nguyên bản trước khi nén
 */
data class ZipStreamMetadata(
    val totalCompressedBytes: Long,
    val sha256: String,
    val fileCount: Int,
    val folderCount: Int,
    val totalUncompressedBytes: Long
)

/**
 * Động cơ đóng gói cây thư mục thành luồng nhị phân Zip Stream trực tiếp.
 * Tuân thủ quy định SDD mục 4.5.3, 8.2 và SAD ADR-06:
 * - Đóng gói trực tiếp lên luồng Socket mà KHÔNG sinh tệp trung gian trên đĩa flash.
 * - Cơ chế Virtual Dry-Run: Tính toán chính xác 100% kích thước luồng và mã băm SHA-256
 *   với 0 byte ghi đĩa và bộ nhớ RAM cố định <= 64KB.
 * - Sắp xếp phần tử theo thứ tự từ điển xác định để bảo đảm tính nhất quán bit-for-bit.
 */
object ZipStreamPacker {

    private const val DEFAULT_BUFFER_SIZE = AppConstants.Buffer.IO_BUFFER_BYTES

    /**
     * Tính toán siêu dữ liệu (Kích thước và SHA-256) của luồng nén mà không ghi đĩa.
     */
    fun calculateZipMetadata(
        sourceDir: File,
        bufferPool: BufferPoolManager? = null
    ): ZipStreamMetadata {
        val digest = MessageDigest.getInstance("SHA-256")
        val countingStream = CountingOutputStream()
        val digestStream = DigestOutputStream(countingStream, digest)

        var totalUncompressed = 0L
        var fileCount = 0
        var folderCount = 0

        val entries = collectSortedEntries(sourceDir)
        for (item in entries) {
            if (item.file.isDirectory) {
                folderCount++
            } else {
                fileCount++
                totalUncompressed += item.file.length()
            }
        }

        // Chạy lượt nén ảo vào luồng đếm
        packEntriesToStream(
            entries = entries,
            outputStream = digestStream,
            bufferPool = bufferPool,
            closeStream = false
        )
        digestStream.flush()

        val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
        return ZipStreamMetadata(
            totalCompressedBytes = countingStream.bytesWritten,
            sha256 = sha256,
            fileCount = fileCount,
            folderCount = folderCount,
            totalUncompressedBytes = totalUncompressed
        )
    }

    /**
     * Nén trực tiếp cây thư mục vào OutputStream (ví dụ Socket output stream).
     *
     * @param sourceDir Thư mục nguồn cần đóng gói
     * @param outputStream Luồng đích để đẩy dữ liệu nén
     * @param bufferPool Bộ quản lý đệm tuần hoàn (tùy chọn)
     * @param onProgress Callback thông báo tiến độ nén (số byte nén đã ghi, tệp hiện tại)
     * @return Tổng số byte nén đã ghi ra luồng
     */
    fun packDirectoryToStream(
        sourceDir: File,
        outputStream: OutputStream,
        bufferPool: BufferPoolManager? = null,
        onProgress: ((bytesWritten: Long, currentFile: String) -> Unit)? = null
    ): Long {
        val entries = collectSortedEntries(sourceDir)
        val countingStream = CountingOutputStream(outputStream)

        packEntriesToStream(
            entries = entries,
            outputStream = countingStream,
            bufferPool = bufferPool,
            onProgress = onProgress,
            closeStream = false
        )
        countingStream.flush()
        return countingStream.bytesWritten
    }

    private data class PackItem(
        val file: File,
        val relativePath: String
    )

    private fun collectSortedEntries(sourceDir: File): List<PackItem> {
        if (!sourceDir.exists() || !sourceDir.isDirectory) {
            return emptyList()
        }

        val baseCanonical = sourceDir.canonicalFile
        val items = mutableListOf<PackItem>()

        sourceDir.walkTopDown().forEach { file ->
            if (file != sourceDir) {
                val fileCanonical = file.canonicalFile
                var relPath = fileCanonical.toRelativeString(baseCanonical).replace('\\', '/')
                if (file.isDirectory && !relPath.endsWith("/")) {
                    relPath += "/"
                }
                items.add(PackItem(file, relPath))
            }
        }

        // Sắp xếp thứ tự từ điển nhất quán để đảm bảo bit-for-bit reproducibility
        return items.sortedBy { it.relativePath }
    }

    private fun packEntriesToStream(
        entries: List<PackItem>,
        outputStream: OutputStream,
        bufferPool: BufferPoolManager?,
        onProgress: ((bytesWritten: Long, currentFile: String) -> Unit)? = null,
        closeStream: Boolean = false
    ) {
        val zipOut = ZipOutputStream(outputStream)
        // Cố định mức nén chuẩn Deflater.DEFAULT_COMPRESSION
        zipOut.setLevel(java.util.zip.Deflater.DEFAULT_COMPRESSION)

        var localBuffer: ByteArray? = null
        try {
            for (item in entries) {
                val entry = ZipEntry(item.relativePath).apply {
                    // Cố định thời gian sửa đổi chuẩn DOS epoch để đảm bảo SHA-256 đồng nhất
                    time = 0L
                }
                zipOut.putNextEntry(entry)

                if (!item.file.isDirectory) {
                    onProgress?.invoke((outputStream as? CountingOutputStream)?.bytesWritten ?: 0L, item.relativePath)
                    FileInputStream(item.file).use { input ->
                        val buffer = if (bufferPool != null) {
                            localBuffer ?: kotlinx.coroutines.runBlocking { bufferPool.acquire() }.also { localBuffer = it }
                        } else {
                            localBuffer ?: ByteArray(DEFAULT_BUFFER_SIZE).also { localBuffer = it }
                        }

                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            zipOut.write(buffer, 0, bytesRead)
                        }
                    }
                }
                zipOut.closeEntry()
            }
            zipOut.finish()
            zipOut.flush()
        } finally {
            if (bufferPool != null && localBuffer != null) {
                bufferPool.release(localBuffer!!)
            }
            if (closeStream) {
                try { zipOut.close() } catch (_: Exception) {}
            }
        }
    }

    /**
     * Luồng đếm số byte thực tế được ghi qua stream.
     */
    private class CountingOutputStream(private val delegate: OutputStream? = null) : OutputStream() {
        var bytesWritten: Long = 0L
            private set

        override fun write(b: Int) {
            delegate?.write(b)
            bytesWritten++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            delegate?.write(b, off, len)
            bytesWritten += len.toLong()
        }

        override fun flush() {
            delegate?.flush()
        }

        override fun close() {
            delegate?.close()
        }
    }
}

