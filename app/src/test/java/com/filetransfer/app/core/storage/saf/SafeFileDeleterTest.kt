package com.filetransfer.app.core.storage.saf

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.security.SecureRandom
import java.util.Arrays

class SafeFileDeleterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val random = SecureRandom()

    @Test
    fun testWipeAndSanitizeFileDeletesFile() {
        val file = tempFolder.newFile("confidential_document.pdf")
        val content = ByteArray(128 * 1024) // 128 KB
        random.nextBytes(content)
        file.writeBytes(content)

        assertTrue(file.exists())
        assertEquals(content.size.toLong(), file.length())

        val success = SafeFileDeleter.wipeAndSanitizeFile(file)
        assertTrue("Hàm tiêu hủy tệp phải trả về true", success)
        assertFalse("Tệp phải bị xóa hoàn toàn khỏi đĩa", file.exists())
    }

    @Test
    fun testWipeZeroesContentBeforeDeletion() {
        // Kiểm tra trực tiếp cơ chế ghi đè bằng 0x00
        val file = tempFolder.newFile("test_zero_overwrite.part")
        val size = 65536 // 64 KB
        val originalBytes = ByteArray(size) { 0xFF.toByte() }
        file.writeBytes(originalBytes)

        assertEquals(size.toLong(), file.length())

        // Ghi đè 0x00
        val zeroBuffer = ByteArray(size)
        Arrays.fill(zeroBuffer, 0.toByte())

        RandomAccessFile(file, "rws").use { raf ->
            raf.seek(0)
            raf.write(zeroBuffer)
            raf.fd.sync()
        }

        // Đọc lại để xác nhận toàn bộ byte là 0x00
        val readBack = file.readBytes()
        assertEquals(size, readBack.size)
        assertTrue("Toàn bộ mảng byte phải là 0x00", readBack.all { it == 0.toByte() })

        // Sau đó xóa inode
        assertTrue(file.delete())
        assertFalse(file.exists())
    }

    @Test
    fun testWipeDirectoryRecursively() {
        val subDir = tempFolder.newFolder("team_transfer_dir")
        val file1 = File(subDir, "chunk_1.part")
        val file2 = File(subDir, "chunk_2.part")
        file1.writeBytes(ByteArray(1024) { 1.toByte() })
        file2.writeBytes(ByteArray(2048) { 2.toByte() })

        val nestedDir = File(subDir, "nested")
        nestedDir.mkdirs()
        val file3 = File(nestedDir, "nested_file.bin")
        file3.writeBytes(ByteArray(512) { 3.toByte() })

        assertTrue(file1.exists())
        assertTrue(file2.exists())
        assertTrue(file3.exists())

        val success = SafeFileDeleter.wipeDirectory(subDir)
        assertTrue("Wipe directory đệ quy phải thành công", success)
        assertFalse("Thư mục và các tệp con phải bị dọn sạch", subDir.exists())
        assertFalse(file1.exists())
        assertFalse(file2.exists())
        assertFalse(file3.exists())
    }

    @Test
    fun testWipeNonExistentFileReturnsTrue() {
        val nonExistent = File(tempFolder.root, "does_not_exist.bin")
        assertFalse(nonExistent.exists())

        val success = SafeFileDeleter.wipeAndSanitizeFile(nonExistent)
        assertTrue("Xóa tệp không tồn tại không được văng lỗi", success)
    }
}

