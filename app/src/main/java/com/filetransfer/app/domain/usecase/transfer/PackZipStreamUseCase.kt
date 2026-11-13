package com.filetransfer.app.domain.usecase.transfer

import com.filetransfer.app.core.transfer.buffer.BufferPoolManager
import com.filetransfer.app.core.transfer.zip.ZipStreamMetadata
import com.filetransfer.app.core.transfer.zip.ZipStreamPacker
import java.io.File
import java.io.OutputStream

/**
 * FR-SND-05: Đóng gói luồng Zip Stream cây thư mục.
 * Tuân thủ SDD Bảng 12.1 và Mục 4.5.3.
 */
class PackZipStreamUseCase(
    private val bufferPool: BufferPoolManager? = null
) {
    /**
     * Tính toán siêu dữ liệu (Kích thước và SHA-256) của cây thư mục trước khi gửi.
     */
    fun calculateMetadata(sourceDir: File): ZipStreamMetadata {
        return ZipStreamPacker.calculateZipMetadata(sourceDir, bufferPool)
    }

    /**
     * Nén trực tiếp cây thư mục vào luồng đích.
     */
    fun packFolderStream(
        sourceDir: File,
        out: OutputStream,
        onProgress: ((bytesWritten: Long, currentFile: String) -> Unit)? = null
    ): Long {
        return ZipStreamPacker.packDirectoryToStream(sourceDir, out, bufferPool, onProgress)
    }

    operator fun invoke(sourceDir: File): ZipStreamMetadata {
        return calculateMetadata(sourceDir)
    }
}

