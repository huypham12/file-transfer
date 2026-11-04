package com.teamshare.app.core.platform.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hiện thực hóa bộ giám sát mạng thời gian thực dựa trên ConnectivityManager.NetworkCallback của Android.
 * Tuân thủ:
 * - SRS FR-MOB-04: Phát hiện mất kết nối Wi-Fi trong < 500 ms và phát tín hiệu chuyển đổi LAN/Relay.
 * - SDD Mục 4.2 & 8.4: Cập nhật StateFlow phản ứng cho TransferCoordinator.
 */
class AndroidNetworkMonitor(
    private val context: Context? = null,
    private val connectivityManager: ConnectivityManager? = context?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
) : INetworkMonitor {

    private val _networkState = MutableStateFlow(NetworkState())
    override val networkState: StateFlow<NetworkState> = _networkState.asStateFlow()

    private val isMonitoring = AtomicBoolean(false)

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            updateFromNetwork(network)
        }

        override fun onLost(network: Network) {
            // Khi mất một network, kiểm tra xem còn active network nào khác không
            val activeNetwork = connectivityManager?.activeNetwork
            if (activeNetwork == null || activeNetwork == network) {
                _networkState.value = NetworkState(
                    type = NetworkType.DISCONNECTED,
                    isConnected = false,
                    isMetered = false,
                    hasInternetCapability = false,
                    linkDownstreamBandwidthKbps = 0
                )
            } else {
                updateFromNetwork(activeNetwork)
            }
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            resolveCapabilities(networkCapabilities)
        }
    }

    init {
        // Khởi tạo trạng thái ban đầu từ active network nếu khả dụng
        queryInitialState()
    }

    private fun queryInitialState() {
        try {
            val activeNetwork = connectivityManager?.activeNetwork
            if (activeNetwork != null) {
                updateFromNetwork(activeNetwork)
            } else {
                _networkState.value = NetworkState(
                    type = NetworkType.DISCONNECTED,
                    isConnected = false
                )
            }
        } catch (e: Exception) {
            _networkState.value = NetworkState(
                type = NetworkType.UNKNOWN,
                isConnected = false
            )
        }
    }

    private fun updateFromNetwork(network: Network) {
        val capabilities = connectivityManager?.getNetworkCapabilities(network)
        if (capabilities != null) {
            resolveCapabilities(capabilities)
        } else {
            _networkState.value = NetworkState(
                type = NetworkType.UNKNOWN,
                isConnected = true
            )
        }
    }

    private fun resolveCapabilities(capabilities: NetworkCapabilities) {
        val type = when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI_SAME_LAN
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.CELLULAR
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.ETHERNET
            else -> NetworkType.UNKNOWN
        }

        val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val isMetered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        val bandwidth = capabilities.linkDownstreamBandwidthKbps

        _networkState.value = NetworkState(
            type = type,
            isConnected = true,
            isMetered = isMetered,
            hasInternetCapability = hasInternet,
            linkDownstreamBandwidthKbps = bandwidth
        )
    }

    override fun getCurrentNetworkType(): NetworkType {
        return _networkState.value.type
    }

    override fun getCurrentNetworkState(): NetworkState {
        return _networkState.value
    }

    override fun isOnline(): Boolean {
        return _networkState.value.isConnected
    }

    override fun startMonitoring() {
        if (isMonitoring.compareAndSet(false, true)) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    connectivityManager?.registerDefaultNetworkCallback(networkCallback)
                } else {
                    val request = android.net.NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build()
                    connectivityManager?.registerNetworkCallback(request, networkCallback)
                }
            } catch (e: Exception) {
                isMonitoring.set(false)
            }
        }
    }

    override fun stopMonitoring() {
        if (isMonitoring.compareAndSet(true, false)) {
            try {
                connectivityManager?.unregisterNetworkCallback(networkCallback)
            } catch (e: Exception) {
                // Ignore unregister exceptions if already unregistered
            }
        }
    }

    /**
     * Phương thức nội bộ phục vụ mô phỏng kiểm thử nhanh (Testing support hook).
     */
    internal fun simulateNetworkState(state: NetworkState) {
        _networkState.value = state
    }

    internal fun getInternalCallback(): ConnectivityManager.NetworkCallback {
        return networkCallback
    }
}

