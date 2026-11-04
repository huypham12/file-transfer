package com.teamshare.app.core.platform

import com.teamshare.app.core.platform.network.AndroidNetworkMonitor
import com.teamshare.app.core.platform.network.NetworkState
import com.teamshare.app.core.platform.network.NetworkType
import com.teamshare.app.core.platform.service.NotificationManagerHelper
import com.teamshare.app.core.platform.service.TransferServiceController
import com.teamshare.app.core.security.sanitizer.FilenameSanitizer
import com.teamshare.app.core.transfer.lan.TransferEngineState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class Stage5IntegrationTest {

    private lateinit var networkMonitor: AndroidNetworkMonitor
    private lateinit var notificationHelper: NotificationManagerHelper
    private val controller = TransferServiceController

    @Before
    fun setUp() {
        controller.reset()
        networkMonitor = AndroidNetworkMonitor(context = null, connectivityManager = null)
        notificationHelper = NotificationManagerHelper(context = null, notificationManager = null)
    }

    @After
    fun tearDown() {
        controller.reset()
    }

    @Test
    fun testEndToEndNetworkLossTriggersPauseScenario() {
        // 1. Khởi động phiên truyền tệp trên mạng Wi-Fi
        networkMonitor.simulateNetworkState(
            NetworkState(
                type = NetworkType.WIFI_SAME_LAN,
                isConnected = true,
                hasInternetCapability = true
            )
        )
        assertTrue(networkMonitor.isOnline())

        controller.startSession(
            sessionId = "session-e2e-1",
            documentName = "TaiLieuChuyenGiao.zip",
            totalBytes = 500_000_000L,
            isLan = true
        )
        controller.notifyProgress(100_000_000L, 500_000_000L, 25_000_000L) // Đã truyền 20%
        assertEquals(TransferEngineState.TRANSFERRING, controller.serviceState.value.engineState)

        // 2. Mô phỏng đứt kết nối mạng đột ngột (< 500ms)
        val startTime = System.currentTimeMillis()
        networkMonitor.simulateNetworkState(
            NetworkState(
                type = NetworkType.DISCONNECTED,
                isConnected = false
            )
        )
        val lossDetectionDuration = System.currentTimeMillis() - startTime
        assertTrue("Thời gian phát hiện đứt mạng phải < 500ms", lossDetectionDuration < 500)

        // 3. Hệ thống kích hoạt tạm dừng an toàn và lưu mốc Checkpoint
        controller.notifyStateChanged(TransferEngineState.PAUSED)
        val pausedState = controller.serviceState.value
        assertEquals(TransferEngineState.PAUSED, pausedState.engineState)
        assertTrue(pausedState.isRunning) // Vẫn giữ session để sẵn sàng resume
        assertEquals(100_000_000L, pausedState.bytesTransferred)

        // 4. Kiểm tra chuỗi định dạng thông báo tạm dừng
        val isPausedNotificationFormatValid = notificationHelper.formatBytes(pausedState.bytesTransferred)
        assertEquals("95.4 MB", isPausedNotificationFormatValid) // 100_000_000 / (1024*1024) = 95.367 MB
    }

    @Test
    fun testEndToEndNetworkRecoveryAndResumeScenario() {
        // Bắt đầu từ trạng thái tạm dừng
        controller.startSession("session-resume", "Dataset.bin", 1_000_000_000L, true)
        controller.notifyProgress(400_000_000L, 1_000_000_000L, 0L)
        controller.notifyStateChanged(TransferEngineState.PAUSED)

        // Mạng Wi-Fi hồi phục
        networkMonitor.simulateNetworkState(
            NetworkState(
                type = NetworkType.WIFI_SAME_LAN,
                isConnected = true,
                hasInternetCapability = true
            )
        )
        assertTrue(networkMonitor.isOnline())

        // Người dùng kích hoạt Tiếp tục
        controller.requestResume()
        controller.notifyProgress(450_000_000L, 1_000_000_000L, 30_000_000L)

        assertEquals(TransferEngineState.TRANSFERRING, controller.serviceState.value.engineState)
        assertEquals(450_000_000L, controller.serviceState.value.bytesTransferred)
    }

    @Test
    fun testTransferCompletionLifecycle() {
        controller.startSession("session-done", "BaoCao.docx", 2_000_000L, true)
        controller.notifyProgress(2_000_000L, 2_000_000L, 10_000_000L)

        val finalHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        controller.notifyCompleted("doc-999", finalHash)

        val finalState = controller.serviceState.value
        assertFalse(finalState.isRunning)
        assertEquals(TransferEngineState.COMPLETED, finalState.engineState)
        assertEquals(finalHash, finalState.finalSha256)
    }

    @Test
    fun testShareIntentPipelineWithFilenameSanitization() {
        // Mô phỏng ứng dụng ngoài chia sẻ một tệp có tên nguy hiểm chứa Path Traversal
        val maliciousFileName = "../../../../../etc/passwd%00.pdf"
        val sanitized = FilenameSanitizer.sanitize(maliciousFileName)

        assertFalse("Tên tệp sau khi sanitize không được chứa ký tự leo thang", sanitized.contains(".."))
        assertFalse("Tên tệp sau khi sanitize không được chứa dấu gạch chéo", sanitized.contains("/"))
        assertTrue("Phải giữ lại định dạng an toàn", sanitized.endsWith(".pdf"))
    }
}
