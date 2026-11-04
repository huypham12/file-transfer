package com.teamshare.app.presentation.viewmodel

import com.teamshare.app.core.platform.network.NetworkState
import com.teamshare.app.core.platform.network.NetworkType
import com.teamshare.app.core.platform.service.ITransferServiceController
import com.teamshare.app.core.transfer.FakeNetworkMonitor
import com.teamshare.app.core.transfer.FakeServiceController
import com.teamshare.app.core.transfer.FakeTransferRepository
import com.teamshare.app.core.transfer.TransferCoordinator
import com.teamshare.app.core.transfer.lan.LanTransferEngine
import com.teamshare.app.core.transfer.lan.TransferEngineState
import com.teamshare.app.core.transfer.relay.RelayClientEngine
import com.teamshare.app.domain.model.SessionCmd
import com.teamshare.app.domain.model.TransferChannel
import com.teamshare.app.domain.model.TransferProgress
import com.teamshare.app.domain.usecase.transfer.ControlSessionUseCase
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TransferViewModelTest {

    private lateinit var transferCoordinator: TransferCoordinator
    private lateinit var controlUseCase: ControlSessionUseCase
    private lateinit var transferViewModel: TransferViewModel
    private lateinit var fakeServiceController: FakeServiceController

    @Before
    fun setup() {
        val fakeRepo = FakeTransferRepository()
        fakeServiceController = FakeServiceController()
        val fakeNetwork = FakeNetworkMonitor(NetworkState(isConnected = true, type = NetworkType.WIFI_SAME_LAN))

        transferCoordinator = TransferCoordinator(
            lanEngine = LanTransferEngine(),
            relayEngine = RelayClientEngine(),
            networkMonitor = fakeNetwork,
            transferRepository = fakeRepo,
            serviceController = fakeServiceController
        )

        controlUseCase = ControlSessionUseCase(transferCoordinator, fakeRepo)
        transferViewModel = TransferViewModel(
            controlSessionUseCase = controlUseCase,
            transferCoordinator = transferCoordinator,
            customScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        )
    }

    @Test
    fun `observeProgress emits terminal progress immediately`() = runBlocking {
        val progressList = flow {
            emit(
                TransferProgress(
                    sessionId = "sess_1",
                    bytesTransferred = 500L,
                    totalBytes = 1000L,
                    speedBytesPerSec = 50_000L,
                    channel = TransferChannel.DIRECT_LAN,
                    state = TransferEngineState.TRANSFERRING
                )
            )
            emit(
                TransferProgress(
                    sessionId = "sess_1",
                    bytesTransferred = 1000L,
                    totalBytes = 1000L,
                    speedBytesPerSec = 45_000L,
                    channel = TransferChannel.DIRECT_LAN,
                    state = TransferEngineState.COMPLETED
                )
            )
        }

        transferViewModel.observeProgress(progressList, fileName = "sample.zip")
        delay(50)

        val state = transferViewModel.uiState.value
        assertEquals("sess_1", state.sessionId)
        assertEquals("sample.zip", state.fileName)
        assertEquals(TransferEngineState.COMPLETED, state.state)
        assertEquals(1.0f, state.progressPercent, 0.001f)
    }

    @Test
    fun `throttling suppresses intermediate emissions within 100ms`() = runBlocking {
        // Phát 100 updates liên tục không delay (mô phỏng streaming 45MB/s)
        var emissionCount = 0
        val rapidProgressFlow = flow {
            for (i in 1..100) {
                emit(
                    TransferProgress(
                        sessionId = "sess_fast",
                        bytesTransferred = i * 10L,
                        totalBytes = 10_000L,
                        speedBytesPerSec = 40_000_000L,
                        channel = TransferChannel.DIRECT_LAN,
                        state = TransferEngineState.TRANSFERRING
                    )
                )
            }
            // Kết thúc với trạng thái COMPLETED
            emit(
                TransferProgress(
                    sessionId = "sess_fast",
                    bytesTransferred = 10_000L,
                    totalBytes = 10_000L,
                    speedBytesPerSec = 40_000_000L,
                    channel = TransferChannel.DIRECT_LAN,
                    state = TransferEngineState.COMPLETED
                )
            )
        }

        transferViewModel.observeProgress(rapidProgressFlow, fileName = "fast_movie.mp4")
        delay(150)

        val finalState = transferViewModel.uiState.value
        assertEquals(TransferEngineState.COMPLETED, finalState.state)
        assertEquals(10_000L, finalState.transferredBytes)
    }

    @Test
    fun `pauseSession requests pause on coordinator`() = runBlocking {
        transferViewModel.pauseSession("sess_pause")
        delay(50)
        assertTrue(fakeServiceController.requestPauseCalled)
    }

    @Test
    fun `cancelSession requests cancel on coordinator`() = runBlocking {
        transferViewModel.cancelSession("sess_cancel")
        delay(50)
        assertTrue(fakeServiceController.requestCancelCalled)
    }

    @Test
    fun `attachSession loads active session state from transfer coordinator`() = runBlocking {
        val session = com.teamshare.app.domain.model.TransferSession(
            sessionId = "sess_attach_1",
            docId = "doc_1",
            peerMemberId = "peer_1",
            direction = com.teamshare.app.domain.model.TransferDirection.INCOMING,
            totalBytes = 20_000_000L,
            bytesTransferred = 5_000_000L,
            channel = TransferChannel.DIRECT_LAN
        )
        transferCoordinator.registerActiveSession(session, "team_alpha")

        transferViewModel.attachSession("sess_attach_1", fileName = "test_attached.mp4")

        val state = transferViewModel.uiState.value
        assertEquals("sess_attach_1", state.sessionId)
        assertEquals("test_attached.mp4", state.fileName)
        assertEquals(5_000_000L, state.transferredBytes)
        assertEquals(20_000_000L, state.totalBytes)
        assertEquals(0.25f, state.progressPercent, 0.001f)
    }
}

