package com.filetransfer.app.core.platform.network

/**
 * Các loại kết nối mạng vật lý và ngữ cảnh định tuyến của thiết bị.
 * Phục vụ quyết định chuyển kênh LAN <-> Relay của TransferCoordinator (SRS FR-MOB-04, SDD 4.2).
 */
enum class NetworkType {
    WIFI_SAME_LAN,
    WIFI_OTHER,
    CELLULAR,
    ETHERNET,
    DISCONNECTED,
    UNKNOWN
}

