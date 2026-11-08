package com.teamshare.app.core.transfer.zip

import com.teamshare.app.core.security.crypto.Sha256StreamCalculator
import com.teamshare.app.core.transfer.buffer.BufferPoolManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Bài kiểm thử tích hợp đóng gói và giải nén cây thư mục phức tạp (50 tệp tin lồng 3 cấp).
 * Đạt tiêu chuẩn Milestone 3 theo SDD mục 4.5.3, 8.2 và SAD ADR-06:
 * - 50 tệp lồng nhau 3 cấp cấu trúc thư mục.
 * - Đóng gói Zip Stream trực tiếp với BufferPoolManager.
 * - Giải nén an toàn qua SafeStreamExtractor và kiểm tra tính toàn vẹn 100%.
 */
class ZipStreamIntegrationTest {

    private lateinit var tempRoot: File
    private lateinit var sourceFolder: File
    private lateinit var extractedFolder: File
    private lateinit var bufferPool: BufferPoolManager

    @Before
    fun setUp() {
        tempRoot = File(System.getProperty("java.io.tmpdir"), "zip_integration_${System.nanoTime()}")
        tempRoot.mkdirs()
        sourceFolder = File(tempRoot, "test_project_dir").apply { mkdirs() }
        extractedFolder = File(tempRoot, "extracted_project_dir").apply { mkdirs() }
        bufferPool = BufferPoolManager(maxBuffers = 16, bufferSize = 65536)
    }

    @After
    fun tearDown() {
        tempRoot.deleteRecursively()
    }

    @Test
    fun testPackAndExtract50NestedFiles3LevelsDeep() {
        // 1. Tạo cây thư mục 50 tệp tin lồng 3 cấp
        val expectedFilesMap = mutableMapOf<String, String>() // relativePath -> SHA-256

        for (i in 1..50) {
            val level1 = "module_${i % 5}"
            val level2 = "package_${i % 3}"
            val level3 = "component_${i % 2}"

            val dir = File(sourceFolder, "$level1/$level2/$level3").apply { mkdirs() }
            val file = File(dir, "source_code_$i.kt")

            val fileContent = "package $level1.$level2.$level3\n\n// File index $i\nclass DataClass$i(val id: Int = $i) {\n    fun calculate() = id * 42\n}\n"
            file.writeText(fileContent, Charsets.UTF_8)

            val relPath = file.canonicalFile.toRelativeString(sourceFolder.canonicalFile).replace('\\', '/')
            val sha256 = Sha256StreamCalculator.calculateFileSha256(file)
            expectedFilesMap[relPath] = sha256
        }

        assertEquals(50, expectedFilesMap.size)

        // 2. Tính toán siêu dữ liệu bằng Virtual Dry-Run (0 byte ghi đĩa)
        val metadata = ZipStreamPacker.calculateZipMetadata(sourceFolder, bufferPool)
        assertEquals(50, metadata.fileCount)
        assertTrue("Tổng byte nén phải > 0", metadata.totalCompressedBytes > 0L)
        assertTrue("Mã băm SHA-256 không được rỗng", metadata.sha256.isNotBlank())

        // 3. Đóng gói trực tiếp qua luồng (Piped Stream) không sinh file trung gian
        val pipedOut = PipedOutputStream()
        val pipedIn = PipedInputStream(pipedOut, 256 * 1024)

        var packedBytes = 0L
        runBlocking {
            val packJob = launch(Dispatchers.IO) {
                try {
                    packedBytes = ZipStreamPacker.packDirectoryToStream(
                        sourceDir = sourceFolder,
                        outputStream = pipedOut,
                        bufferPool = bufferPool
                    )
                } finally {
                    pipedOut.close()
                }
            }

            // 4. Giải nén an toàn qua SafeStreamExtractor từ luồng piped
            val extractResult = SafeStreamExtractor.extractZipStream(
                inputStream = pipedIn,
                destinationDir = extractedFolder,
                bufferPool = bufferPool
            )
            packJob.join()

            // 5. Kiểm chứng kết quả giải nén
            assertEquals("Số lượng tệp nén khớp metadata", metadata.totalCompressedBytes, packedBytes)
            assertEquals("Số lượng tệp giải nén phải đúng 50", 50, extractResult.totalFiles)
            assertEquals("Danh sách file trích xuất phải đúng 50", 50, extractResult.extractedFiles.size)
        }

        // 6. Đối chiếu toàn vẹn 50 tệp tin: đường dẫn, nội dung và SHA-256
        for ((relPath, expectedSha) in expectedFilesMap) {
            val extractedFile = File(extractedFolder, relPath)
            assertTrue("Tệp giải nén phải tồn tại: $relPath", extractedFile.exists())
            assertTrue("Phải là tệp thông thường", extractedFile.isFile)

            val actualSha = Sha256StreamCalculator.calculateFileSha256(extractedFile)
            assertEquals("Mã băm SHA-256 của $relPath phải khớp 100%", expectedSha, actualSha)
        }

        // 7. Xác nhận BufferPoolManager không bị rò rỉ permit
        assertEquals(
            "Tất cả buffer phải được hoàn trả về BufferPool",
            16,
            bufferPool.availablePermits()
        )
    }

    @Test
    fun testPackMetadataDeterministicReproducibility() {
        // Tạo vài tệp tin
        File(sourceFolder, "dirA").mkdirs()
        File(sourceFolder, "dirA/file1.txt").writeText("Hello World 1")
        File(sourceFolder, "dirB").mkdirs()
        File(sourceFolder, "dirB/file2.txt").writeText("Hello World 2")

        // Chạy tính toán metadata 2 lần
        val meta1 = ZipStreamPacker.calculateZipMetadata(sourceFolder, bufferPool)
        val meta2 = ZipStreamPacker.calculateZipMetadata(sourceFolder, bufferPool)

        assertEquals("Kích thước nén phải giống nhau giữa các lần", meta1.totalCompressedBytes, meta2.totalCompressedBytes)
        assertEquals("Mã SHA-256 phải đồng nhất 100% (deterministic)", meta1.sha256, meta2.sha256)
    }
}

