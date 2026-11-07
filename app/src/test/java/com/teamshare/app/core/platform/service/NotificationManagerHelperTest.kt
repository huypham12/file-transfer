package com.teamshare.app.core.platform.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NotificationManagerHelperTest {

    private lateinit var helper: NotificationManagerHelper

    @Before
    fun setUp() {
        // Khởi tạo helper độc lập không phụ thuộc Android context để test logic format và throttling
        helper = NotificationManagerHelper(context = null, notificationManager = null)
    }

    @Test
    fun testFormatSpeed() {
        assertEquals("0 KB/s", helper.formatSpeed(0L))
        assertEquals("500 B/s", helper.formatSpeed(500L))
        assertEquals("50 KB/s", helper.formatSpeed(50 * 1024L))
        assertEquals("1.5 MB/s", helper.formatSpeed((1.5 * 1024 * 1024).toLong()))
        assertEquals("45.0 MB/s", helper.formatSpeed(45 * 1024 * 1024L))
    }

    @Test
    fun testFormatBytes() {
        assertEquals("500 B", helper.formatBytes(500L))
        assertEquals("50.0 KB", helper.formatBytes(50 * 1024L))
        assertEquals("100.5 MB", helper.formatBytes((100.5 * 1024 * 1024).toLong()))
        assertEquals("2.50 GB", helper.formatBytes((2.5 * 1024 * 1024 * 1024).toLong()))
    }

    @Test
    fun testThrottlingMechanism() {
        // Lần đầu tiên nên cho phép cập nhật
        assertTrue(helper.shouldUpdate(force = false))

        // Gọi lại ngay tức thì (< 500ms) phải bị chặn bởi throttle
        assertFalse(helper.shouldUpdate(force = false))
        assertFalse(helper.shouldUpdate(force = false))

        // Gọi với cờ force = true (như khi Pause/Complete) phải được phép
        assertTrue(helper.shouldUpdate(force = true))
    }

    @Test
    fun testExtraSessionIdConstant() {
        assertEquals("extra_session_id", NotificationManagerHelper.EXTRA_SESSION_ID)
    }
}
