package com.teamshare.app.presentation.viewmodel

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.teamshare.app.core.constant.AppConstants
import com.teamshare.app.domain.model.TransferSession
import com.teamshare.app.domain.repository.ITransferRepository
import com.teamshare.app.domain.usecase.platform.OpenFileSecurelyUseCase
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
            val completedDir = File(context.filesDir, "${AppConstants.StorageDirectory.TRANSFERS}/${AppConstants.StorageDirectory.COMPLETED}")
            if (session.docId.isNotBlank()) {
                val f = File(completedDir, session.docId)
                if (f.exists()) return f
            }

            // Thử tìm trong outgoing cache nếu là máy gửi
            val outgoingDir = File(context.cacheDir, "outgoing_shares")
            if (session.docId.isNotBlank()) {
                val f = File(outgoingDir, session.docId)
                if (f.exists()) return f
            }
        }

        return null
    }

    /**
     * Mở tệp trực tiếp thông qua ứng dụng tương thích.
     * @deprecated Sử dụng [openFileSecurely] để bảo vệ tài liệu bằng sinh trắc học theo SRS FR-DOC-03 & BR-05.
     */
    @Deprecated(
        message = "Sử dụng openFileSecurely để bảo đảm tuân thủ xác thực sinh trắc học",
        replaceWith = ReplaceWith("openFileSecurely(activity, session, onResult)")
    )
    fun openFileDirectly(
        activity: FragmentActivity,
        session: TransferSession,
        onResult: (Boolean, String?) -> Unit
    ) {
        openFileSecurely(activity, session, onResult)
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

        scope.launch {
            try {
                val result = useCase.execute(file = file, requireBiometric = true, activity = activity)
                result.onSuccess { intent ->
                    activity.startActivity(intent)
                    onResult(true, null)
                }.onFailure { err ->
                    onResult(false, err.message ?: "Xác thực không thành công")
                }
            } catch (e: Exception) {
                onResult(false, e.message ?: "Xác thực không thành công")
            }
        }
    }

    /**
     * Xuất tệp hoặc thư mục ra thư mục công cộng Downloads/TeamShare của máy.
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
            val exporter = com.teamshare.app.core.storage.saf.PublicStorageExporter(context)
            try {
                if (file.isDirectory) {
                    val result = exporter.exportFolder(file)
                    withContext(Dispatchers.Main) {
                        result.onSuccess { count ->
                            onResult(true, "Đã giải nén $count tệp vào Downloads/TeamShare/${file.name}")
                        }.onFailure { err ->
                            onResult(false, "Lỗi khi giải nén thư mục: ${err.message}")
                        }
                    }
                } else {
                    val result = exporter.exportSingleFile(file)
                    withContext(Dispatchers.Main) {
                        result.onSuccess { path ->
                            onResult(true, "Đã lưu tệp vào $path")
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
     * Lưu một tệp con riêng lẻ trong thư mục hoặc gói tệp ra Downloads/TeamShare.
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
            val exporter = com.teamshare.app.core.storage.saf.PublicStorageExporter(context)
            val result = exporter.exportSingleFile(childFile)
            withContext(Dispatchers.Main) {
                result.onSuccess { path ->
                    onResult(true, "Đã lưu ${childFile.name} vào $path")
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

