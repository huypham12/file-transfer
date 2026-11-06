package com.teamshare.app.core.storage.saf

import java.io.File
import java.io.RandomAccessFile
import java.util.Arrays

/**
 * Tiêu hủy tệp an toàn chống khôi phục pháp chứng số (Anti-Forensics Wipe).
 * Tuân thủ chuẩn OWASP MASVS-STORAGE:
 * Ghi đè toàn bộ độ dài tệp vật lý bằng byte 0x00 và đồng bộ trực tiếp xuống Flash
 * trước khi xóa liên kết inode trên hệ thống tệp.
 */
object SafeFileDeleter {

    private const val BUFFER_SIZE = 64 * 1024 // 64 KB chunk

    /**
     * Ghi đè toàn bộ nội dung tệp bằng 0x00 và đồng bộ xuống đĩa Flash (không xóa file).
     * @param file Tệp cần ghi đè an toàn
     * @return true nếu ghi đè thành công hoặc file rỗng
     */
    fun wipeFileContent(file: File): Boolean {
        if (!file.exists() || !file.isFile) return false
        return try {
            val length = file.length()
            if (length > 0) {
                val zeroBuffer = ByteArray(BUFFER_SIZE)
                Arrays.fill(zeroBuffer, 0.toByte())

                RandomAccessFile(file, "rws").use { raf ->
                    raf.seek(0)
                    var remaining = length
                    while (remaining > 0) {
                        val toWrite = minOf(remaining, zeroBuffer.size.toLong()).toInt()
                        raf.write(zeroBuffer, 0, toWrite)
                        remaining -= toWrite
                    }
                    raf.fd.sync() // Cưỡng bức flush bộ đệm xuống bộ nhớ flash
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Ghi đè toàn bộ nội dung tệp bằng 0x00 rồi xóa inode.
     * @param file Tệp cần tiêu hủy an toàn
     * @return true nếu xóa thành công hoặc tệp không tồn tại
     */
    fun wipeAndSanitizeFile(file: File): Boolean {
        if (!file.exists()) return true
        if (file.isDirectory) return wipeDirectory(file)

        wipeFileContent(file)
        return try {
            file.delete()
        } catch (e: Exception) {
            // Cố gắng xóa inode dù ghi đè thất bại
            file.delete()
        }
    }

    /**
     * Quét đệ quy và tiêu hủy an toàn toàn bộ tệp trong thư mục.
     */
    fun wipeDirectory(dir: File): Boolean {
        if (!dir.exists()) return true
        var success = true
        dir.listFiles()?.forEach { child ->
            val childSuccess = if (child.isDirectory) {
                wipeDirectory(child)
            } else {
                wipeAndSanitizeFile(child)
            }
            if (!childSuccess) success = false
        }
        return dir.delete() && success
    }
}

