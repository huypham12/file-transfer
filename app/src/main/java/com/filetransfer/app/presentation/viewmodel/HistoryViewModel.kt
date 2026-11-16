package com.filetransfer.app.presentation.viewmodel

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filetransfer.app.core.constant.AppConstants
import com.filetransfer.app.domain.model.TransferSession
import com.filetransfer.app.domain.repository.ITransferRepository
import com.filetransfer.app.domain.usecase.platform.OpenFileSecurelyUseCase
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class HistoryTab {
    RECEIVED,
    SENT
}

data class HistoryUiState(
    val receivedSessions: List<TransferSession> = emptyList(),
    val sentSessions: List<TransferSession> = emptyList(),
    val selectedTab: HistoryTab = HistoryTab.RECEIVED,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val actionFeedbackMessage: String? = null
)

/**
 * ViewModel quản lý lịch sử truyền nhận tệp (FR-HIS-01).
 * Cung cấp khả năng tra cứu, mở tệp và xuất tệp ra thư mục Tải về.
 */
class HistoryViewModel(
    private val transferRepository: ITransferRepository,
    private val openFileSecurelyUseCase: OpenFileSecurelyUseCase? = null,
    private val biometricAuthManager: com.filetransfer.app.core.security.biometric.IBiometricAuthManager? = null,
    private val fileProviderGateway: com.filetransfer.app.core.storage.saf.FileProviderGateway? = null,
    private val customScope: CoroutineScope? = null
) : ViewModel() {

    private val scope: CoroutineScope get() = customScope ?: viewModelScope

    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    init {
        loadHistory()
    }

    fun loadHistory() {
        loadJob?.cancel()
        loadJob = scope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                transferRepository.getAllSessions().collect { sessions ->
                    val received = sessions.filter { it.direction.isReceiver }
                    val sent = sessions.filter { it.direction.isSender }
                    _uiState.update { current ->
                        current.copy(
                            receivedSessions = received,
                            sentSessions = sent,
                            isLoading = false
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, errorMessage = e.message) }
            }
        }
    }

    fun selectTab(tab: HistoryTab) {
        _uiState.update { it.copy(selectedTab = tab) }
    }

    /**
     * Tìm tệp tương ứng trên đĩa cục bộ.
     */
    fun resolveSessionFile(context: Context? = null, session: TransferSession): File? {
        if (session.tempFilePath.isNotBlank()) {
            val f = File(session.tempFilePath)
            if (f.exists()) return f
        }

        if (context != null) {
            val manifestName = runCatching {
                val array = org.json.JSONArray(session.manifestJson)
                if (array.length() > 0) array.getJSONObject(0).optString("fileName") else null
            }.getOrNull()

            if (!manifestName.isNullOrBlank()) {
                val completedDir = File(context.filesDir, "${AppConstants.StorageDirectory.TRANSFERS}/${AppConstants.StorageDirectory.COMPLETED}")
                val f = File(completedDir, manifestName)
                if (f.exists()) return f

                // Thử tìm trong outgoing cache nếu là máy gửi
                val outgoingDir = File(context.cacheDir, "outgoing_shares")
                val outgoingFile = File(outgoingDir, manifestName)
                if (outgoingFile.exists()) return outgoingFile
            }
        }

        return null
    }

    /**
     * Mở tệp an toàn kèm xác thực sinh trắc học vân tay từ TransferSession.
     */
    fun openFileSecurely(
        activity: FragmentActivity,
        session: TransferSession,
        onResult: (Boolean, String?) -> Unit
    ) {
        val file = resolveSessionFile(activity, session)
        if (file == null || !file.exists()) {
            onResult(false, "Không tìm thấy tệp tin trên bộ nhớ thiết bị")
            return
        }
        openFileSecurely(activity, file, onResult)
    }

    /**
     * Mở tệp an toàn kèm xác thực sinh trắc học vân tay từ đối tượng File cụ thể.
     */
    fun openFileSecurely(
        activity: FragmentActivity,
        file: File,
        onResult: (Boolean, String?) -> Unit
    ) {
        if (!file.exists()) {
            onResult(false, "Không tìm thấy tệp tin trên bộ nhớ thiết bị")
            return
        }

        val useCase = openFileSecurelyUseCase
        if (useCase == null) {
            onResult(false, "Chức năng mở tệp chưa được khởi tạo")
            return
        }

        val prefs = com.filetransfer.app.core.platform.preference.AppPreferencesManager.getInstance(activity)
        val isBiometricEnforced = prefs.requireBiometricForOpen.value
        val canAuth = biometricAuthManager?.canAuthenticate() ?: true
        val requireBiometric = isBiometricEnforced && canAuth

        scope.launch {
            try {
                val result = useCase.execute(
                    file = file,
                    requireBiometric = requireBiometric,
                    authenticate = {
                        val bio = biometricAuthManager ?: return@execute true
                        val deferred = kotlinx.coroutines.CompletableDeferred<Boolean>()
                        bio.authenticate(
                            activity = activity,
                            title = "Xác thực mở tài liệu bảo mật",
                            subtitle = "Vui lòng xác thực vân tay hoặc mã khóa để mở tệp ${file.name}",
                            negativeButtonText = "Hủy bỏ",
                            onSuccess = { deferred.complete(true) },
                            onError = { _, errString -> deferred.completeExceptionally(SecurityException(errString)) },
                            onFailed = { deferred.completeExceptionally(SecurityException("Xác thực không trùng khớp")) }
                        )
                        deferred.await()
                    }
                )
                result.onSuccess { authorizedFile ->
                    val gateway = fileProviderGateway ?: com.filetransfer.app.core.storage.saf.FileProviderGateway(activity)
                    val intent = if (authorizedFile.isDirectory) {
                        gateway.createOpenDirectoryIntent(authorizedFile)
                    } else {
                        gateway.createViewIntent(authorizedFile)
                    }
                    activity.startActivity(intent)
                    onResult(true, null)
                }.onFailure { err ->
                    val userFriendlyMsg = if (err.message?.contains("0x401") == true) {
                        "Xác thực danh tính không thành công hoặc đã bị hủy"
                    } else {
                        err.message ?: "Không thể mở tệp"
                    }
                    onResult(false, userFriendlyMsg)
                }
            } catch (e: Exception) {
                val userFriendlyMsg = if (e.message?.contains("0x401") == true) {
                    "Xác thực danh tính không thành công hoặc đã bị hủy"
                } else {
                    e.message ?: "Không thể mở tệp"
                }
                onResult(false, userFriendlyMsg)
            }
        }
    }

    /**
     * Xuất tệp hoặc thư mục ra thư mục công cộng Downloads/filetransfer của máy.
     * Tự động nhận diện tệp đơn lẻ hoặc thư mục để trích xuất trực tiếp thay vì nén .zip.
     */
    fun exportFileToDownloads(
        context: Context,
        session: TransferSession,
        onResult: (Boolean, String?) -> Unit
    ) {
        val file = resolveSessionFile(context, session)
        if (file == null || !file.exists()) {
            onResult(false, "Không tìm thấy tệp tin nguồn")
            return
        }

        scope.launch(Dispatchers.IO) {
            val exporter = com.filetransfer.app.core.storage.saf.PublicStorageExporter(context)
            try {
                if (file.isDirectory) {
                    val result = exporter.exportFolder(file)
                    withContext(Dispatchers.Main) {
                        result.onSuccess { count ->
                            onResult(true, "Đã lưu $count tệp vào Downloads/filetransfer/${file.name}")
                        }.onFailure { err ->
                            onResult(false, "Lỗi khi lưu tệp vào Downloads: ${err.message}")
                        }
                    }
                } else {
                    val alreadyExisted = exporter.checkFileExistsInDownloads(file.name)
                    val result = exporter.exportSingleFile(file)
                    withContext(Dispatchers.Main) {
                        result.onSuccess { path ->
                            val msg = if (alreadyExisted) "Đã lưu thêm bản sao tại $path" else "Đã lưu tệp vào $path"
                            onResult(true, msg)
                        }.onFailure { err ->
                            onResult(false, "Lỗi khi lưu tệp: ${err.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onResult(false, "Lỗi khi lưu tệp: ${e.message}")
                }
            }
        }
    }

    /**
     * Kiểm tra xem tệp đã tồn tại trong thư mục Download/filetransfer công cộng hay chưa.
     */
    fun checkFileExistsInDownloads(context: Context, file: File): Boolean {
        if (!file.exists()) return false
        val exporter = com.filetransfer.app.core.storage.saf.PublicStorageExporter(context)
        return exporter.checkFileExistsInDownloads(file.name)
    }

    /**
     * Lưu một tệp con riêng lẻ trong thư mục hoặc gói tệp ra Downloads/filetransfer.
     */
    fun exportSingleChildFile(
        context: Context,
        childFile: File,
        onResult: (Boolean, String?) -> Unit
    ) {
        if (!childFile.exists()) {
            onResult(false, "Tệp nguồn không tồn tại")
            return
        }

        scope.launch(Dispatchers.IO) {
            val exporter = com.filetransfer.app.core.storage.saf.PublicStorageExporter(context)
            val alreadyExisted = exporter.checkFileExistsInDownloads(childFile.name)
            val result = exporter.exportSingleFile(childFile)
            withContext(Dispatchers.Main) {
                result.onSuccess { path ->
                    val msg = if (alreadyExisted) "Đã lưu thêm bản sao ${childFile.name} tại $path" else "Đã lưu ${childFile.name} vào $path"
                    onResult(true, msg)
                }.onFailure { err ->
                    onResult(false, "Lỗi khi lưu tệp: ${err.message}")
                }
            }
        }
    }

    fun clearFeedback() {
        _uiState.update { it.copy(actionFeedbackMessage = null, errorMessage = null) }
    }

    override fun onCleared() {
        super.onCleared()
        loadJob?.cancel()
    }
}

