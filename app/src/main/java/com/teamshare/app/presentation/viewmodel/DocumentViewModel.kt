package com.teamshare.app.presentation.viewmodel

import android.net.Uri
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.teamshare.app.core.network.sync.PeerSyncManager
import com.teamshare.app.domain.model.MemberRole
import com.teamshare.app.domain.model.TeamFolder
import com.teamshare.app.domain.repository.IFolderRepository
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
    private val peerSyncManager: PeerSyncManager? = null,
    private val customScope: CoroutineScope? = null,
    private val folderRepository: IFolderRepository? = null
) : ViewModel() {

    private val scope: CoroutineScope get() = customScope ?: viewModelScope

    private val _catalogUiState = MutableStateFlow(DocumentCatalogUiState())
    val catalogUiState: StateFlow<DocumentCatalogUiState> = _catalogUiState.asStateFlow()

    val peerPresence = peerSyncManager?.peerPresenceFlow ?: MutableStateFlow(emptyMap())

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
     * Tải và liên tục quan sát danh mục tài liệu và thư mục ảo của nhóm (UC-08 - FR-DOC-02).
     */
    fun loadDocuments(teamId: String) {
        catalogObserveJob?.cancel()
        catalogObserveJob = scope.launch {
            _catalogUiState.update { it.copy(isLoading = true, errorMessage = null) }

            // Chủ động gửi CATALOG_QUERY & FOLDER_SYNC_QUERY để kéo dữ liệu mới nhất từ Relay / LAN
            launch {
                try {
                    peerSyncManager?.queryCatalog(teamId)
                    peerSyncManager?.queryFolders(teamId)
                } catch (_: Exception) {}
            }

            // Quan sát thư mục ảo trong nhóm
            launch {
                folderRepository?.getFoldersByTeam(teamId)?.collect { allFolders ->
                    _catalogUiState.update { current ->
                        current.copy(folders = allFolders)
                    }
                }
            }

            getDocumentCatalogUseCase(teamId).collect { docs ->
                _catalogUiState.update {
                    it.copy(documents = docs, isLoading = false, errorMessage = null)
                }
            }
        }
    }

    /**
     * Điều hướng tới thư mục cụ thể hoặc quay về gốc (root) nếu null.
     */
    fun navigateToFolder(folder: TeamFolder?) {
        _catalogUiState.update { current ->
            if (folder == null) {
                current.copy(currentFolderId = null, folderPath = emptyList())
            } else {
                val newPath = current.folderPath.takeWhile { it.folderId != folder.folderId } + folder
                current.copy(currentFolderId = folder.folderId, folderPath = newPath)
            }
        }
    }

    /**
     * Điều hướng lên một cấp thư mục cha (Navigate Up).
     */
    fun navigateUp() {
        _catalogUiState.update { current ->
            val path = current.folderPath
            if (path.isEmpty()) {
                current.copy(currentFolderId = null, folderPath = emptyList())
            } else {
                val newPath = path.dropLast(1)
                val parentId = newPath.lastOrNull()?.folderId
                current.copy(currentFolderId = parentId, folderPath = newPath)
            }
        }
    }

    /**
     * Tạo thư mục ảo mới trong Không gian Nhóm và phát sóng thông báo P2P.
     */
    fun createFolder(teamId: String, folderName: String, createdBy: String, onSuccess: (() -> Unit)? = null) {
        val trimmedName = folderName.trim()
        if (trimmedName.isBlank()) {
            _catalogUiState.update { it.copy(errorMessage = "Tên thư mục không được để trống") }
            return
        }
        scope.launch {
            val folder = TeamFolder(
                folderId = java.util.UUID.randomUUID().toString(),
                teamId = teamId,
                parentFolderId = _catalogUiState.value.currentFolderId,
                folderName = trimmedName,
                createdBy = createdBy,
                createdAt = System.currentTimeMillis()
            )
            folderRepository?.createFolder(folder)
            peerSyncManager?.broadcastFolderAnnounce(folder)
            _catalogUiState.update { it.copy(actionMessage = "Đã tạo thư mục: $trimmedName") }
            onSuccess?.invoke()
        }
    }

    /**
     * Xóa mềm thư mục ảo và phát sóng thông báo P2P.
     */
    fun deleteFolder(teamId: String, folderId: String, onSuccess: (() -> Unit)? = null) {
        scope.launch {
            folderRepository?.softDeleteFolder(folderId)
            peerSyncManager?.broadcastFolderDelete(teamId, folderId)
            if (_catalogUiState.value.currentFolderId == folderId) {
                navigateUp()
            }
            _catalogUiState.update { it.copy(actionMessage = "Đã xóa thư mục") }
            onSuccess?.invoke()
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
            val currentFolder = _catalogUiState.value.currentFolderId
            publishDocumentUseCase(
                teamId = teamId,
                publisherId = publisherId,
                uri = uri,
                requireBiometric = requireBiometric,
                overrideName = overrideName,
                overrideSize = overrideSize,
                overrideMime = overrideMime,
                customInputStream = customInputStream,
                folderId = currentFolder
            ).collect { result ->
                result.fold(
                    onSuccess = { doc ->
                        // Phát sóng thông báo tài liệu mới tới các thành viên trong nhóm
                        launch {
                            peerSyncManager?.broadcastCatalogAnnounce(
                                teamId = teamId,
                                document = doc,
                                publisherName = publisherId
                            )
                        }

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
        val doc = _catalogUiState.value.documents.find { it.docId == docId }
        scope.launch {
            _catalogUiState.update { it.copy(isLoading = true, errorMessage = null, openedDocumentUri = null, openedDocumentMimeType = null) }
            openSecureDocumentUseCase(docId).collect { result ->
                result.fold(
                    onSuccess = { contentUri ->
                        _catalogUiState.update {
                            it.copy(
                                isLoading = false,
                                openedDocumentUri = contentUri.toString(),
                                openedDocumentMimeType = doc?.mimeType
                            )
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
        _catalogUiState.update { it.copy(openedDocumentUri = null, openedDocumentMimeType = null) }
    }

    fun clearActionMessage() {
        _catalogUiState.update { it.copy(actionMessage = null, openedDocumentUri = null, openedDocumentMimeType = null) }
    }

    fun clearError() {
        _catalogUiState.update { it.copy(errorMessage = null) }
    }

    override fun onCleared() {
        super.onCleared()
        catalogObserveJob?.cancel()
    }
}
