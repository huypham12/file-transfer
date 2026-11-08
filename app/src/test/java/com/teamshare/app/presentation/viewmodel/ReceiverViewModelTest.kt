package com.teamshare.app.presentation.viewmodel

import com.teamshare.app.core.platform.storage.IStorageAuditor
import com.teamshare.app.core.platform.storage.StorageAuditResult
import com.teamshare.app.data.local.db.dao.InMemoryAuditLogDao
import com.teamshare.app.data.repository.AuditRepositoryImpl
import com.teamshare.app.domain.model.SessionQrPayload
import com.teamshare.app.domain.model.TransferChannel
import com.teamshare.app.domain.model.TransferType
import com.teamshare.app.domain.usecase.transfer.CheckReceiverStorageUseCase
import com.teamshare.app.domain.usecase.transfer.ScanQrTokenUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class ReceiverViewModelTest {

    private lateinit var auditRepository: AuditRepositoryImpl
    private lateinit var scanQrTokenUseCase: ScanQrTokenUseCase

    class FakeStorageAuditor(private val mockAvailableBytes: Long) : IStorageAuditor {
        override fun getAvailableBytes(targetDir: File?): Long = mockAvailableBytes
        override fun getTotalBytes(targetDir: File?): Long = 100L * 1024 * 1024 * 1024
        override fun hasSufficientSpace(requiredBytes: Long, targetDir: File?): Boolean {
            return mockAvailableBytes >= (requiredBytes + 50 * 1024 * 1024L)
        }
        override fun auditStorage(requiredBytes: Long, targetDir: File?): StorageAuditResult {
            val safetyMargin = 50 * 1024 * 1024L
            val totalNeeded = requiredBytes + safetyMargin
            val isSufficient = mockAvailableBytes >= totalNeeded
            val shortfall = if (isSufficient) 0L else totalNeeded - mockAvailableBytes
            return StorageAuditResult(
                availableBytes = mockAvailableBytes,
                totalBytes = 100L * 1024 * 1024 * 1024,
                requiredBytes = requiredBytes,
                safetyMarginBytes = safetyMargin,
                isSufficient = isSufficient,
                shortfallBytes = shortfall
            )
        }
    }

    @Before
    fun setUp() {
        val auditDao = InMemoryAuditLogDao()
        auditRepository = AuditRepositoryImpl(auditDao)
        scanQrTokenUseCase = ScanQrTokenUseCase(auditRepository)
    }

    private fun createValidQrPayload(fileSize: Long = 10 * 1024 * 1024L, isExpired: Boolean = false): SessionQrPayload {
        val ttl = if (isExpired) -60000L else 300000L
        return SessionQrPayload(
            sessionId = "sess_test_123",
            sessionToken = "token_secret_abc",
            senderIp = "192.168.1.50",
            senderPort = 8889,
            channel = TransferChannel.DIRECT_LAN,
            transferType = TransferType.SINGLE_FILE,
            totalBytes = fileSize,
            itemCount = 1,
            senderDeviceModel = "Xiaomi Redmi Turbo 4 Pro",
            expiresAt = System.currentTimeMillis() + ttl
        )
    }

    @Test
    fun testOnQrCodeScanned_validQrAndSufficientStorage_success() = kotlinx.coroutines.runBlocking {
        val storageAuditor = FakeStorageAuditor(mockAvailableBytes = 1024 * 1024 * 1024L) // 1GB
        val checkStorageUseCase = CheckReceiverStorageUseCase(storageAuditor, auditRepository)

        val viewModel = ReceiverViewModel(
            scanQrTokenUseCase = scanQrTokenUseCase,
            checkReceiverStorageUseCase = checkStorageUseCase,
            customScope = CoroutineScope(Dispatchers.Unconfined)
        )

        val payload = createValidQrPayload(fileSize = 20 * 1024 * 1024L) // 20MB
        val rawQr = payload.toQrString()

        var callbackCalled = false
        viewModel.onQrCodeScanned(rawQr, onValidQr = { callbackCalled = true }).join()

        val state = viewModel.uiState.value
        assertFalse(state.isScanning)
        assertNotNull(state.qrPayload)
        assertEquals("sess_test_123", state.qrPayload?.sessionId)
        assertTrue(state.isStorageSufficient)
        assertEquals(0L, state.shortfallBytes)
        assertNull(state.errorMessage)
        assertTrue(callbackCalled)
    }

    @Test
    fun testOnQrCodeScanned_expiredQr_failsWith0x103Error() = kotlinx.coroutines.runBlocking {
        val storageAuditor = FakeStorageAuditor(mockAvailableBytes = 500 * 1024 * 1024L)
        val checkStorageUseCase = CheckReceiverStorageUseCase(storageAuditor, auditRepository)

        val viewModel = ReceiverViewModel(
            scanQrTokenUseCase = scanQrTokenUseCase,
            checkReceiverStorageUseCase = checkStorageUseCase,
            customScope = CoroutineScope(Dispatchers.Unconfined)
        )

        val expiredPayload = createValidQrPayload(isExpired = true)
        val rawQr = expiredPayload.toQrString()

        var callbackCalled = false
        viewModel.onQrCodeScanned(rawQr, onValidQr = { callbackCalled = true }).join()

        val state = viewModel.uiState.value
        assertFalse(callbackCalled)
        assertNull(state.qrPayload)
        assertNotNull(state.errorMessage)
        assertTrue(state.errorMessage!!.contains("0x103"))
    }

    @Test
    fun testOnQrCodeScanned_insufficientStorage_flagsStorageNotSufficient() = kotlinx.coroutines.runBlocking {
        // Chỉ có 10MB trống, nhưng yêu cầu 100MB + 50MB đệm = 150MB
        val storageAuditor = FakeStorageAuditor(mockAvailableBytes = 10 * 1024 * 1024L)
        val checkStorageUseCase = CheckReceiverStorageUseCase(storageAuditor, auditRepository)

        val viewModel = ReceiverViewModel(
            scanQrTokenUseCase = scanQrTokenUseCase,
            checkReceiverStorageUseCase = checkStorageUseCase,
            customScope = CoroutineScope(Dispatchers.Unconfined)
        )

        val payload = createValidQrPayload(fileSize = 100 * 1024 * 1024L)
        val rawQr = payload.toQrString()

        viewModel.onQrCodeScanned(rawQr).join()

        val state = viewModel.uiState.value
        assertFalse(state.isScanning)
        assertNotNull(state.qrPayload)
        assertFalse(state.isStorageSufficient)
        assertTrue(state.shortfallBytes > 0)
    }

    @Test
    fun testRequestConnection_insufficientStorage_blocksConnection() = kotlinx.coroutines.runBlocking {
        val storageAuditor = FakeStorageAuditor(mockAvailableBytes = 5 * 1024 * 1024L)
        val checkStorageUseCase = CheckReceiverStorageUseCase(storageAuditor, auditRepository)

        val viewModel = ReceiverViewModel(
            scanQrTokenUseCase = scanQrTokenUseCase,
            checkReceiverStorageUseCase = checkStorageUseCase,
            customScope = CoroutineScope(Dispatchers.Unconfined)
        )

        val payload = createValidQrPayload(fileSize = 50 * 1024 * 1024L)
        viewModel.onQrCodeScanned(payload.toQrString()).join()
        assertFalse(viewModel.uiState.value.isStorageSufficient)

        viewModel.requestConnection(targetDir = File("mock_dir"))

        val state = viewModel.uiState.value
        assertEquals(ReceiverConnectionState.IDLE, state.connectionState)
        assertNotNull(state.errorMessage)
        assertTrue(state.errorMessage!!.contains("không đủ bộ nhớ"))
    }

    @Test
    fun testCancelDownload_resetsScannerState() = kotlinx.coroutines.runBlocking {
        val storageAuditor = FakeStorageAuditor(mockAvailableBytes = 500 * 1024 * 1024L)
        val checkStorageUseCase = CheckReceiverStorageUseCase(storageAuditor, auditRepository)

        val viewModel = ReceiverViewModel(
            scanQrTokenUseCase = scanQrTokenUseCase,
            checkReceiverStorageUseCase = checkStorageUseCase,
            customScope = CoroutineScope(Dispatchers.Unconfined)
        )

        val payload = createValidQrPayload()
        viewModel.onQrCodeScanned(payload.toQrString()).join()
        assertFalse(viewModel.uiState.value.isScanning)

        viewModel.cancelDownload()

        val state = viewModel.uiState.value
        assertTrue(state.isScanning)
        assertNull(state.qrPayload)
        assertEquals(ReceiverConnectionState.IDLE, state.connectionState)
        assertNull(state.errorMessage)
    }
}
