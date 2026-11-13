package com.filetransfer.app.presentation.viewmodel

import com.filetransfer.app.core.platform.network.INetworkMonitor
import com.filetransfer.app.core.platform.network.NetworkState
import com.filetransfer.app.core.platform.network.NetworkType
import com.filetransfer.app.core.platform.service.ITransferServiceController
import com.filetransfer.app.core.platform.service.TransferServiceState
import com.filetransfer.app.core.security.keystore.SoftwareKeyManager
import com.filetransfer.app.core.transfer.TransferCoordinator
import com.filetransfer.app.core.transfer.lan.LanTransferEngine
import com.filetransfer.app.core.transfer.lan.TransferEngineState
import com.filetransfer.app.core.transfer.lan.TransferProgressCallback
import com.filetransfer.app.core.transfer.relay.RelayClientEngine
import com.filetransfer.app.data.local.db.dao.InMemoryAuditLogDao
import com.filetransfer.app.data.local.db.dao.InMemoryTransferSessionDao
import com.filetransfer.app.data.repository.AuditRepositoryImpl
import com.filetransfer.app.data.repository.TransferRepositoryImpl
import com.filetransfer.app.domain.model.TransferChannel
import com.filetransfer.app.domain.model.TransferProgress
import com.filetransfer.app.domain.usecase.transfer.ControlSessionUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class FakeMonitor : INetworkMonitor {
    private val _networkState = MutableStateFlow(NetworkState(type = NetworkType.WIFI_SAME_LAN, isConnected = true))
    override val networkState: StateFlow<NetworkState> = _networkState.asStateFlow()
    override fun getCurrentNetworkType(): NetworkType = _networkState.value.type
    override fun getCurrentNetworkState(): NetworkState = _networkState.value
    override fun isOnline(): Boolean = _networkState.value.isConnected
    override fun startMonitoring() {}
    override fun stopMonitoring() {}
}

class FakeController : ITransferServiceController {
    private val _serviceState = MutableStateFlow(TransferServiceState())
    override val serviceState: StateFlow<TransferServiceState> = _serviceState.asStateFlow()
    override fun registerCallback(callback: TransferProgressCallback) {}
    override fun unregisterCallback(callback: TransferProgressCallback) {}
    override fun startSession(sessionId: String, documentName: String, totalBytes: Long, isLan: Boolean) {}
    override fun notifyProgress(bytesTransferred: Long, totalBytes: Long, speedBytesPerSec: Long) {}
    override fun notifyStateChanged(state: TransferEngineState) {}
    override fun notifyCompleted(docId: String, finalSha256: String) {}
    override fun notifyError(errorCode: String, message: String) {}
    override fun requestPause() {}
    override fun requestResume() {}
    override fun requestCancel() {}
    override fun reset() {}
}

@OptIn(ExperimentalCoroutinesApi::class)
class TransferViewModelTest {

    private lateinit var transferRepository: TransferRepositoryImpl
    private lateinit var auditRepository: AuditRepositoryImpl
    private lateinit var coordinator: TransferCoordinator
    private lateinit var controlSessionUseCase: ControlSessionUseCase
    private lateinit var viewModel: TransferViewModel

    @Before
    fun setUp() {
        val sessionDao = InMemoryTransferSessionDao()
        val auditDao = InMemoryAuditLogDao()
        transferRepository = TransferRepositoryImpl(sessionDao)
        auditRepository = AuditRepositoryImpl(auditDao)

        coordinator = TransferCoordinator(
            lanEngine = LanTransferEngine(),
            relayEngine = RelayClientEngine(),
            networkMonitor = FakeMonitor(),
            transferRepository = transferRepository,
            serviceController = FakeController(),
            keyManager = SoftwareKeyManager(),
            ioDispatcher = Dispatchers.Unconfined
        )

        controlSessionUseCase = ControlSessionUseCase(
            transferCoordinator = coordinator,
            transferRepository = transferRepository,
            auditRepository = auditRepository
        )

        viewModel = TransferViewModel(
            controlSessionUseCase = controlSessionUseCase,
            transferCoordinator = coordinator,
            openFileSecurelyUseCase = null,
            customScope = CoroutineScope(Dispatchers.Unconfined)
        )
    }

