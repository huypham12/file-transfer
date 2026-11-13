package com.filetransfer.app.presentation.di

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.filetransfer.app.core.platform.device.DeviceIdentityManager
import com.filetransfer.app.core.platform.network.AndroidNetworkMonitor
import com.filetransfer.app.core.platform.service.TransferServiceController
import com.filetransfer.app.core.platform.storage.StatFsStorageAuditor
import com.filetransfer.app.core.security.biometric.BiometricAuthManager
import com.filetransfer.app.core.security.keystore.AndroidKeystoreKeyManager
import com.filetransfer.app.core.storage.saf.FileProviderGateway
import com.filetransfer.app.core.storage.saf.StorageDirectoryManager
import com.filetransfer.app.core.transfer.TransferCoordinator
import com.filetransfer.app.core.transfer.lan.LanTransferEngine
import com.filetransfer.app.core.transfer.relay.RelayClientEngine
import com.filetransfer.app.data.local.db.AppDatabase
import com.filetransfer.app.data.repository.AuditRepositoryImpl
import com.filetransfer.app.data.repository.TransferRepositoryImpl
import com.filetransfer.app.domain.usecase.audit.QuerySecurityAuditUseCase
import com.filetransfer.app.domain.usecase.device.GetDeviceIdentityUseCase
import com.filetransfer.app.domain.usecase.platform.OpenFileSecurelyUseCase
import com.filetransfer.app.domain.usecase.transfer.CancelTransferSessionUseCase
import com.filetransfer.app.domain.usecase.transfer.CheckReceiverStorageUseCase
import com.filetransfer.app.domain.usecase.transfer.ControlSessionUseCase
import com.filetransfer.app.domain.usecase.transfer.CreateSenderSessionUseCase
import com.filetransfer.app.domain.usecase.transfer.ManageApprovalModeUseCase
import com.filetransfer.app.domain.usecase.transfer.RequestConnectionUseCase
import com.filetransfer.app.domain.usecase.transfer.ScanQrTokenUseCase
import com.filetransfer.app.presentation.viewmodel.AuditViewModel
import com.filetransfer.app.presentation.viewmodel.HistoryViewModel
import com.filetransfer.app.presentation.viewmodel.ReceiverViewModel
import com.filetransfer.app.presentation.viewmodel.SenderViewModel
import com.filetransfer.app.presentation.viewmodel.TransferViewModel

/**
 * Nhà máy cấp phát ViewModels (Dependency Injection) cho tầng Presentation.
 * Khởi tạo trễ (Lazy initialization) các thành phần tinh gọn phục vụ Secure Local Drop.
 * Cung cấp đầy đủ cả 4 ViewModels: Sender, Receiver, Transfer, Audit theo Stage 7.
 */
class ViewModelFactory(private val context: Context) : ViewModelProvider.Factory {

    private val keyManager by lazy { AndroidKeystoreKeyManager(context) }
    private val database by lazy { AppDatabase.getInstance(context, keyManager) }

    private val transferRepo by lazy { TransferRepositoryImpl(database.transferSessionDao()) }
    private val auditRepo by lazy { AuditRepositoryImpl(database.auditDao(), database.transferSessionDao()) }

    private val dirManager by lazy { StorageDirectoryManager(context) }
    private val networkMonitor by lazy { AndroidNetworkMonitor(context) }

    private val lanEngine by lazy { LanTransferEngine(sessionDao = database.transferSessionDao()) }
    private val relayEngine by lazy { RelayClientEngine(sessionDao = database.transferSessionDao()) }
    private val serviceLauncher by lazy {
        com.filetransfer.app.core.platform.service.TransferForegroundServiceLauncherImpl(context)
    }

    private val deviceIdentityManager by lazy { DeviceIdentityManager(keyManager) }
    private val biometricAuthManager by lazy { BiometricAuthManager(context) }
    private val fileProviderGateway by lazy { FileProviderGateway(context) }

