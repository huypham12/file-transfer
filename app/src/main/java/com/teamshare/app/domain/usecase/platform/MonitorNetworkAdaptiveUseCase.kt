package com.teamshare.app.domain.usecase.platform

import com.teamshare.app.core.platform.network.INetworkMonitor
import com.teamshare.app.core.platform.network.NetworkType
import com.teamshare.app.domain.model.NetworkAdaptiveState
import com.teamshare.app.domain.model.TransferChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * UC-19: Giám sát mạng qua NetworkCallback & Chuyển đổi LAN/Relay (SRS FR-MOB-04).
 * Lắng nghe biến động mạng phản ứng theo thời gian thực từ INetworkMonitor,
 * tính toán kênh truyền khuyến nghị (Direct LAN vs Zero-Knowledge Relay).
 */
class MonitorNetworkAdaptiveUseCase(
    private val networkMonitor: INetworkMonitor
) {
    operator fun invoke(): Flow<NetworkAdaptiveState> {
        return networkMonitor.networkState.map { state ->
            val isOnline = state.isConnected
            val channel = if (state.type == NetworkType.WIFI_SAME_LAN) {
                TransferChannel.DIRECT_LAN
            } else {
                TransferChannel.ZERO_KNOWLEDGE_RELAY
            }

            val message = when {
                state.type == NetworkType.WIFI_SAME_LAN ->
                    "Đang kết nối cùng mạng Wi-Fi LAN. Khuyến nghị truyền trực tiếp tốc độ cao."
                isOnline ->
                    "Đang kết nối Internet (${state.type.name}). Khuyến nghị truyền qua máy chủ chuyển tiếp Zero-Knowledge E2EE."
                else ->
                    "Mất kết nối mạng. Tiến trình truyền tải sẽ tự động tạm dừng an toàn."
            }

            NetworkAdaptiveState(
                isOnline = isOnline,
                currentNetwork = state.type.name,
                recommendedChannel = channel,
                message = message
            )
        }
    }
}