    @Test
    fun testAttachSession_updatesSessionInfoAndPeerModel() {
        viewModel.attachSession(
            sessionId = "session_xyz",
            fileName = "data.zip",
            peerDeviceModel = "Xiaomi Redmi Turbo 4 Pro"
        )

        val state = viewModel.uiState.value
        assertEquals("session_xyz", state.sessionId)
        assertEquals("data.zip", state.fileName)
        assertEquals("Xiaomi Redmi Turbo 4 Pro", state.peerDeviceModel)
    }

    @Test
    fun testSetPeerDeviceModel_updatesState() {
        viewModel.setPeerDeviceModel("Samsung Galaxy S24 Ultra")
        assertEquals("Samsung Galaxy S24 Ultra", viewModel.uiState.value.peerDeviceModel)
    }

    @Test
    fun testSetCompletedFilePath_updatesState() {
        viewModel.setCompletedFilePath("/data/user/0/app/files/doc.pdf")
        assertEquals("/data/user/0/app/files/doc.pdf", viewModel.uiState.value.completedFilePath)
    }

    @Test
    fun testObserveProgress_collectsAndUpdatesState() = runBlocking {
        val progress = TransferProgress(
            sessionId = "sess_p1",
            bytesTransferred = 5 * 1024 * 1024L,
            totalBytes = 10 * 1024 * 1024L,
            progressPercent = 0.5f,
            speedBytesPerSec = 25 * 1024 * 1024L,
            channel = TransferChannel.DIRECT_LAN,
            state = TransferEngineState.TRANSFERRING
        )

        val progressFlow = flowOf(progress)
        viewModel.observeProgress(progressFlow, fileName = "video.mp4")

        val state = viewModel.uiState.value
        assertEquals("sess_p1", state.sessionId)
        assertEquals("video.mp4", state.fileName)
        assertEquals(0.5f, state.progressPercent, 0.001f)
        assertEquals(5 * 1024 * 1024L, state.transferredBytes)
        assertEquals(10 * 1024 * 1024L, state.totalBytes)
        assertEquals(TransferEngineState.TRANSFERRING, state.state)
    }

    @Test
    fun testPauseResumeCancelSession_updatesStateCorrectly() = runBlocking {
        viewModel.attachSession("sess_cmd")

        viewModel.pauseSession("sess_cmd")
        assertEquals(TransferEngineState.PAUSED, viewModel.uiState.value.state)

        viewModel.resumeSession("sess_cmd")
        assertEquals(TransferEngineState.TRANSFERRING, viewModel.uiState.value.state)

        viewModel.cancelSession("sess_cmd")
        assertEquals(TransferEngineState.CANCELLED, viewModel.uiState.value.state)
    }

    @Test
    fun testOnTransferFinished_withDirectory_populatesReceivedFileList() {
        val tempDir = java.io.File.createTempFile("test_batch", "").apply {
            delete()
            mkdirs()
        }
        java.io.File(tempDir, "img1.png").apply { writeText("image 1") }
        java.io.File(tempDir, "doc.pdf").apply { writeText("document text") }

        viewModel.onTransferFinished(tempDir)

        val state = viewModel.uiState.value
        assertEquals(TransferEngineState.COMPLETED, state.state)
        assertTrue(state.isDirectoryOrBatch)
        assertEquals(2, state.receivedFileList.size)
        assertEquals("doc.pdf", state.receivedFileList[0].name)
        assertEquals("img1.png", state.receivedFileList[1].name)

        tempDir.deleteRecursively()
    }
}
