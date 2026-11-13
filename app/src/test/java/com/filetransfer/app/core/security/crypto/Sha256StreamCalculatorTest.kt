package com.filetransfer.app.core.security.crypto

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

class Sha256StreamCalculatorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testEmptyInputHash() {
        val emptyStream = ByteArrayInputStream(ByteArray(0))
        val hash = Sha256StreamCalculator.calculateStreamSha256(emptyStream)
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hash)

        val bytesHash = Sha256StreamCalculator.calculateBytesSha256(ByteArray(0))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", bytesHash)
    }

    @Test
    fun testStandardNistTestVector() {
        val input = "The quick brown fox jumps over the lazy dog".toByteArray()
        val expectedHash = "d7a8fbb307d7809469ca9abcb0082e4f8d5651e46d3cdb762d02d0bf37c9e592"

        val hashFromBytes = Sha256StreamCalculator.calculateBytesSha256(input)
        assertEquals(expectedHash, hashFromBytes)

        val hashFromStream = Sha256StreamCalculator.calculateStreamSha256(ByteArrayInputStream(input))
        assertEquals(expectedHash, hashFromStream)
    }

    @Test
    fun testMultiChunkStreamingConsistency() {
        // Tạo khối dữ liệu 100 KB
        val size = 100 * 1024
        val data = ByteArray(size) { (it % 256).toByte() }

        // Băm trực tiếp từ mảng byte
        val directHash = Sha256StreamCalculator.calculateBytesSha256(data)

        // Băm theo streaming với buffer cực nhỏ (512 bytes) để kích hoạt lặp nhiều chunk
        val streamHash = Sha256StreamCalculator.calculateStreamSha256(
            ByteArrayInputStream(data),
            bufferSize = 512
        )

        assertEquals(directHash, streamHash)
    }

    @Test
    fun testFileHashMatchesStreamHash() {
        val testFile = tempFolder.newFile("test_report.pdf")
        val content = "Dữ liệu tệp báo cáo tốt nghiệp bảo mật 2026.".toByteArray()
        testFile.writeBytes(content)

        val expectedHash = Sha256StreamCalculator.calculateBytesSha256(content)
        val fileHash = Sha256StreamCalculator.calculateFileSha256(testFile)

        assertEquals(expectedHash, fileHash)
    }
}

