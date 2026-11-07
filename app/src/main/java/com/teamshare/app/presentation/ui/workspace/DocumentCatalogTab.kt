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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
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
    val peerPresence by viewModel.peerPresence.collectAsState()

    var selectedUriToPublish by remember { mutableStateOf<Uri?>(null) }
    var selectedFileName by remember { mutableStateOf("") }
    var showPublishDialog by remember { mutableStateOf(false) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var docToDelete by remember { mutableStateOf<SharedDocument?>(null) }
    var folderToDelete by remember { mutableStateOf<com.teamshare.app.domain.model.TeamFolder?>(null) }

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

    // Lọc thư mục và tài liệu theo cấp hiện tại
    val currentFolders = uiState.folders.filter { !it.isDeleted && it.parentFolderId == uiState.currentFolderId }
    val currentDocs = uiState.documents.filter { it.folderId == uiState.currentFolderId }

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

            // Thanh điều hướng Breadcrumb & Tạo thư mục
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Breadcrumb Scrollable
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AssistChip(
                            onClick = { viewModel.navigateToFolder(null) },
                            label = { Text("Gốc", fontWeight = if (uiState.currentFolderId == null) FontWeight.Bold else FontWeight.Normal) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Home,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        )

                        uiState.folderPath.forEachIndexed { index, folder ->
                            Icon(
                                imageVector = Icons.Default.ChevronRight,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            val isLast = index == uiState.folderPath.lastIndex
                            AssistChip(
                                onClick = { viewModel.navigateToFolder(folder) },
                                label = { Text(folder.folderName, fontWeight = if (isLast) FontWeight.Bold else FontWeight.Normal) }
                            )
                        }
                    }

                    // Nút Tạo thư mục mới
                    IconButton(
                        onClick = { showCreateFolderDialog = true },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CreateNewFolder,
                            contentDescription = "Tạo thư mục mới",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            if (currentFolders.isEmpty() && currentDocs.isEmpty() && !uiState.isLoading) {
                EmptyStateView(
                    title = if (uiState.currentFolderId == null) "Chưa có tài liệu nào" else "Thư mục trống",
                    subtitle = "Tạo thư mục con hoặc chọn tệp từ máy của bạn để chia sẻ trong thư mục này.",
                    icon = if (uiState.currentFolderId == null) Icons.Default.Description else Icons.Default.Folder,
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
                    // Nút quay lại cấp cha nếu đang ở trong thư mục con
                    if (uiState.currentFolderId != null) {
                        item(key = "__navigate_up__") {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)),
                                onClick = { viewModel.navigateUp() }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ArrowBack,
                                        contentDescription = "Quay lại thư mục cha",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = ".. (Quay lại thư mục cha)",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }

                    // Hiển thị danh sách thư mục con
                    items(currentFolders, key = { "folder_${it.folderId}" }) { folder ->
                        val childFolderCount = uiState.folders.count { !it.isDeleted && it.parentFolderId == folder.folderId }
                        val childDocCount = uiState.documents.count { it.folderId == folder.folderId }
                        val canDeleteFolder = isOwner || (currentMemberId != null && folder.createdBy == currentMemberId)

                        FolderCard(
                            folder = folder,
                            itemCount = childFolderCount + childDocCount,
                            canDelete = canDeleteFolder,
                            onClick = { viewModel.navigateToFolder(folder) },
                            onDeleteClick = { folderToDelete = folder }
                        )
                    }

                    // Hiển thị danh sách tài liệu trong thư mục hiện tại
                    items(currentDocs, key = { "doc_${it.docId}" }) { doc ->
                        val canDelete = isOwner || (currentMemberId != null && doc.publisherId == currentMemberId)
                        val isPublisherOnline = peerPresence[doc.publisherId]?.isOnline == true || doc.publisherId == currentMemberId
                        DocumentCard(
                            document = doc,
                            canDelete = canDelete,
                            isPublisherOnline = isPublisherOnline,
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

        // Hộp thoại tạo thư mục mới
        if (showCreateFolderDialog) {
            CreateFolderDialog(
                onDismiss = { showCreateFolderDialog = false },
                onConfirm = { folderName ->
                    viewModel.createFolder(
                        teamId = teamId,
                        folderName = folderName,
                        createdBy = currentMemberId ?: "unknown"
                    )
                    showCreateFolderDialog = false
                }
            )
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

        // Hộp thoại xác nhận xóa thư mục
        if (folderToDelete != null) {
            ConfirmationDialog(
                title = "Xóa thư mục?",
                message = "Hành động này sẽ xóa thư mục '${folderToDelete!!.folderName}'. Các tệp tin bên trong vẫn được lưu trữ an toàn trong nhóm.",
                confirmText = "Xóa",
                isDestructive = true,
                onConfirm = {
                    viewModel.deleteFolder(teamId, folderToDelete!!.folderId)
                    folderToDelete = null
                },
                onDismiss = { folderToDelete = null }
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
fun FolderCard(
    folder: com.teamshare.app.domain.model.TeamFolder,
    itemCount: Int,
    canDelete: Boolean,
    onClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp)
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = folder.folderName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "$itemCount mục",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (canDelete) {
                IconButton(onClick = onDeleteClick) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Xóa thư mục",
                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}

@Composable
fun CreateFolderDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tạo thư mục mới") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Tên thư mục") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isNotBlank()) onConfirm(name.trim())
                },
                enabled = name.isNotBlank()
            ) {
                Text("Tạo")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Hủy")
            }
        }
    )
}

@Composable
fun DocumentCard(
    document: SharedDocument,
    canDelete: Boolean,
    isPublisherOnline: Boolean = false,
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = formatFileSize(document.fileSize),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (isPublisherOnline) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "• Trực tuyến",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF2E7D32),
                                fontWeight = FontWeight.Medium
                            )
                        } else {
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "• Ngoại tuyến",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.Gray,
                                fontWeight = FontWeight.Normal
                            )
                        }
                    }
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

