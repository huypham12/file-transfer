package com.teamshare.app.core.platform.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AndroidNetworkMonitorTest {

    private lateinit var networkMonitor: AndroidNetworkMonitor

    @Before
    fun setUp() {
        // Khởi tạo AndroidNetworkMonitor không phụ thuộc Android context để test state flow logic
        networkMonitor = AndroidNetworkMonitor(context = null, connectivityManager = null)
    }

    @Test
    fun testInitialStateWhenNoNetworkManager() {
        val state = networkMonitor.getCurrentNetworkState()
        assertFalse(state.isConnected)
        assertEquals(NetworkType.DISCONNECTED, state.type)
        assertFalse(networkMonitor.isOnline())
    }

    @Test
    fun testSimulateWifiConnectedState() {
        val wifiState = NetworkState(
            type = NetworkType.WIFI_SAME_LAN,
            isConnected = true,
            isMetered = false,
            hasInternetCapability = true,
            linkDownstreamBandwidthKbps = 150_000
        )

        networkMonitor.simulateNetworkState(wifiState)

        assertTrue(networkMonitor.isOnline())
        assertEquals(NetworkType.WIFI_SAME_LAN, networkMonitor.getCurrentNetworkType())
        assertEquals(150_000, networkMonitor.getCurrentNetworkState().linkDownstreamBandwidthKbps)
        assertFalse(networkMonitor.getCurrentNetworkState().isMetered)
    }

    @Test
    fun testSimulateCellularState() {
        val cellState = NetworkState(
            type = NetworkType.CELLULAR,
            isConnected = true,
            isMetered = true,
            hasInternetCapability = true,
            linkDownstreamBandwidthKbps = 50_000
        )

        networkMonitor.simulateNetworkState(cellState)

        assertTrue(networkMonitor.isOnline())
        assertEquals(NetworkType.CELLULAR, networkMonitor.getCurrentNetworkType())
        assertTrue(networkMonitor.getCurrentNetworkState().isMetered)
    }

    @Test
    fun testSimulateNetworkLossUnder500ms() {
        // Bắt đầu với mạng Wi-Fi
        networkMonitor.simulateNetworkState(
            NetworkState(
                type = NetworkType.WIFI_SAME_LAN,
                isConnected = true,
                hasInternetCapability = true
            )
        )
        assertTrue(networkMonitor.isOnline())

        val startTime = System.currentTimeMillis()

        // Mô phỏng sự kiện mất mạng
        networkMonitor.simulateNetworkState(
            NetworkState(
                type = NetworkType.DISCONNECTED,
                isConnected = false,
                isMetered = false,
                hasInternetCapability = false
            )
        )

        val duration = System.currentTimeMillis() - startTime

        assertFalse(networkMonitor.isOnline())
        assertEquals(NetworkType.DISCONNECTED, networkMonitor.getCurrentNetworkType())
        assertTrue("Mất mạng phải được phát hiện dưới 500ms", duration < 500)
    }

    @Test
    fun testStartAndStopMonitoringIdempotency() {
        // Gọi liên tục không được văng lỗi khi không có ConnectivityManager
        networkMonitor.startMonitoring()
        networkMonitor.startMonitoring()

        networkMonitor.stopMonitoring()
        networkMonitor.stopMonitoring()
    }
}
