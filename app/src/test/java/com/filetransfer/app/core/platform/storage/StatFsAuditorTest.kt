package com.filetransfer.app.core.platform.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Bộ kiểm thử kiểm toán dung lượng lưu trữ đĩa vật lý StatFsStorageAuditor.
 * Tuân thủ quy định SDD mục 4.6, 8.5 và FR-RCV-02.
 */
class StatFsAuditorTest {

    private lateinit var auditor: StatFsStorageAuditor
    private val safetyMargin = StatFsStorageAuditor.SAFETY_MARGIN_BYTES // 50 MB

    @Before
    fun setUp() {
        auditor = StatFsStorageAuditor(context = null)
    }

    @Test
    fun testSufficientSpaceReturnsTrue() {
        val requiredBytes = 100L * 1024L * 1024L // 100 MB
        // Giả lập đĩa còn 200 MB trống (> 100MB + 50MB)
        auditor.simulateStorage(availableBytes = 200L * 1024L * 1024L, totalBytes = 500L * 1024L * 1024L)

        val hasSpace = auditor.hasSufficientSpace(requiredBytes)
        assertTrue("Phải đủ bộ nhớ khi dung lượng trống (200MB) vượt ngưỡng cần (150MB)", hasSpace)

        val result = auditor.auditStorage(requiredBytes)
        assertTrue(result.isSufficient)
        assertEquals(0L, result.shortfallBytes)
        assertEquals(200L * 1024L * 1024L, result.availableBytes)
    }

    @Test
    fun testInsufficientSpaceReturnsFalse() {
        val requiredBytes = 100L * 1024L * 1024L // 100 MB
        // Giả lập đĩa còn 140 MB trống (< 100MB + 50MB buffer)
        auditor.simulateStorage(availableBytes = 140L * 1024L * 1024L, totalBytes = 500L * 1024L * 1024L)

        val hasSpace = auditor.hasSufficientSpace(requiredBytes)
        assertFalse("Phải báo thiếu bộ nhớ khi dung lượng trống (140MB) nhỏ hơn 150MB", hasSpace)

        val result = auditor.auditStorage(requiredBytes)
        assertFalse(result.isSufficient)
        assertEquals(10L * 1024L * 1024L, result.shortfallBytes) // Thiếu 10MB
    }

    @Test
    fun testExactBoundaryCondition() {
        val requiredBytes = 50L * 1024L * 1024L // 50 MB
        val exactNeeded = requiredBytes + safetyMargin // 100 MB

        // Trường hợp đúng bằng 100MB
        auditor.simulateStorage(availableBytes = exactNeeded)
        assertTrue("Phải chấp nhận khi dung lượng trống vừa khít ngưỡng", auditor.hasSufficientSpace(requiredBytes))

        // Trường hợp thiếu đúng 1 byte
        auditor.simulateStorage(availableBytes = exactNeeded - 1L)
        assertFalse("Phải từ chối khi dung lượng trống thiếu dù chỉ 1 byte", auditor.hasSufficientSpace(requiredBytes))
    }

    @Test
    fun testRealFilesystemFallbackOnJvm() {
        // Reset trạng thái mô phỏng để kích hoạt fallback sang java.io.File.usableSpace
        auditor.simulateStorage(availableBytes = null, totalBytes = null)

        val currentDir = File(".")
        val available = auditor.getAvailableBytes(currentDir)
        val total = auditor.getTotalBytes(currentDir)

        assertTrue("Dung lượng khả dụng của đĩa thực phải > 0", available > 0L)
        assertTrue("Tổng dung lượng đĩa thực phải >= dung lượng khả dụng", total >= available)
    }
}

