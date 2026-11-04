package com.teamshare.app.core.platform.network

import kotlinx.coroutines.flow.StateFlow

/**
 * Giao diện trừu tượng cho dịch vụ giám sát mạng thời gian thực.
 * Cho phép tách biệt logic Android framework khỏi tầng nghiệp vụ Domain (Clean Architecture)
 * và tạo điều kiện chạy các bài kiểm thử tự động độc lập trên JVM.
 */
interface INetworkMonitor {
    /**
     * Luồng trạng thái mạng phản ứng theo thời gian thực (StateFlow).
     */
    val networkState: StateFlow<NetworkState>

    /**
     * Lấy loại kết nối mạng hiện tại một cách đồng bộ.
     */
    fun getCurrentNetworkType(): NetworkType

    /**
     * Lấy trạng thái mạng hiện tại một cách đồng bộ.
     */
    fun getCurrentNetworkState(): NetworkState

    /**
     * Kiểm tra nhanh xem thiết bị có đang trực tuyến (có kết nối mạng) hay không.
     */
    fun isOnline(): Boolean

    /**
     * Bắt đầu đăng ký lắng nghe sự kiện mạng từ hệ điều hành.
     */
    fun startMonitoring()

    /**
     * Hủy đăng ký lắng nghe sự kiện mạng để giải phóng tài nguyên.
     */
    fun stopMonitoring()
}

