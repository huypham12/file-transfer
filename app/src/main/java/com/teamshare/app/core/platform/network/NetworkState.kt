package com.teamshare.app.core.platform.network

/**
 * Trạng thái chi tiết của kết nối mạng trên thiết bị.
 *
 * @property type Loại kết nối mạng vật lý (Wi-Fi, 4G/5G, Ethernet, Mất mạng...).
 * @property isConnected Có đang duy trì kết nối mạng hay không.
 * @property isMetered Kết nối mạng có bị tính cước dữ liệu di động hay không.
 * @property hasInternetCapability Kết nối mạng có khả năng truy cập Internet ra ngoài hay không.
 * @property linkDownstreamBandwidthKbps Băng thông tải xuống ước tính của đường truyền (Kbps).
 */
data class NetworkState(
    val type: NetworkType = NetworkType.UNKNOWN,
    val isConnected: Boolean = false,
    val isMetered: Boolean = false,
    val hasInternetCapability: Boolean = false,
    val linkDownstreamBandwidthKbps: Int = 0
)

