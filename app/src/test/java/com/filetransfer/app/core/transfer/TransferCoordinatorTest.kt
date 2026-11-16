package com.filetransfer.app.core.transfer

import com.filetransfer.app.core.platform.network.INetworkMonitor
import com.filetransfer.app.core.platform.network.NetworkState
import com.filetransfer.app.core.platform.network.NetworkType
import com.filetransfer.app.core.platform.service.ITransferServiceController
import com.filetransfer.app.core.platform.service.TransferServiceState
import com.filetransfer.app.core.security.keystore.SoftwareKeyManager
import com.filetransfer.app.core.transfer.lan.LanTransferEngine
import com.filetransfer.app.core.transfer.TransferEngineState
import com.filetransfer.app.core.transfer.TransferProgressCallback
import com.filetransfer.app.core.transfer.relay.RelayClientEngine
import com.filetransfer.app.domain.model.SessionStatus
import com.filetransfer.app.domain.model.TransferChannel
import com.filetransfer.app.domain.model.TransferDirection
import com.filetransfer.app.domain.model.TransferSession
import com.filetransfer.app.domain.repository.ITransferRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

class FakeNetworkMonitor(
    initialState: NetworkState = NetworkState(
        type = NetworkType.WIFI_SAME_LAN,
        isConnected = true
    )
) : INetworkMonitor {
    private val _networkState = MutableStateFlow(initialState)
    override val networkState: StateFlow<NetworkState> = _networkState.asStateFlow()

    fun updateState(newState: NetworkState) {
        _networkState.value = newState
    }

    override fun getCurrentNetworkType(): NetworkType = _networkState.value.type
    override fun getCurrentNetworkState(): NetworkState = _networkState.value
    override fun isOnline(): Boolean = _networkState.value.isConnected
    override fun startMonitoring() {}
    override fun stopMonitoring() {}
}

class FakeTransferRepository : ITransferRepository {
    val sessions = ConcurrentHashMap<String, TransferSession>()
    var updateCheckpointCallCount = 0

    override suspend fun createSession(session: TransferSession) {
        sessions[session.sessionId] = session
    }

    override suspend fun getSessionById(sessionId: String): TransferSession? = sessions[sessionId]

    override fun observeSessionById(sessionId: String): Flow<TransferSession?> = flowOf(sessions[sessionId])

    override suspend fun findByToken(token: String): TransferSession? = sessions.values.firstOrNull { it.sessionToken == token }

    override fun getActiveSessions(): Flow<List<TransferSession>> = flowOf(sessions.values.toList())

    override fun getAllSessions(): Flow<List<TransferSession>> = flowOf(sessions.values.toList())

    override suspend fun updateCheckpoint(sessionId: String, confirmedOffset: Long) {
        updateCheckpointCallCount++
        sessions[sessionId]?.let { current ->
            sessions[sessionId] = current.copy(confirmedOffset = confirmedOffset)
        }
    }

    override suspend fun updateSessionStatus(sessionId: String, status: SessionStatus, errorCode: String?) {
        sessions[sessionId]?.let { current ->
            sessions[sessionId] = current.copy(status = status, errorCode = errorCode)
        }
    }

    override suspend fun updateApprovalMode(sessionId: String, mode: com.filetransfer.app.domain.model.ApprovalMode) {
        sessions[sessionId]?.let { current ->
            sessions[sessionId] = current.copy(approvalMode = mode)
        }
    }

    override suspend fun updatePeerInfo(sessionId: String, peerModel: String, peerFingerprint: String) {
        sessions[sessionId]?.let { current ->
            sessions[sessionId] = current.copy(peerDeviceModel = peerModel, peerFingerprint = peerFingerprint)
        }
    }

    override suspend fun updateSessionFile(sessionId: String, filePath: String) {
        sessions[sessionId]?.let { current ->
            sessions[sessionId] = current.copy(tempFilePath = filePath)
        }
    }

    override suspend fun deleteSession(sessionId: String) {
        sessions.remove(sessionId)
    }
}

class FakeServiceController : ITransferServiceController {
    private val _serviceState = MutableStateFlow(TransferServiceState())
    override val serviceState: StateFlow<TransferServiceState> = _serviceState.asStateFlow()

    var startSessionCalled = false
    var requestPauseCalled = false
    var requestResumeCalled = false
    var requestCancelCalled = false

