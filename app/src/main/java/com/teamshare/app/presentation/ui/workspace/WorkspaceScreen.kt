package com.teamshare.app.presentation.ui.workspace

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.teamshare.app.presentation.ui.components.ConfirmationDialog
import com.teamshare.app.presentation.viewmodel.AuditViewModel
import com.teamshare.app.presentation.viewmodel.DocumentViewModel
import com.teamshare.app.presentation.viewmodel.TeamViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceScreen(
    teamId: String,
    teamViewModel: TeamViewModel,
    documentViewModel: DocumentViewModel,
    auditViewModel: AuditViewModel,
    onBackClick: () -> Unit,
    onInviteQrClick: () -> Unit,
    onRequestDownload: (docId: String) -> Unit,
    onOpenDocument: (docId: String) -> Unit
) {
    val context = LocalContext.current
    val workspaceState by teamViewModel.workspaceUiState.collectAsState()
    val catalogState by documentViewModel.catalogUiState.collectAsState()
    val pendingSharedUris by documentViewModel.pendingSharedUris.collectAsState()

    var selectedTabIndex by remember { mutableIntStateOf(0) }
    var showMenu by remember { mutableStateOf(false) }
    var showDisbandConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(teamId) {
        teamViewModel.loadWorkspace(teamId)
        documentViewModel.loadDocuments(teamId)
        auditViewModel.loadLogs(teamId)
    }

    // Tự động kích hoạt Intent ACTION_VIEW khi có content:// URI mở tệp
    LaunchedEffect(catalogState.openedDocumentUri) {
        val uriStr = catalogState.openedDocumentUri
        if (!uriStr.isNullOrBlank()) {
            runCatching {
                val openIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(Uri.parse(uriStr), "*/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(openIntent)
            }
            documentViewModel.clearOpenedDocumentUri()
        }
    }

    // Nếu nhóm bị giải tán, tự động quay về màn hình danh sách nhóm
    LaunchedEffect(workspaceState.isDisbanded) {
        if (workspaceState.isDisbanded) {
            onBackClick()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = workspaceState.team?.teamName ?: "Không gian làm việc",
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Quay lại"
                        )
                    }
                },
                actions = {
                    if (workspaceState.isOwner) {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(imageVector = Icons.Default.MoreVert, contentDescription = "Tùy chọn")
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Giải tán nhóm", color = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    showMenu = false
                                    showDisbandConfirm = true
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Hiển thị banner tiếp nhận chia sẻ tệp ngoài từ Android Sharesheet (SRS FR-MOB-01)
            if (pendingSharedUris.isNotEmpty()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Tiếp nhận chia sẻ ngoài",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                text = "Có ${pendingSharedUris.size} tệp tin sẵn sàng công bố vào nhóm.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        Button(
                            onClick = {
                                val publisherId = workspaceState.currentMemberId ?: "publisher"
                                documentViewModel.publishPendingFiles(teamId, publisherId)
                            }
                        ) {
                            Text("Công bố ngay")
                        }
                    }
                }
            }

            TabRow(selectedTabIndex = selectedTabIndex) {
                Tab(
                    selected = selectedTabIndex == 0,
                    onClick = { selectedTabIndex = 0 },
                    text = { Text("Tài liệu") },
                    icon = { Icon(imageVector = Icons.Default.Description, contentDescription = null) }
                )
                Tab(
                    selected = selectedTabIndex == 1,
                    onClick = { selectedTabIndex = 1 },
                    text = { Text("Thành viên") },
                    icon = { Icon(imageVector = Icons.Default.Group, contentDescription = null) }
                )
                Tab(
                    selected = selectedTabIndex == 2,
                    onClick = { selectedTabIndex = 2 },
                    text = { Text("Hoạt động") },
                    icon = { Icon(imageVector = Icons.Default.History, contentDescription = null) }
                )
            }

            Box(modifier = Modifier.fillMaxSize()) {
                when (selectedTabIndex) {
                    0 -> DocumentCatalogTab(
                        teamId = teamId,
                        currentMemberId = workspaceState.currentMemberId,
                        isOwner = workspaceState.isOwner,
                        viewModel = documentViewModel,
                        onRequestDownload = onRequestDownload,
                        onOpenDocument = { docId ->
                            documentViewModel.openDocument(docId, activity = context as? FragmentActivity)
                        }
                    )
                    1 -> MemberListTab(
                        teamId = teamId,
                        viewModel = teamViewModel,
                        onInviteQrClick = onInviteQrClick
                    )
                    2 -> ActivityLogTab(
                        teamId = teamId,
                        viewModel = auditViewModel
                    )
                }
            }
        }

        if (showDisbandConfirm) {
            ConfirmationDialog(
                title = "Giải tán nhóm vĩnh viễn?",
                message = "Cảnh báo: Hành động này sẽ xóa Master Key khỏi Android Keystore TEE, chuyển trạng thái nhóm sang DISBANDED và hủy toàn bộ phiên truyền tệp đang hoạt động. Bạn có chắc chắn?",
                confirmText = "Giải tán nhóm",
                isDestructive = true,
                onConfirm = {
                    teamViewModel.disbandTeam(teamId) {
                        showDisbandConfirm = false
                    }
                },
                onDismiss = { showDisbandConfirm = false }
            )
        }
    }
}

