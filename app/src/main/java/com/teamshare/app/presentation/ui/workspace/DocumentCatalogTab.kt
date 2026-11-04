package com.teamshare.app.presentation.ui.workspace

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.teamshare.app.domain.model.DownloadStatus
import com.teamshare.app.domain.model.MemberRole
import com.teamshare.app.domain.model.SharedDocument
import com.teamshare.app.presentation.ui.components.ConfirmationDialog
import com.teamshare.app.presentation.ui.components.EmptyStateView
import com.teamshare.app.presentation.ui.components.ErrorBanner
import com.teamshare.app.presentation.ui.components.SuccessBanner
import com.teamshare.app.presentation.viewmodel.DocumentViewModel

@Composable
fun DocumentCatalogTab(
    teamId: String,
    currentMemberId: String?,
    isOwner: Boolean,
    viewModel: DocumentViewModel,
    onRequestDownload: (docId: String) -> Unit,
    onOpenDocument: (docId: String) -> Unit
) {
    val uiState by viewModel.catalogUiState.collectAsState()

    var selectedUriToPublish by remember { mutableStateOf<Uri?>(null) }
    var selectedFileName by remember { mutableStateOf("") }
    var showPublishDialog by remember { mutableStateOf(false) }
    var docToDelete by remember { mutableStateOf<SharedDocument?>(null) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            if (uri != null) {
                selectedUriToPublish = uri
                selectedFileName = uri.lastPathSegment ?: "document"
                showPublishDialog = true
            }
        }
    )

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (uiState.errorMessage != null) {
                ErrorBanner(
                    message = uiState.errorMessage!!,
                    onDismiss = { viewModel.clearError() }
                )
            }

            if (uiState.actionMessage != null) {
                SuccessBanner(
                    message = uiState.actionMessage!!,
                    onDismiss = { viewModel.clearActionMessage() }
                )
            }

            if (uiState.documents.isEmpty() && !uiState.isLoading) {
                EmptyStateView(
                    title = "Chưa có tài liệu nào",
                    subtitle = "Chọn tệp từ máy của bạn để chia sẻ an toàn với các thành viên trong nhóm.",
                    icon = Icons.Default.Description,
                    actionLabel = "Công bố tài liệu mới",
                    onAction = { filePickerLauncher.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(uiState.documents, key = { it.docId }) { doc ->
                        val canDelete = isOwner || (currentMemberId != null && doc.publisherId == currentMemberId)
                        DocumentCard(
                            document = doc,
                            canDelete = canDelete,
                            onDownloadClick = { onRequestDownload(doc.docId) },
                            onOpenClick = { onOpenDocument(doc.docId) },
                            onDeleteClick = { docToDelete = doc }
                        )
                    }
                }
            }
        }

        // Nút FAB công bố tài liệu
        FloatingActionButton(
            onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        ) {
            Icon(imageVector = Icons.Default.UploadFile, contentDescription = "Công bố tài liệu mới")
        }

        // Hộp thoại công bố tài liệu đã chọn
        if (showPublishDialog && selectedUriToPublish != null) {
            PublishDocumentDialog(
                selectedUri = selectedUriToPublish!!,
                fileName = selectedFileName,
                isLoading = uiState.isPublishing,
                onDismiss = { showPublishDialog = false },
                onConfirm = { requireBiometric ->
                    viewModel.publishDocument(
                        teamId = teamId,
                        publisherId = currentMemberId ?: "unknown_publisher",
                        uri = selectedUriToPublish!!,
                        requireBiometric = requireBiometric,
                        onSuccess = { showPublishDialog = false }
                    )
                }
            )
        }

        // Hộp thoại xác nhận tiêu hủy tệp an toàn (Anti-Forensics)
        if (docToDelete != null) {
            ConfirmationDialog(
                title = "Tiêu hủy tài liệu an toàn?",
                message = "Hành động này sẽ xóa vĩnh viễn '${docToDelete!!.fileName}' khỏi CSDL nhóm và ghi đè byte 0x00 lên tệp tin cục bộ (chống khôi phục dữ liệu). Bạn có chắc chắn?",
                confirmText = "Tiêu hủy",
                isDestructive = true,
                onConfirm = {
                    val role = if (isOwner) MemberRole.OWNER else MemberRole.MEMBER
                    viewModel.deleteDocument(
                        docId = docToDelete!!.docId,
                        requesterMemberId = currentMemberId ?: "",
                        requesterRole = role,
                        wipeLocally = true
                    )
                    docToDelete = null
                },
                onDismiss = { docToDelete = null }
            )
        }
    }
}

@Composable
fun DocumentCard(
    document: SharedDocument,
    canDelete: Boolean,
    onDownloadClick: () -> Unit,
    onOpenClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Description,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = document.fileName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = formatFileSize(document.fileSize),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (document.requireBiometric) {
                    Icon(
                        imageVector = Icons.Default.Fingerprint,
                        contentDescription = "Yêu cầu vân tay",
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier
                            .size(24.dp)
                            .padding(end = 4.dp)
                    )
                }

                if (canDelete) {
                    IconButton(onClick = onDeleteClick, modifier = Modifier.size(28.dp)) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Xóa tài liệu",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Trạng thái tải về
                when (document.downloadStatus) {
                    DownloadStatus.NOT_DOWNLOADED -> {
                        AssistChip(
                            onClick = {},
                            label = { Text("Chưa tải") }
                        )
                        Button(onClick = onDownloadClick) {
                            Icon(imageVector = Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Tải về")
                        }
                    }
                    DownloadStatus.DOWNLOADING -> {
                        AssistChip(
                            onClick = {},
                            label = { Text("Đang tải...", color = Color(0xFFEF6C00)) }
                        )
                    }
                    DownloadStatus.COMPLETED -> {
                        AssistChip(
                            onClick = {},
                            label = { Text("Đã có trên máy", color = Color(0xFF2E7D32)) }
                        )
                        OutlinedButton(onClick = onOpenClick) {
                            Icon(imageVector = Icons.Default.FileOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Mở tệp")
                        }
                    }
                }
            }
        }
    }
}

fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
    val value = bytes / Math.pow(1024.0, digitGroups.toDouble())
    return String.format("%.1f %s", value, units[digitGroups.coerceIn(0, units.size - 1)])
}