    override fun registerCallback(callback: TransferProgressCallback) {}
    override fun unregisterCallback(callback: TransferProgressCallback) {}
    override fun startSession(sessionId: String, documentName: String, totalBytes: Long, isLan: Boolean) {
        startSessionCalled = true
        _serviceState.value = _serviceState.value.copy(
            isRunning = true,
            sessionId = sessionId,
            documentName = documentName,
            totalBytes = totalBytes,
            isLan = isLan
        )
    }
    override fun notifyProgress(bytesTransferred: Long, totalBytes: Long, speedBytesPerSec: Long) {}
    override fun notifyStateChanged(state: TransferEngineState) {
        _serviceState.value = _serviceState.value.copy(engineState = state)
    }
    override fun notifyCompleted(transferId: String, finalSha256: String) {
        _serviceState.value = _serviceState.value.copy(finalSha256 = finalSha256, isRunning = false)
    }
    override fun notifyError(errorCode: String, message: String) {
        _serviceState.value = _serviceState.value.copy(errorCode = errorCode, errorMessage = message, isRunning = false)
    }
    override fun requestPause() { requestPauseCalled = true }
    override fun requestResume() { requestResumeCalled = true }
    override fun requestCancel() { requestCancelCalled = true }
    override fun reset() { _serviceState.value = TransferServiceState() }
}

class TransferCoordinatorTest {

    private lateinit var networkMonitor: FakeNetworkMonitor
    private lateinit var transferRepository: FakeTransferRepository
    private lateinit var serviceController: FakeServiceController
    private lateinit var lanEngine: LanTransferEngine
    private lateinit var relayEngine: RelayClientEngine
    private lateinit var keyManager: SoftwareKeyManager
    private lateinit var coordinator: TransferCoordinator

    @Before
    fun setUp() {
        networkMonitor = FakeNetworkMonitor()
        transferRepository = FakeTransferRepository()
        serviceController = FakeServiceController()
        lanEngine = LanTransferEngine()
        relayEngine = RelayClientEngine()
        keyManager = SoftwareKeyManager()

        coordinator = TransferCoordinator(
            lanEngine = lanEngine,
            relayEngine = relayEngine,
            networkMonitor = networkMonitor,
            transferRepository = transferRepository,
            serviceController = serviceController,
            keyManager = keyManager,
            ioDispatcher = Dispatchers.Unconfined
        )
    }

    @Test
    fun testSelectTransferChannelPrefersLanOnSameWifi() {
        networkMonitor.updateState(
            NetworkState(type = NetworkType.WIFI_SAME_LAN, isConnected = true)
        )
        val channel = coordinator.selectTransferChannel("192.168.1.50")
        assertEquals(TransferChannel.DIRECT_LAN, channel)
    }

    @Test
    fun testSelectTransferChannelFallsBackToRelayOnDifferentNetwork() {
        networkMonitor.updateState(
            NetworkState(type = NetworkType.CELLULAR, isConnected = true)
        )
        val channel = coordinator.selectTransferChannel("192.168.1.50")
        assertEquals(TransferChannel.RELAY_E2EE, channel)
    }

    @Test
    fun testSelectTransferChannelFallsBackToRelayWhenPeerIpMissing() {
        networkMonitor.updateState(
            NetworkState(type = NetworkType.WIFI_SAME_LAN, isConnected = true)
        )
        val channel = coordinator.selectTransferChannel(null)
        assertEquals(TransferChannel.RELAY_E2EE, channel)
    }

    @Test
    fun testPauseAndResumeSession() = runBlocking {
        val session = TransferSession(
            sessionId = "sess_001",
            direction = TransferDirection.SENDER,
            totalBytes = 1024L
        )
        transferRepository.createSession(session)

        coordinator.pauseSession("sess_001")

        assertTrue(serviceController.requestPauseCalled)
        assertEquals(SessionStatus.PAUSED, transferRepository.getSessionById("sess_001")?.status)

        coordinator.resumeSession("sess_001")

        assertTrue(serviceController.requestResumeCalled)
        assertEquals(SessionStatus.TRANSFERRING, transferRepository.getSessionById("sess_001")?.status)
    }

    @Test
    fun testCancelSession() = runBlocking {
        val session = TransferSession(
            sessionId = "sess_002",
            direction = TransferDirection.RECEIVER,
            totalBytes = 2048L
        )
        transferRepository.createSession(session)

        coordinator.cancelSession("sess_002")

        assertTrue(serviceController.requestCancelCalled)
        assertEquals(SessionStatus.CANCELLED, transferRepository.getSessionById("sess_002")?.status)
    }

    @Test
    fun testRegisterAndGetActiveSession() {
        val session = TransferSession(
            sessionId = "sess_active_100",
            direction = TransferDirection.SENDER,
            totalBytes = 4096L
        )
        coordinator.registerActiveSession(session)

        val retrieved = coordinator.getActiveSession("sess_active_100")
        assertNotNull(retrieved)
        assertEquals("sess_active_100", retrieved?.sessionId)
        assertEquals(4096L, retrieved?.totalBytes)

        assertNull(coordinator.getActiveSession("non_existent_session"))
    }
}
