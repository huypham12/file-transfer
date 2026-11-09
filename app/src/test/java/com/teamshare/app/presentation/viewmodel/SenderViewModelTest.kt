package com.teamshare.app.presentation.viewmodel

import com.teamshare.app.core.platform.device.DeviceIdentityManager
import com.teamshare.app.data.local.db.dao.InMemoryAuditLogDao
import com.teamshare.app.data.local.db.dao.InMemoryTransferSessionDao
import com.teamshare.app.data.repository.AuditRepositoryImpl
import com.teamshare.app.data.repository.TransferRepositoryImpl
import com.teamshare.app.domain.model.ApprovalMode
import com.teamshare.app.domain.model.DeviceProfile
import com.teamshare.app.domain.usecase.transfer.CancelTransferSessionUseCase
import com.teamshare.app.domain.usecase.transfer.CreateSenderSessionUseCase
import com.teamshare.app.domain.usecase.transfer.ManageApprovalModeUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream

@OptIn(ExperimentalCoroutinesApi::class)
class SenderViewModelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var transferRepository: TransferRepositoryImpl
    private lateinit var auditRepository: AuditRepositoryImpl
    private lateinit var deviceIdentityManager: DeviceIdentityManager

    private lateinit var createSenderSessionUseCase: CreateSenderSessionUseCase
    private lateinit var manageApprovalModeUseCase: ManageApprovalModeUseCase
    private lateinit var cancelTransferSessionUseCase: CancelTransferSessionUseCase

    private lateinit var viewModel: SenderViewModel

    @Before
    fun setUp() {
        val sessionDao = InMemoryTransferSessionDao()
        val auditDao = InMemoryAuditLogDao()
        transferRepository = TransferRepositoryImpl(sessionDao)
        auditRepository = AuditRepositoryImpl(auditDao)

        deviceIdentityManager = DeviceIdentityManager()
        deviceIdentityManager.setOverrideProfile(
            DeviceProfile(
                manufacturer = "Xiaomi",
                model = "Redmi Turbo 4 Pro",
                fingerprint = "fp_turbo4"
            )
        )

        createSenderSessionUseCase = CreateSenderSessionUseCase(
            transferRepository = transferRepository,
            deviceIdentityManager = deviceIdentityManager,
            auditRepository = auditRepository
        )
        manageApprovalModeUseCase = ManageApprovalModeUseCase(
            transferRepository = transferRepository,
            auditRepository = auditRepository
        )
        cancelTransferSessionUseCase = CancelTransferSessionUseCase(
            transferRepository = transferRepository,
            auditRepository = auditRepository
        )

        viewModel = SenderViewModel(
            createSenderSessionUseCase = createSenderSessionUseCase,
            manageApprovalModeUseCase = manageApprovalModeUseCase,
            cancelTransferSessionUseCase = cancelTransferSessionUseCase,
            customScope = CoroutineScope(Dispatchers.Unconfined)
        )
    }

    private fun createSampleFile(name: String, sizeBytes: Int): File {
        val file = tempFolder.newFile(name)
        FileOutputStream(file).use { fos ->
            val buf = ByteArray(1024) { 0x41 }
            var written = 0
            while (written < sizeBytes) {
                val toWrite = minOf(buf.size, sizeBytes - written)
                fos.write(buf, 0, toWrite)
                written += toWrite
            }
        }
        return file
    }

    @Test
    fun testOnFilesSelected_calculatesTotalBytes() {
        val file1 = createSampleFile("file1.dat", 1024)
        val file2 = createSampleFile("file2.dat", 2048)

        viewModel.onFilesSelected(listOf(file1, file2))

        val state = viewModel.uiState.value
        assertEquals(2, state.selectedFiles.size)
        assertEquals(3072L, state.totalBytes)
        assertNull(state.errorMessage)
    }

    @Test
    fun testRemoveSelectedFile_updatesListAndTotalBytes() {
        val file1 = createSampleFile("doc1.txt", 1000)
        val file2 = createSampleFile("doc2.txt", 2000)

        viewModel.onFilesSelected(listOf(file1, file2))
        assertEquals(3000L, viewModel.uiState.value.totalBytes)

        viewModel.removeSelectedFile(file1)

        val state = viewModel.uiState.value
        assertEquals(1, state.selectedFiles.size)
        assertEquals("doc2.txt", state.selectedFiles[0].name)
        assertEquals(2000L, state.totalBytes)
    }

    @Test
    fun testClearSelectedFiles_clearsAll() {
        val file = createSampleFile("test.bin", 512)
        viewModel.onFilesSelected(listOf(file))
        assertEquals(1, viewModel.uiState.value.selectedFiles.size)

        viewModel.clearSelectedFiles()

        val state = viewModel.uiState.value
        assertTrue(state.selectedFiles.isEmpty())
        assertEquals(0L, state.totalBytes)
    }

    @Test
    fun testSetApprovalMode_updatesState() {
        assertEquals(ApprovalMode.MANUAL_APPROVAL, viewModel.uiState.value.approvalMode)

        viewModel.setApprovalMode(ApprovalMode.AUTO_ACCEPT)
        assertEquals(ApprovalMode.AUTO_ACCEPT, viewModel.uiState.value.approvalMode)

        viewModel.setApprovalMode(ApprovalMode.MANUAL_APPROVAL)
        assertEquals(ApprovalMode.MANUAL_APPROVAL, viewModel.uiState.value.approvalMode)
    }

    @Test
    fun testStartSharingSession_emptyList_setsErrorMessage() {
        viewModel.clearSelectedFiles()
        viewModel.startSharingSession()

        val state = viewModel.uiState.value
        assertFalse(state.isSharing)
        assertNotNull(state.errorMessage)
    }

    @Test
    fun testStartSharingSession_success_createsSessionAndQrPayload() = runBlocking {
        val file = createSampleFile("presentation.pdf", 4096)
        viewModel.onFilesSelected(listOf(file))

        var createdSessionId: String? = null
        val job = viewModel.startSharingSession(
            senderIp = "192.168.1.15",
            senderPort = 8889,
            onSessionCreated = { sessionId ->
                createdSessionId = sessionId
            }
        )
        job.join()

        val state = viewModel.uiState.value
        assertTrue(state.isSharing)
        assertTrue(state.sessionId.isNotBlank())
        assertEquals(state.sessionId, createdSessionId)
        assertNotNull(state.qrPayload)
        assertEquals("192.168.1.15", state.qrPayload?.senderIp)
        assertEquals(8889, state.qrPayload?.senderPort)
        assertNull(state.errorMessage)
    }

    @Test
    fun testDeviceConnectionRequested_manualMode_setsPendingApprovalDevice() {
        viewModel.setApprovalMode(ApprovalMode.MANUAL_APPROVAL)
        val incomingDevice = DeviceProfile(
            manufacturer = "Samsung",
            model = "Galaxy S23",
            fingerprint = "fp_samsung_s23"
        )

        viewModel.onDeviceConnectionRequested(incomingDevice)

        val state = viewModel.uiState.value
        assertNotNull(state.pendingApprovalDevice)
        assertEquals("Galaxy S23", state.pendingApprovalDevice?.model)
        assertTrue(state.connectedDevices.isEmpty())
    }

    @Test
    fun testDeviceConnectionRequested_autoMode_automaticallyAcceptsDevice() {
        viewModel.setApprovalMode(ApprovalMode.AUTO_ACCEPT)
        val incomingDevice = DeviceProfile(
            manufacturer = "Google",
            model = "Pixel 8 Pro",
            fingerprint = "fp_pixel8"
        )

        viewModel.onDeviceConnectionRequested(incomingDevice)

        val state = viewModel.uiState.value
        assertNull(state.pendingApprovalDevice)
        assertEquals(1, state.connectedDevices.size)
        assertEquals("Pixel 8 Pro", state.connectedDevices[0].model)
    }

    @Test
    fun testApprovePendingDevice_accepted_addsToConnectedList() {
        viewModel.setApprovalMode(ApprovalMode.MANUAL_APPROVAL)
        val device = DeviceProfile(
            manufacturer = "Sony",
            model = "Xperia 1 VI",
            fingerprint = "fp_xperia"
        )
        viewModel.onDeviceConnectionRequested(device)
        assertNotNull(viewModel.uiState.value.pendingApprovalDevice)

        // Approve
        viewModel.approvePendingDevice(device, accept = true)

        val state = viewModel.uiState.value
        assertNull(state.pendingApprovalDevice)
        assertEquals(1, state.connectedDevices.size)
        assertEquals("Xperia 1 VI", state.connectedDevices[0].model)
    }

    @Test
    fun testApprovePendingDevice_rejected_doesNotAddToList() {
        viewModel.setApprovalMode(ApprovalMode.MANUAL_APPROVAL)
        val device = DeviceProfile(
            manufacturer = "Unknown",
            model = "Malicious Device",
            fingerprint = "fp_bad"
        )
        viewModel.onDeviceConnectionRequested(device)

        // Reject
        viewModel.approvePendingDevice(device, accept = false)

        val state = viewModel.uiState.value
        assertNull(state.pendingApprovalDevice)
        assertTrue(state.connectedDevices.isEmpty())
    }

    @Test
    fun testApprovalCache_clearedOnStopSharingSession() = runBlocking {
        viewModel.setApprovalMode(ApprovalMode.MANUAL_APPROVAL)
        val device = DeviceProfile(
            manufacturer = "Xiaomi",
            model = "Redmi Turbo 4 Pro",
            fingerprint = "fp_turbo_4"
        )
        viewModel.onDeviceConnectionRequested(device)
        viewModel.approvePendingDevice(device, accept = true)
        assertEquals(1, viewModel.uiState.value.connectedDevices.size)

        viewModel.stopSharingSession().join()
        assertTrue(viewModel.uiState.value.connectedDevices.isEmpty())
    }

    @Test
    fun testStopSharingSession_cleansUpState() = runBlocking {
        val file = createSampleFile("temp.bin", 1024)
        viewModel.onFilesSelected(listOf(file))
        viewModel.startSharingSession().join()
        assertTrue(viewModel.uiState.value.isSharing)

        var stoppedCalled = false
        viewModel.stopSharingSession { stoppedCalled = true }.join()

        val state = viewModel.uiState.value
        assertFalse(state.isSharing)
        assertEquals("", state.sessionId)
        assertNull(state.qrPayload)
        assertNull(state.pendingApprovalDevice)
        assertTrue(state.connectedDevices.isEmpty())
        assertTrue(stoppedCalled)
    }
}
