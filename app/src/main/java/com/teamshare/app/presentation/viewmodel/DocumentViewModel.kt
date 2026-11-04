package com.teamshare.app.presentation.viewmodel

import android.net.Uri
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.teamshare.app.domain.model.MemberRole
import com.teamshare.app.domain.usecase.document.DeleteDocumentUseCase
import com.teamshare.app.domain.usecase.document.GetDocumentCatalogUseCase
import com.teamshare.app.domain.usecase.document.OpenSecureDocumentUseCase
import com.teamshare.app.domain.usecase.document.PublishDocumentUseCase
import com.teamshare.app.domain.usecase.platform.AuthenticateBiometricUseCase
import com.teamshare.app.domain.usecase.platform.HandleSendIntentUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.InputStream

/**
 * ViewModel quản lý danh mục tài liệu nhóm, công bố tệp từ SAF,
 * mở tệp an toàn (xác thực sinh trắc học nếu yêu cầu) và tiêu hủy tệp Anti-Forensics.
 */
class DocumentViewModel(
    private val publishDocumentUseCase: PublishDocumentUseCase,
    private val getDocumentCatalogUseCase: GetDocumentCatalogUseCase,
    private val openSecureDocumentUseCase: OpenSecureDocumentUseCase,
    private val deleteDocumentUseCase: DeleteDocumentUseCase,
    private val authenticateBiometricUseCase: AuthenticateBiometricUseCase? = null,
    private val handleSendIntentUseCase: HandleSendIntentUseCase? = null,
    private val customScope: CoroutineScope? = null
) : ViewModel() {

    private val scope: CoroutineScope get() = customScope ?: viewModelScope

    private val _catalogUiState = MutableStateFlow(DocumentCatalogUiState())
    val catalogUiState: StateFlow<DocumentCatalogUiState> = _catalogUiState.asStateFlow()

    private val _pendingSharedUris = MutableStateFlow<List<Uri>>(emptyList())
    val pendingSharedUris: StateFlow<List<Uri>> = _pendingSharedUris.asStateFlow()

    private var catalogObserveJob: Job? = null

    fun setPendingSharedUris(uris: List<Uri>) {
        _pendingSharedUris.value = uris
    }

    fun clearPendingSharedUris() {
        _pendingSharedUris.value = emptyList()
    }

    fun publishPendingFiles(
        teamId: String,
        publisherId: String,
        onSuccess: ((Int) -> Unit)? = null
    ) {
        val uris = _pendingSharedUris.value
        if (uris.isEmpty()) return
        scope.launch {
            _catalogUiState.update { it.copy(isPublishing = true, errorMessage = null, actionMessage = null) }
            if (handleSendIntentUseCase != null) {
                handleSendIntentUseCase(uris, teamId, publisherId).collect { result ->
                    result.fold(
                        onSuccess = { count ->
                            _pendingSharedUris.value = emptyList()
                            _catalogUiState.update {
                                it.copy(
                                    isPublishing = false,
                                    actionMessage = "Đã công bố $count tệp từ chia sẻ ngoài"
                                )
                            }
                            onSuccess?.invoke(count)
                        },
                        onFailure = { error ->
                            _catalogUiState.update {
                                it.copy(
                                    isPublishing = false,
                                    errorMessage = error.message ?: "Không thể công bố tệp chia sẻ"
                                )
                            }
                        }
                    )
                }
            } else {
                var count = 0
                for (uri in uris) {
                    publishDocumentUseCase(teamId, publisherId, uri).collect { res ->
                        if (res.isSuccess) count++
                    }
                }
                _pendingSharedUris.value = emptyList()
                _catalogUiState.update {
                    it.copy(isPublishing = false, actionMessage = "Đã công bố $count tệp từ chia sẻ ngoài")
                }
                onSuccess?.invoke(count)
            }
        }
    }

    /**
     * Tải và liên tục quan sát danh mục tài liệu của nhóm (UC-08 - FR-DOC-02).
     */
    fun loadDocuments(teamId: String) {
        catalogObserveJob?.cancel()
        catalogObserveJob = scope.launch {
            _catalogUiState.update { it.copy(isLoading = true, errorMessage = null) }
            getDocumentCatalogUseCase(teamId).collect { docs ->
                _catalogUiState.update {
                    it.copy(documents = docs, isLoading = false, errorMessage = null)
                }
            }
        }
    }

    /**
     * Công bố tệp vào nhóm từ SAF Uri (UC-07 - FR-DOC-01).
     */
    fun publishDocument(
        teamId: String,
        publisherId: String,
        uri: Uri,
        requireBiometric: Boolean,
        overrideName: String? = null,
        overrideSize: Long? = null,
        overrideMime: String? = null,
        customInputStream: InputStream? = null,
        onSuccess: (() -> Unit)? = null
    ) {
        scope.launch {
            _catalogUiState.update { it.copy(isPublishing = true, errorMessage = null, actionMessage = null) }
            publishDocumentUseCase(
                teamId = teamId,
                publisherId = publisherId,
                uri = uri,
                requireBiometric = requireBiometric,
                overrideName = overrideName,
                overrideSize = overrideSize,
                overrideMime = overrideMime,
                customInputStream = customInputStream
            ).collect { result ->
                result.fold(
                    onSuccess = { doc ->
                        _catalogUiState.update {
                            it.copy(
                                isPublishing = false,
                                actionMessage = "Đã công bố tài liệu: ${doc.fileName}"
                            )
                        }
                        onSuccess?.invoke()
                    },
                    onFailure = { error ->
                        _catalogUiState.update {
                            it.copy(
                                isPublishing = false,
                                errorMessage = error.message ?: "Không thể công bố tài liệu"
                            )
                        }
                    }
                )
            }
        }
    }

    /**
     * Mở tệp an toàn qua FileProvider (UC-09 - FR-DOC-03).
     * Tự động kích hoạt xác thực sinh trắc học nếu tài liệu yêu cầu (UC-17 - FR-MOB-02).
     */
    fun openDocument(
        docId: String,
        activity: FragmentActivity? = null,
        onUriReady: ((Uri) -> Unit)? = null
    ) {
        val doc = _catalogUiState.value.documents.find { it.docId == docId }
        val needsBiometric = doc?.requireBiometric == true && authenticateBiometricUseCase != null

        if (needsBiometric) {
            scope.launch {
                _catalogUiState.update { it.copy(isLoading = true, errorMessage = null, openedDocumentUri = null) }
                authenticateBiometricUseCase!!(activity).collect { authResult ->
                    authResult.fold(
                        onSuccess = {
                            doOpenSecureDocument(docId, onUriReady)
                        },
                        onFailure = { error ->
                            _catalogUiState.update {
                                it.copy(
                                    isLoading = false,
                                    errorMessage = "Xác thực sinh trắc học thất bại: ${error.message}"
                                )
                            }
                        }
                    )
                }
            }
        } else {
            doOpenSecureDocument(docId, onUriReady)
        }
    }

    private fun doOpenSecureDocument(
        docId: String,
        onUriReady: ((Uri) -> Unit)? = null
    ) {
        scope.launch {
            _catalogUiState.update { it.copy(isLoading = true, errorMessage = null, openedDocumentUri = null) }
            openSecureDocumentUseCase(docId).collect { result ->
                result.fold(
                    onSuccess = { contentUri ->
                        _catalogUiState.update {
                            it.copy(isLoading = false, openedDocumentUri = contentUri.toString())
                        }
                        onUriReady?.invoke(contentUri)
                    },
                    onFailure = { error ->
                        _catalogUiState.update {
                            it.copy(isLoading = false, errorMessage = error.message ?: "Không thể mở tài liệu an toàn")
                        }
                    }
                )
            }
        }
    }

    /**
     * Xóa tài liệu khỏi CSDL nhóm và tùy chọn ghi đè 0x00 tiêu hủy tệp đĩa (UC-10 - FR-DOC-04).
     */
    fun deleteDocument(
        docId: String,
        requesterMemberId: String,
        requesterRole: MemberRole? = null,
        wipeLocally: Boolean = true,
        onSuccess: (() -> Unit)? = null
    ) {
        scope.launch {
            _catalogUiState.update { it.copy(isLoading = true, errorMessage = null, actionMessage = null) }
            deleteDocumentUseCase(docId, requesterMemberId, requesterRole, wipeLocally).collect { result ->
                result.fold(
                    onSuccess = {
                        _catalogUiState.update {
                            it.copy(isLoading = false, actionMessage = "Đã xóa tài liệu an toàn")
                        }
                        onSuccess?.invoke()
                    },
                    onFailure = { error ->
                        _catalogUiState.update {
                            it.copy(isLoading = false, errorMessage = error.message ?: "Không thể xóa tài liệu")
                        }
                    }
                )
            }
        }
    }

    fun clearOpenedDocumentUri() {
        _catalogUiState.update { it.copy(openedDocumentUri = null) }
    }

    fun clearActionMessage() {
        _catalogUiState.update { it.copy(actionMessage = null, openedDocumentUri = null) }
    }

    fun clearError() {
        _catalogUiState.update { it.copy(errorMessage = null) }
    }

    override fun onCleared() {
        super.onCleared()
        catalogObserveJob?.cancel()
    }
}
