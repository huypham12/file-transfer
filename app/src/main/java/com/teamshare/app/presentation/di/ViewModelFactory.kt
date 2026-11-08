package com.teamshare.app.presentation.di

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.teamshare.app.core.platform.network.AndroidNetworkMonitor
import com.teamshare.app.core.platform.service.TransferServiceController
import com.teamshare.app.core.security.keystore.AndroidKeystoreKeyManager
import com.teamshare.app.core.storage.saf.StorageDirectoryManager
import com.teamshare.app.core.transfer.TransferCoordinator
import com.teamshare.app.core.transfer.lan.LanTransferEngine
import com.teamshare.app.core.transfer.relay.RelayClientEngine
import com.teamshare.app.data.local.db.AppDatabase
import com.teamshare.app.data.repository.AuditRepositoryImpl
import com.teamshare.app.data.repository.TransferRepositoryImpl
import com.teamshare.app.domain.usecase.audit.QuerySecurityAuditUseCase
import com.teamshare.app.domain.usecase.transfer.ControlSessionUseCase
import com.teamshare.app.presentation.viewmodel.AuditViewModel
import com.teamshare.app.presentation.viewmodel.TransferViewModel

/**
 * Nhà máy cấp phát ViewModels (Dependency Injection) cho tầng Presentation.
 * Khởi tạo trễ (Lazy initialization) các thành phần tinh gọn phục vụ Secure Local Drop.
 */
class ViewModelFactory(private val context: Context) : ViewModelProvider.Factory {

    private val keyManager by lazy { AndroidKeystoreKeyManager(context) }
    private val database by lazy { AppDatabase.getInstance(context, keyManager) }

    private val transferRepo by lazy { TransferRepositoryImpl(database.transferSessionDao()) }
    private val auditRepo by lazy { AuditRepositoryImpl(database.auditDao()) }

    private val dirManager by lazy { StorageDirectoryManager(context) }
    private val networkMonitor by lazy { AndroidNetworkMonitor(context) }

    private val lanEngine by lazy { LanTransferEngine() }
    private val relayEngine by lazy { RelayClientEngine() }

    val transferCoordinator by lazy {
        TransferCoordinator(
            lanEngine = lanEngine,
            relayEngine = relayEngine,
            networkMonitor = networkMonitor,
            transferRepository = transferRepo,
            serviceController = TransferServiceController,
            directoryManager = dirManager,
            auditRepository = auditRepo,
            keyManager = keyManager
        )
    }

    val transferViewModel by lazy {
        TransferViewModel(
            controlSessionUseCase = ControlSessionUseCase(transferCoordinator, transferRepo, auditRepo),
            transferCoordinator = transferCoordinator
        )
    }

    val auditViewModel by lazy {
        AuditViewModel(
            querySecurityAuditUseCase = QuerySecurityAuditUseCase(auditRepo)
        )
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when {
            modelClass.isAssignableFrom(TransferViewModel::class.java) -> transferViewModel as T
            modelClass.isAssignableFrom(AuditViewModel::class.java) -> auditViewModel as T
            else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
