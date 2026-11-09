package com.teamshare.app.presentation.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.example.file_tranfer.R
import com.teamshare.app.domain.model.SessionStatus
import com.teamshare.app.domain.model.TransferSession
import com.teamshare.app.domain.model.TransferType
import com.teamshare.app.presentation.ui.components.formatFileSize
import com.teamshare.app.presentation.ui.components.getFileIcon
import com.teamshare.app.presentation.ui.theme.LanBadgeColor
import com.teamshare.app.presentation.ui.theme.RelayBadgeColor
import com.teamshare.app.presentation.ui.theme.SecurityWarningColor
import com.teamshare.app.presentation.viewmodel.HistoryTab
import com.teamshare.app.presentation.viewmodel.HistoryViewModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * Màn hình Quản lý lịch sử truyền nhận tệp (FR-HIS-01).
 * Cho phép xem chi tiết tệp đã gửi/đã nhận, mở tệp và lưu vào Downloads.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferHistoryScreen(
    viewModel: HistoryViewModel,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    var folderDialogSession by remember { mutableStateOf<Pair<TransferSession, List<File>>?>(null) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_transfer_history)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.loadHistory() }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.action_refresh)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        modifier = modifier
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // TabRow: Tệp đã nhận vs Tệp đã gửi
            TabRow(
                selectedTabIndex = if (uiState.selectedTab == HistoryTab.RECEIVED) 0 else 1,
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                Tab(
                    selected = uiState.selectedTab == HistoryTab.RECEIVED,
                    onClick = { viewModel.selectTab(HistoryTab.RECEIVED) },
                    text = {
                        Text(
                            text = "${stringResource(R.string.history_tab_received)} (${uiState.receivedSessions.size})",
                            fontWeight = if (uiState.selectedTab == HistoryTab.RECEIVED) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                )
                Tab(
                    selected = uiState.selectedTab == HistoryTab.SENT,
                    onClick = { viewModel.selectTab(HistoryTab.SENT) },
                    text = {
                        Text(
                            text = "${stringResource(R.string.history_tab_sent)} (${uiState.sentSessions.size})",
                            fontWeight = if (uiState.selectedTab == HistoryTab.SENT) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                )
            }

            val currentList = if (uiState.selectedTab == HistoryTab.RECEIVED) {
                uiState.receivedSessions
            } else {
                uiState.sentSessions
            }

            if (uiState.isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (currentList.isEmpty()) {
                val emptyTitle = if (uiState.selectedTab == HistoryTab.RECEIVED) {
                    stringResource(R.string.history_empty_received)
                } else {
                    stringResource(R.string.history_empty_sent)
                }
                val emptyDesc = if (uiState.selectedTab == HistoryTab.RECEIVED) {
                    stringResource(R.string.history_empty_received_desc)
                } else {
                    stringResource(R.string.history_empty_sent_desc)
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.History,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = emptyTitle,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = emptyDesc,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(currentList, key = { it.sessionId }) { session ->
                        HistorySessionCard(
                            session = session,
                            onOpenFile = {
                                val resolvedFile = viewModel.resolveSessionFile(activity, session)
                                if (resolvedFile != null && resolvedFile.isDirectory) {
                                    val childFiles = resolvedFile.listFiles()?.filterNot { it.name.startsWith(".") }?.sortedBy { it.name } ?: emptyList()
                                    folderDialogSession = Pair(session, childFiles)
                                } else {
                                    if (activity != null) {
                                        viewModel.openFileSecurely(activity, session) { success, msg ->
                                            if (!success && msg != null) {
                                                coroutineScope.launch {
                                                    snackbarHostState.showSnackbar(msg)
                                                }
                                            }
                                        }
                                    }
                                }
                            },
                            onSaveToDownloads = {
                                viewModel.exportFileToDownloads(context, session) { success, msg ->
                                    if (msg != null) {
                                        coroutineScope.launch {
                                            snackbarHostState.showSnackbar(msg)
                                        }
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }

        if (folderDialogSession != null) {
            val (session, files) = folderDialogSession!!
            val isFolder = session.transferType == TransferType.FOLDER_ZIP
            val titleText = if (isFolder) {
                stringResource(R.string.dialog_folder_files_title, session.docId.ifBlank { "Folder" })
            } else {
                stringResource(R.string.dialog_batch_files_title, files.size)
            }

            AlertDialog(
                onDismissRequest = { folderDialogSession = null },
                title = {
                    Text(
                        text = titleText,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Tổng cộng: ${files.size} tệp tin. Bấm để mở hoặc lưu từng tệp:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(files, key = { it.absolutePath }) { subFile ->
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(8.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 8.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = getFileIcon(subFile.name),
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = subFile.name,
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = formatFileSize(subFile.length()),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        IconButton(
                                            onClick = {
                                                viewModel.exportSingleChildFile(context, subFile) { success, msg ->
                                                    if (msg != null) {
                                                        coroutineScope.launch {
                                                            snackbarHostState.showSnackbar(msg)
                                                        }
                                                    }
                                                }
                                            },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Download,
                                                contentDescription = "Lưu tệp",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                        FilledTonalButton(
                                            onClick = {
                                                folderDialogSession = null
                                                if (activity != null) {
                                                    viewModel.openFileSecurely(activity, subFile) { success, msg ->
                                                        if (!success && msg != null) {
                                                            coroutineScope.launch {
                                                                snackbarHostState.showSnackbar(msg)
                                                            }
                                                        }
                                                    }
                                                }
                                            },
                                            shape = RoundedCornerShape(6.dp),
                                            modifier = Modifier.padding(start = 4.dp)
                                        ) {
                                            Text("Mở", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            }
                        }

                        // Nút lưu / giải nén toàn bộ
                        OutlinedButton(
                            onClick = {
                                folderDialogSession = null
                                viewModel.exportFileToDownloads(context, session) { success, msg ->
                                    if (msg != null) {
                                        coroutineScope.launch {
                                            snackbarHostState.showSnackbar(msg)
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isFolder) stringResource(R.string.btn_extract_folder) else stringResource(R.string.btn_save_all_to_downloads),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { folderDialogSession = null }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            )
        }
    }
}

@Composable
private fun HistorySessionCard(
    session: TransferSession,
    onOpenFile: () -> Unit,
    onSaveToDownloads: () -> Unit
) {
    val dateFormatter = remember { SimpleDateFormat("HH:mm dd/MM/yyyy", Locale.getDefault()) }
    val dateText = try {
        dateFormatter.format(Date(session.startedAt))
    } catch (_: Throwable) {
        "N/A"
    }

    val (statusText, statusColor) = when (session.status) {
        SessionStatus.COMPLETED -> stringResource(R.string.history_status_completed) to LanBadgeColor
        SessionStatus.FAILED -> stringResource(R.string.history_status_failed) to MaterialTheme.colorScheme.error
        SessionStatus.CANCELLED -> stringResource(R.string.history_status_cancelled) to SecurityWarningColor
        else -> stringResource(R.string.history_status_in_progress) to MaterialTheme.colorScheme.primary
    }

    val isFolder = session.transferType == TransferType.FOLDER_ZIP
    val isBatch = session.transferType == TransferType.BATCH_FILES
    val channelText = if (session.channel.isLan) {
        stringResource(R.string.history_channel_lan)
    } else {
        stringResource(R.string.history_channel_relay)
    }

    val fileName = session.docId.ifBlank {
        when {
            isFolder -> stringResource(R.string.history_type_folder)
            isBatch -> "Gói nhiều tệp"
            else -> "Tệp chia sẻ"
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            // Hàng 1: Icon tệp + Tên tệp + Badge trạng thái
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (isFolder) Icons.Default.Folder else Icons.Default.InsertDriveFile,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = fileName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${formatFileSize(session.totalBytes)} • $dateText",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Status Badge
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = statusColor.copy(alpha = 0.15f),
                    modifier = Modifier.padding(start = 6.dp)
                ) {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = statusColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Hàng 2: Thông tin Thiết bị đối tác + Kênh truyền
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val peerName = session.peerDeviceModel.ifBlank { "Thiết bị khác" }
                Text(
                    text = stringResource(R.string.history_peer_label, peerName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = if (session.channel.isLan) LanBadgeColor.copy(alpha = 0.12f) else RelayBadgeColor.copy(alpha = 0.12f)
                ) {
                    Text(
                        text = channelText,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (session.channel.isLan) LanBadgeColor else RelayBadgeColor,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            // Hàng 3: Các nút hành động (chỉ hiện khi thành công)
            if (session.status == SessionStatus.COMPLETED) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val openBtnText = when {
                        isFolder || isBatch -> stringResource(R.string.btn_browse_files)
                        else -> stringResource(R.string.history_btn_open)
                    }
                    val saveBtnText = when {
                        isFolder -> stringResource(R.string.btn_extract_folder)
                        isBatch -> stringResource(R.string.btn_save_all_to_downloads)
                        else -> stringResource(R.string.history_btn_save_download)
                    }

                    FilledTonalButton(
                        onClick = onOpenFile,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = openBtnText,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1
                        )
                    }

                    OutlinedButton(
                        onClick = onSaveToDownloads,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = saveBtnText,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

