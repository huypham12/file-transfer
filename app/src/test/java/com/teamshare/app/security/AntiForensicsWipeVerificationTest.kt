package com.teamshare.app.security

import com.teamshare.app.core.storage.saf.SafeFileDeleter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets

/**
 * Kiểm chứng Thuật toán Tiêu hủy Tệp An toàn Chống Pháp chứng số (Anti-Forensics Wipe Verification Test).
 * Thẩm định yêu cầu FR-DOC-04 và NFR-SEC: Khi người dùng yêu cầu xóa tài liệu hoặc khi phiên truyền bị thu hồi,
 * toàn bộ byte dữ liệu phải được ghi đè bằng giá trị 0x00 trước khi tệp bị gỡ khỏi hệ thống tệp.
 */
class AntiForensicsWipeVerificationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testFileOverwrittenWithZeros_BeforeUnlink() {
        val testFile = tempFolder.newFile("top_secret_intel.pdf")
        val secretContent = "DỮ LIỆU TỐI MẬT QUỐC GIA - KHÔNG ĐƯỢC PHÉP KHÔI PHỤC".toByteArray(StandardCharsets.UTF_8)

        FileOutputStream(testFile).use { fos ->
            repeat(100) {
                fos.write(secretContent)
            }
        }

        val originalLength = testFile.length()
        assertTrue("Tệp mẫu phải có dữ liệu (> 0 bytes)", originalLength > 0)

        // 1. Gọi trực tiếp hàm sản xuất SafeFileDeleter.wipeFileContent
        val wipeSuccess = SafeFileDeleter.wipeFileContent(testFile)
        assertTrue("Hàm wipeFileContent của SafeFileDeleter phải trả về true", wipeSuccess)

        // Đọc quét nhị phân để kiểm chứng 100% byte là 0x00
        FileInputStream(testFile).use { fis ->
            val readBuf = ByteArray(1024)
            var bytesRead: Int
            while (fis.read(readBuf).also { bytesRead = it } != -1) {
                for (i in 0 until bytesRead) {
                    assertEquals("Mọi byte trong tệp bắt buộc phải là 0x00 (Byte thứ $i)", 0.toByte(), readBuf[i])
                }
            }
        }

        // 2. Gọi SafeFileDeleter.wipeAndSanitizeFile để hoàn tất việc xóa an toàn
        SafeFileDeleter.wipeAndSanitizeFile(testFile)

        // Kiểm chứng tệp đã bị xóa khỏi hệ thống tệp
        assertFalse("Tệp phải bị xóa hoàn toàn khỏi đĩa cứng sau khi sanitize", testFile.exists())
    }

    @Test
    fun testSafeFileDeleter_HandlesNonExistentFileGracefully() {
        val ghostFile = File(tempFolder.root, "ghost_file.bin")
        assertFalse(ghostFile.exists())

        // Không được văng ngoại lệ khi xóa tệp không tồn tại
        SafeFileDeleter.wipeAndSanitizeFile(ghostFile)
    }
}

