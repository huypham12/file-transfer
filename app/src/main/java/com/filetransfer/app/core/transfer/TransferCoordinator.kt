package com.filetransfer.app.core.transfer

import com.filetransfer.app.core.constant.AppConstants
import com.filetransfer.app.core.platform.network.INetworkMonitor
import com.filetransfer.app.core.platform.network.NetworkAddressHelper
import com.filetransfer.app.core.platform.network.NetworkType
import com.filetransfer.app.core.platform.service.ITransferForegroundServiceLauncher
import com.filetransfer.app.core.platform.service.ITransferServiceController
import com.filetransfer.app.core.platform.service.TransferServiceController
import com.filetransfer.app.core.security.keystore.IKeyManager
import com.filetransfer.app.core.storage.saf.StorageDirectoryManager
import com.filetransfer.app.core.transfer.lan.LanTransferEngine
import com.filetransfer.app.core.transfer.relay.RelayClientEngine
import com.filetransfer.app.domain.model.SessionStatus
import com.filetransfer.app.domain.model.TransferChannel
import com.filetransfer.app.domain.model.TransferSession
import com.filetransfer.app.domain.repository.IAuditRepository
import com.filetransfer.app.domain.repository.ITransferRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Nhạc trưởng Điều phối Truyền tải Đa môi trường (TransferCoordinator).
 * Đảm nhiệm:
 * 1. Định tuyến tự động kênh truyền: Ưu tiên Direct LAN khi cùng Wi-Fi; chuyển sang Zero-Knowledge Relay khi khác mạng.
 * 2. Điều phối trạng thái vòng đời phiên truyền tải (Tạm dừng, Tiếp tục, Hủy phiên).
 * 3. Cập nhật đồng bộ dịch vụ Android Foreground Service (TransferServiceController).
 */
class TransferCoordinator(
    private val lanEngine: LanTransferEngine,
    private val relayEngine: RelayClientEngine,
    private val networkMonitor: INetworkMonitor,
    private val transferRepository: ITransferRepository,
    private val serviceController: ITransferServiceController = TransferServiceController,
    private val serviceLauncher: ITransferForegroundServiceLauncher? = null,
    private val directoryManager: StorageDirectoryManager? = null,
    private val auditRepository: IAuditRepository? = null,
    private val keyManager: IKeyManager? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    private val activeSessions = ConcurrentHashMap<String, TransferSession>()
    private val isPausedMap = ConcurrentHashMap<String, AtomicBoolean>()

    /**
     * Xác định kênh truyền phù hợp dựa trên trạng thái mạng và thông tin kết nối.
     * Hỗ trợ thăm dò kết nối LAN (Pre-flight Probe TCP) khi có cổng kết nối cụ thể.
     */
    fun selectTransferChannel(peerIp: String?, port: Int = 0): TransferChannel {
        val currentNetwork = networkMonitor.getCurrentNetworkType()
        if (currentNetwork != NetworkType.WIFI_SAME_LAN || peerIp.isNullOrBlank()) {
            return TransferChannel.RELAY_E2EE
        }

        // Nếu thiết bị này là Android Emulator và peer là thiết bị LAN vật lý bên ngoài (NAT máy ảo không cho phép kết nối ngược vào 10.0.2.16)
        if (NetworkAddressHelper.isEmulator() &&
            !peerIp.startsWith("10.0.2.") && !peerIp.startsWith("127.0.0.1") && peerIp != "localhost"
        ) {
            return TransferChannel.RELAY_E2EE
        }

        if (port > 0) {
            val isReachable = try {
                java.net.Socket().use { socket ->
                    socket.connect(java.net.InetSocketAddress(peerIp, port), AppConstants.Timeout.LAN_FAST_PROBE_MS.toInt())
                    true
                }
            } catch (_: Exception) {
                false
            }
            if (!isReachable) {
                return TransferChannel.RELAY_E2EE
            }
        }

        return TransferChannel.DIRECT_LAN
    }

    /**
     * Tạm dừng phiên truyền tải đang hoạt động.
     */
    suspend fun pauseSession(sessionId: String) = withContext(ioDispatcher) {
        isPausedMap[sessionId]?.set(true)
        lanEngine.pause()
        relayEngine.pause()
        serviceController.requestPause()
        transferRepository.updateSessionStatus(sessionId, SessionStatus.PAUSED)
    }

    /**
     * Tiếp tục phiên truyền tải đã tạm dừng.
     */
    suspend fun resumeSession(sessionId: String) = withContext(ioDispatcher) {
        isPausedMap[sessionId]?.set(false)
        lanEngine.resume()
        relayEngine.resume()
        serviceController.requestResume()
        transferRepository.updateSessionStatus(sessionId, SessionStatus.TRANSFERRING)
    }

    /**
     * Hủy phiên truyền tải và dọn dẹp tài nguyên.
     */
    suspend fun cancelSession(sessionId: String) = withContext(ioDispatcher) {
        lanEngine.cancel()
        relayEngine.cancel()
        serviceController.requestCancel()
        activeSessions.remove(sessionId)
        isPausedMap.remove(sessionId)
        transferRepository.updateSessionStatus(sessionId, SessionStatus.CANCELLED)
    }

    /**
     * Lấy phiên truyền tải hiện tại nếu đang hoạt động.
     */
    fun getActiveSession(sessionId: String): TransferSession? = activeSessions[sessionId]

    /**
     * Đăng ký phiên truyền tải hoạt động (phục vụ test hoặc khởi tạo trước).
     */
    fun registerActiveSession(session: TransferSession) {
        activeSessions[session.sessionId] = session
    }
}