    val transferCoordinator by lazy {
        TransferCoordinator(
            lanEngine = lanEngine,
            relayEngine = relayEngine,
            networkMonitor = networkMonitor,
            transferRepository = transferRepo,
            serviceController = TransferServiceController,
            serviceLauncher = serviceLauncher,
            directoryManager = dirManager,
            auditRepository = auditRepo,
            keyManager = keyManager
        )
    }

    // 1. SenderViewModel
    val senderViewModel by lazy {
        SenderViewModel(
            createSenderSessionUseCase = CreateSenderSessionUseCase(
                transferRepository = transferRepo,
                deviceIdentityManager = deviceIdentityManager,
                auditRepository = auditRepo
            ),
            manageApprovalModeUseCase = ManageApprovalModeUseCase(
                transferRepository = transferRepo,
                auditRepository = auditRepo
            ),
            cancelTransferSessionUseCase = CancelTransferSessionUseCase(
                transferRepository = transferRepo,
                auditRepository = auditRepo
            ),
            lanEngine = lanEngine,
            relayEngine = relayEngine,
            authenticateBiometricUseCase = com.filetransfer.app.domain.usecase.platform.AuthenticateBiometricUseCase(biometricAuthManager),
            serviceLauncher = serviceLauncher,
            transferRepository = transferRepo,
            preferencesManager = com.filetransfer.app.core.platform.preference.AppPreferencesManager.getInstance(context)
        )
    }

    // 2. ReceiverViewModel
    val receiverViewModel by lazy {
        val checkStorageUseCase = CheckReceiverStorageUseCase(
            storageAuditor = StatFsStorageAuditor(context),
            auditRepository = auditRepo
        )
        val requestConnUseCase = RequestConnectionUseCase(
            transferRepository = transferRepo,
            checkReceiverStorageUseCase = checkStorageUseCase,
            auditRepository = auditRepo,
            relayEngine = relayEngine
        )
        ReceiverViewModel(
            scanQrTokenUseCase = ScanQrTokenUseCase(auditRepository = auditRepo),
            checkReceiverStorageUseCase = checkStorageUseCase,
            requestConnectionUseCase = requestConnUseCase,
            getDeviceIdentityUseCase = GetDeviceIdentityUseCase(deviceIdentityManager),
            lanEngine = lanEngine,
            relayEngine = relayEngine
        )
    }

    // 3. TransferViewModel
    val transferViewModel by lazy {
        TransferViewModel(
            controlSessionUseCase = ControlSessionUseCase(transferCoordinator, transferRepo, auditRepo),
            transferCoordinator = transferCoordinator,
            openFileSecurelyUseCase = OpenFileSecurelyUseCase(
                biometricAuthManager = biometricAuthManager,
                fileProviderGateway = fileProviderGateway,
                auditRepository = auditRepo
            ),
            biometricAuthManager = biometricAuthManager
        )
    }

    // 4. AuditViewModel
    val auditViewModel by lazy {
        AuditViewModel(
            querySecurityAuditUseCase = QuerySecurityAuditUseCase(auditRepo)
        )
    }

    // 5. HistoryViewModel
    val historyViewModel by lazy {
        HistoryViewModel(
            transferRepository = transferRepo,
            openFileSecurelyUseCase = OpenFileSecurelyUseCase(
                biometricAuthManager = biometricAuthManager,
                fileProviderGateway = fileProviderGateway,
                auditRepository = auditRepo
            ),
            biometricAuthManager = biometricAuthManager
        )
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when {
            modelClass.isAssignableFrom(SenderViewModel::class.java) -> senderViewModel as T
            modelClass.isAssignableFrom(ReceiverViewModel::class.java) -> receiverViewModel as T
            modelClass.isAssignableFrom(TransferViewModel::class.java) -> transferViewModel as T
            modelClass.isAssignableFrom(AuditViewModel::class.java) -> auditViewModel as T
            modelClass.isAssignableFrom(HistoryViewModel::class.java) -> historyViewModel as T
            else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
