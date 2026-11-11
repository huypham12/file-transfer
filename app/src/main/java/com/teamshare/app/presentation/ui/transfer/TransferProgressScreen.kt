package com.teamshare.app.presentation.ui.transfer

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
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.example.file_tranfer.R
import com.teamshare.app.core.constant.AppConstants
import com.teamshare.app.core.transfer.lan.TransferEngineState
import com.teamshare.app.domain.model.TransferChannel
import com.teamshare.app.presentation.ui.components.formatFileSize
import com.teamshare.app.presentation.ui.components.getFileIcon
import com.teamshare.app.presentation.ui.mapper.UiErrorMapper
import com.teamshare.app.presentation.ui.theme.LanBadgeColor
import com.teamshare.app.presentation.ui.theme.RelayBadgeColor
import com.teamshare.app.presentation.ui.theme.SecurityWarningColor
import com.teamshare.app.presentation.viewmodel.FileStorageState
import com.teamshare.app.presentation.viewmodel.ReceivedFileInfo
import com.teamshare.app.presentation.viewmodel.TransferViewModel
import java.io.File

/**
 * Màn hình theo dõi tiến trình truyền tệp tốc độ cao P2P (FR-TRF-01, FR-TRF-02, FR-MOB-02).
 * Tích hợp điều tiết 100ms (NFR-PERF-03) và mở tệp an toàn bằng sinh trắc học vân tay.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferProgressScreen(
    sessionId: String,
    viewModel: TransferViewModel,
    onBackClick: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var showCancelDialog by remember { mutableStateOf(false) }
    var biometricFeedbackMessage by remember { mutableStateOf<String?>(null) }
    var exportFeedbackMessage by remember { mutableStateOf<String?>(null) }

    val channelColor = if (uiState.channel == TransferChannel.DIRECT_LAN) LanBadgeColor else RelayBadgeColor

    if (showCancelDialog) {
        AlertDialog(
            onDismissRequest = { showCancelDialog = false },
            title = { Text(stringResource(R.string.dialog_cancel_title)) },
            text = { Text(stringResource(R.string.dialog_cancel_message)) },
            confirmButton = {
                Button(
                    onClick = {
                        showCancelDialog = false
                        viewModel.cancelSession(sessionId)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.btn_confirm_cancel))
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showCancelDialog = false }) {
                    Text(stringResource(R.string.btn_keep_transfer))
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_transfer_progress)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
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
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Phần 1: Tên tệp & Đối tác phần cứng
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = uiState.fileName.ifBlank { "Tệp chia sẻ" },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(6.dp))

                if (uiState.peerDeviceModel.isNotBlank()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PhoneAndroid,
                            contentDescription = "Đối tác",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Đối tác: ${uiState.peerDeviceModel}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                }

                if (uiState.sasCode != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = "Bảo mật SAS",
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Mã xác thực SAS: ${uiState.sasCode}",
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }

            // Phần 2: Đồng hồ tròn biểu thị % tiến trình & tốc độ
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .sizeIn(minWidth = 180.dp, maxWidth = 240.dp, minHeight = 180.dp, maxHeight = 240.dp)
                    .size(220.dp)
            ) {
                CircularProgressIndicator(
                    progress = { uiState.progressPercent },
                    modifier = Modifier.fillMaxSize(),
                    strokeWidth = 14.dp,
                    color = when (uiState.state) {
                        TransferEngineState.COMPLETED -> LanBadgeColor
                        TransferEngineState.PAUSED -> SecurityWarningColor
                        TransferEngineState.FAILED -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.primary
                    },
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    when (uiState.state) {
                        TransferEngineState.WAITING_APPROVAL -> {
                            CircularProgressIndicator(
                                modifier = Modifier.size(56.dp),
                                color = MaterialTheme.colorScheme.primary,
                                strokeWidth = 4.dp
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = stringResource(R.string.transfer_status_waiting_approval),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                textAlign = TextAlign.Center
                            )
                        }
                        TransferEngineState.COMPLETED -> {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = LanBadgeColor,
                                modifier = Modifier.size(56.dp)
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "HOÀN TẤT",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = LanBadgeColor
                            )
                            if (uiState.durationText.isNotBlank()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = uiState.durationText,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        TransferEngineState.FAILED -> {
                            Icon(
                                imageVector = Icons.Default.Error,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(56.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "THẤT BẠI",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        else -> {
                            Text(
                                text = "${(uiState.progressPercent * 100).toInt()}%",
                                style = MaterialTheme.typography.headlineLarge,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 42.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = formatSpeed(uiState.speedBytesPerSec),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            // Phần 3: Thống kê dung lượng & Trạng thái chi tiết
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
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Đã chuyển:",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "${formatFileSize(uiState.transferredBytes)} / ${formatFileSize(uiState.totalBytes)}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Trạng thái:",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = formatTransferState(uiState.state),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = when (uiState.state) {
                                TransferEngineState.COMPLETED -> LanBadgeColor
                                TransferEngineState.PAUSED -> SecurityWarningColor
                                TransferEngineState.FAILED -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.primary
                            }
                        )
                    }

                    if (uiState.state == TransferEngineState.TRANSFERRING || uiState.state == TransferEngineState.PAUSED) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Thời gian còn lại (ETA):",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = formatEta(uiState.etaSeconds),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    if (uiState.state == TransferEngineState.COMPLETED && uiState.durationText.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Tổng thời gian thực tế:",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = uiState.durationText,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        if (uiState.averageSpeedBytesPerSec > 0) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Tốc độ trung bình:",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = formatSpeed(uiState.averageSpeedBytesPerSec),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Kênh truyền:",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = if (uiState.channel == TransferChannel.DIRECT_LAN) "Direct LAN (P2P TLS 1.3)" else "Zero-Knowledge Relay (E2EE)",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = channelColor
                        )
                    }

                    if (!uiState.errorMessage.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        val localizedError = uiState.errorCode?.let {
                            UiErrorMapper.getLocalizedMessageFromCode(context, it, uiState.errorMessage ?: "")
                        } ?: uiState.errorMessage ?: ""
                        Text(
                            text = localizedError,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            // Phần 4: Nút Mở tệp an toàn hoặc Duyệt danh sách tệp (Khi đã Hoàn tất)
            if (uiState.state == TransferEngineState.COMPLETED) {
                val activity = context as? FragmentActivity
                val targetFilePath = uiState.completedFilePath
                val targetFile = if (!targetFilePath.isNullOrBlank()) File(targetFilePath) else File(context.filesDir, "${AppConstants.StorageDirectory.TRANSFERS}/${AppConstants.StorageDirectory.COMPLETED}/${uiState.fileName}")

                if (uiState.isDirectoryOrBatch || targetFile.isDirectory) {
                    // TRƯỜNG HỢP NHIỀU TỆP (BATCH)
                    val savedCount = uiState.receivedFileList.count {
                        it.storageState == FileStorageState.SAVED_TO_PUBLIC || it.storageState == FileStorageState.SANDBOX_CLEANED
                    }
                    val totalBatchCount = uiState.receivedFileList.size

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // Header Thư mục / Gói tệp
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Folder,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = uiState.fileName.ifBlank { "Danh sách tệp đã nhận" },
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "$totalBatchCount tệp tin • ${formatFileSize(uiState.totalBytes)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            // Thẻ tóm tắt tiến trình lưu trữ (Storage Summary Card)
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                )
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Tiến trình lưu vào Downloads:",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            text = "$savedCount / $totalBatchCount tệp",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.Bold,
                                            color = if (savedCount == totalBatchCount && totalBatchCount > 0) LanBadgeColor else MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    LinearProgressIndicator(
                                        progress = { if (totalBatchCount > 0) savedCount.toFloat() / totalBatchCount.toFloat() else 0f },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }

                            Text(
                                text = "Chạm vào từng tệp dưới đây để mở bảo mật hoặc lưu vào Downloads:",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            // Danh sách các tệp con
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                uiState.receivedFileList.forEach { item ->
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(10.dp),
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                        )
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 12.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = getFileIcon(item.name),
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(22.dp)
                                            )
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = item.name,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.SemiBold,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Row(
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        text = formatFileSize(item.sizeBytes),
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                    Text(
                                                        text = when (item.storageState) {
                                                            FileStorageState.SAVED_TO_PUBLIC -> "• Đã lưu Downloads"
                                                            FileStorageState.SANDBOX_CLEANED -> "• Đã dọn Sandbox"
                                                            else -> "• Trong App"
                                                        },
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = if (item.storageState == FileStorageState.SAVED_TO_PUBLIC) LanBadgeColor else MaterialTheme.colorScheme.secondary
                                                    )
                                                }
                                            }

                                            // Nút mở bảo mật qua vân tay
                                            FilledTonalButton(
                                                onClick = {
                                                    if (activity != null) {
                                                        viewModel.openFileSecurely(activity, File(item.absolutePath)) { success, err ->
                                                            if (!success) {
                                                                biometricFeedbackMessage = err ?: "Xác thực không thành công"
                                                            }
                                                        }
                                                    }
                                                },
                                                shape = RoundedCornerShape(8.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Fingerprint,
                                                    contentDescription = "Mở",
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }

                                            Spacer(modifier = Modifier.width(4.dp))

                                            // Nút lưu lẻ từng tệp (không disable khi đã lưu; bấm lại sẽ hỏi xác nhận)
                                            IconButton(
                                                onClick = {
                                                    viewModel.exportChildFileSmart(context, item, forceOverwrite = false)
                                                }
                                            ) {
                                                Icon(
                                                    imageVector = if (item.storageState == FileStorageState.SAVED_TO_PUBLIC || item.storageState == FileStorageState.SANDBOX_CLEANED) Icons.Default.Done else Icons.Default.Download,
                                                    contentDescription = "Lưu tệp",
                                                    tint = if (item.storageState == FileStorageState.SAVED_TO_PUBLIC) LanBadgeColor else MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            // Nút lưu tất cả các tệp vào Downloads (Smart Idempotent)
                            val remainingToSave = totalBatchCount - savedCount
                            val actionBtnText = if (remainingToSave > 0) {
                                "Lưu tất cả vào Downloads ($remainingToSave tệp chưa lưu)"
                            } else {
                                "Lưu lại toàn bộ vào Downloads"
                            }

                            Button(
                                onClick = {
                                    viewModel.exportAllBatchSmart(context, targetFile, forceOverwriteAll = false, onlyNewFiles = false)
                                },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Download,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(actionBtnText, fontWeight = FontWeight.Bold)
                            }

                            // Nút dọn dẹp các tệp Sandbox đã lưu an toàn
                            if (savedCount > 0) {
                                OutlinedButton(
                                    onClick = {
                                        viewModel.cleanupExportedSandboxFiles(context)
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Dọn dẹp bản sao tạm trong App ($savedCount tệp đã có ở Downloads)")
                                }
                            }

                            if (biometricFeedbackMessage != null) {
                                Text(
                                    text = biometricFeedbackMessage ?: "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            val feedback = uiState.exportFeedbackMessage ?: exportFeedbackMessage
                            if (feedback != null) {
                                Text(
                                    text = feedback,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = LanBadgeColor,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                } else {
                    // TRƯỜNG HỢP 1 TỆP PHẲNG (SINGLE FILE)
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = when (uiState.singleFileStorageState) {
                                    FileStorageState.SAVED_TO_PUBLIC -> "Tệp đã được lưu an toàn vào Downloads"
                                    FileStorageState.SANDBOX_CLEANED -> "Đã giải phóng Sandbox (Tệp sẵn sàng tại Downloads)"
                                    else -> "Tệp tin đã được lưu an toàn trong Sandbox"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(4.dp))

                            // Nút 1: Xác thực vân tay mở tệp (Bảo mật cao)
                            Button(
                                onClick = {
                                    if (activity != null) {
                                        viewModel.openFileSecurely(activity, targetFile) { success, err ->
                                            if (!success) {
                                                biometricFeedbackMessage = err ?: "Xác thực không thành công"
                                            }
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Security,
                                    contentDescription = "Bảo mật",
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Xác thực bảo mật để mở tệp")
                            }

                            // Nút 2: Lưu vào thư mục Downloads của máy (Luôn cho phép bấm, hỏi xác nhận nếu trùng)
                            OutlinedButton(
                                onClick = {
                                    viewModel.exportSingleFileSmart(context, targetFile, forceOverwrite = false)
                                },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    imageVector = if (uiState.singleFileStorageState == FileStorageState.SAVED_TO_PUBLIC || uiState.singleFileStorageState == FileStorageState.SANDBOX_CLEANED) Icons.Default.Done else Icons.Default.Download,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (uiState.singleFileStorageState == FileStorageState.SAVED_TO_PUBLIC || uiState.singleFileStorageState == FileStorageState.SANDBOX_CLEANED) {
                                        "Đã có trong Downloads (Bấm để tải lại)"
                                    } else {
                                        "Lưu vào thư mục Tải về (Downloads)"
                                    }
                                )
                            }

                            // Banner Gợi ý Dọn dẹp Sandbox khi tệp đã lưu ra Downloads
                            if (uiState.singleFileStorageState == FileStorageState.SAVED_TO_PUBLIC) {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = "Gợi ý: Tệp đã có trong thư mục Downloads của máy. Bạn có muốn dọn dẹp bản lưu tạm trong TeamShare để giải phóng ${formatFileSize(targetFile.length())} bộ nhớ không?",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.End
                                        ) {
                                            FilledTonalButton(
                                                onClick = { viewModel.cleanupExportedSandboxFiles(context) },
                                                shape = RoundedCornerShape(8.dp)
                                            ) {
                                                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("Xóa bản tạm", style = MaterialTheme.typography.labelMedium)
                                            }
                                        }
                                    }
                                }
                            }

                            if (biometricFeedbackMessage != null) {
                                Text(
                                    text = biometricFeedbackMessage ?: "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    textAlign = TextAlign.Center
                                )
                            }

                            val singleFeedback = uiState.exportFeedbackMessage ?: exportFeedbackMessage
                            if (singleFeedback != null) {
                                Text(
                                    text = singleFeedback,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = LanBadgeColor,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }

                // DIALOG 1: XÁC NHẬN TẢI LẠI TỆP TRÙNG LẶP (SINGLE FILE DUPLICATE DIALOG)
                if (uiState.showSingleDuplicateDialog) {
                    val dupFile = uiState.duplicateFileToConfirm
                    val targetName = dupFile?.name ?: uiState.fileName
                    AlertDialog(
                        onDismissRequest = { viewModel.dismissDuplicateDialog() },
                        icon = { Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                        title = { Text("Tệp đã có trong máy") },
                        text = {
                            Text("Tệp '$targetName' đã có sẵn trong thư mục Download/TeamShare. Bạn có muốn lưu lần nữa không?")
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    if (uiState.isDirectoryOrBatch && dupFile != null) {
                                        viewModel.exportChildFileSmart(context, dupFile, forceOverwrite = true)
                                    } else {
                                        viewModel.exportSingleFileSmart(context, targetFile, forceOverwrite = true)
                                    }
                                }
                            ) {
                                Text("Lưu lần nữa")
                            }
                        },
                        dismissButton = {
                            OutlinedButton(onClick = { viewModel.dismissDuplicateDialog() }) {
                                Text("Hủy")
                            }
                        }
                    )
                }

                // DIALOG 2: XÁC NHẬN TẢI LẠI DANH SÁCH NHIỀU TỆP TRÙNG LẶP (BATCH DUPLICATE DIALOG)
                if (uiState.showBatchDuplicateDialog) {
                    AlertDialog(
                        onDismissRequest = { viewModel.dismissDuplicateDialog() },
                        icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = SecurityWarningColor) },
                        title = { Text("Phát hiện tệp trùng lặp") },
                        text = {
                            Text("Phát hiện ${uiState.pendingBatchDuplicates.size} tệp đã có sẵn trong thư mục Downloads/TeamShare. Bạn muốn bỏ qua các tệp đã có hay tải lại toàn bộ?")
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    viewModel.exportAllBatchSmart(context, targetFile, forceOverwriteAll = false, onlyNewFiles = true)
                                }
                            ) {
                                Text("Chỉ tải tệp mới")
                            }
                        },
                        dismissButton = {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { viewModel.dismissDuplicateDialog() }) {
                                    Text("Hủy")
                                }
                                Button(
                                    onClick = {
                                        viewModel.exportAllBatchSmart(context, targetFile, forceOverwriteAll = true, onlyNewFiles = false)
                                    }
                                ) {
                                    Text("Tải lại tất cả")
                                }
                            }
                        }
                    )
                }

                // DIALOG 3: GỢI Ý DỌN DẸP BẢN SAO TẠM SANDBOX SAU KHI XUẤT HÀNG LOẠT
                if (uiState.showCleanupSuggestionDialog && uiState.isDirectoryOrBatch) {
                    AlertDialog(
                        onDismissRequest = { viewModel.dismissCleanupDialog() },
                        icon = { Icon(Icons.Default.CheckCircle, contentDescription = null, tint = LanBadgeColor) },
                        title = { Text("Giải phóng bộ nhớ ứng dụng?") },
                        text = {
                            val cleanSize = if (uiState.cleanableBytes > 0) formatFileSize(uiState.cleanableBytes) else formatFileSize(uiState.totalBytes)
                            Text("Các tệp đã được lưu an toàn vào thư mục Downloads của thiết bị!\n\nBản sao tạm trong ứng dụng đang chiếm $cleanSize bộ nhớ. Bạn có muốn xóa sạch các bản tạm này để giải phóng bộ nhớ không?")
                        },
                        confirmButton = {
                            Button(
                                onClick = { viewModel.cleanupExportedSandboxFiles(context) }
                            ) {
                                Text("Xóa bản tạm")
                            }
                        },
                        dismissButton = {
                            OutlinedButton(onClick = { viewModel.dismissCleanupDialog() }) {
                                Text("Để lại trong App")
                            }
                        }
                    )
                }
            }

            // Phần 5: Nút điều khiển (Tạm dừng / Tiếp tục / Hủy / Quay lại)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (uiState.state) {
                    TransferEngineState.WAITING_APPROVAL -> {
                        OutlinedButton(
                            onClick = { showCancelDialog = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(imageVector = Icons.Default.Cancel, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.btn_confirm_cancel))
                        }
                    }
                    TransferEngineState.TRANSFERRING, TransferEngineState.HANDSHAKING -> {
                        OutlinedButton(
                            onClick = { viewModel.pauseSession(sessionId) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(imageVector = Icons.Default.Pause, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Tạm dừng")
                        }
                        Button(
                            onClick = { showCancelDialog = true },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(imageVector = Icons.Default.Cancel, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.btn_confirm_cancel))
                        }
                    }
                    TransferEngineState.PAUSED -> {
                        Button(
                            onClick = { viewModel.resumeSession(sessionId) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.action_resume))
                        }
                        OutlinedButton(
                            onClick = { showCancelDialog = true },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(imageVector = Icons.Default.Cancel, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.btn_confirm_cancel))
                        }
                    }
                    else -> {
                        Button(
                            onClick = onBackClick,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.btn_return_home))
                        }
                    }
                }
            }
        }
    }
}

fun formatSpeed(speedBytesPerSec: Long): String {
    if (speedBytesPerSec <= 0) return "0 KB/s"
    val mb = speedBytesPerSec / (1024.0 * 1024.0)
    return if (mb >= 1.0) {
        String.format("%.1f MB/s", mb)
    } else {
        val kb = speedBytesPerSec / 1024.0
        String.format("%.0f KB/s", kb)
    }
}

fun formatTransferState(state: TransferEngineState): String {
    return when (state) {
        TransferEngineState.IDLE -> "Sẵn sàng"
        TransferEngineState.CONNECTING -> "Đang thiết lập kết nối..."
        TransferEngineState.WAITING_APPROVAL -> "Đang chờ người gửi phê duyệt..."
        TransferEngineState.HANDSHAKING -> "Đang bắt tay TLS/E2EE..."
        TransferEngineState.TRANSFERRING -> "Đang truyền dữ liệu..."
        TransferEngineState.PAUSED -> "Đã tạm dừng an toàn"
        TransferEngineState.VERIFYING -> "Đang đối chiếu SHA-256..."
        TransferEngineState.COMPLETED -> "Truyền nhận thành công"
        TransferEngineState.FAILED -> "Lỗi truyền tải"
        TransferEngineState.CANCELLED -> "Đã hủy phiên"
    }
}

fun formatEta(seconds: Long): String {
    if (seconds <= 0L) return "Đang tính toán..."
    val mins = seconds / 60
    val secs = seconds % 60
    return if (mins > 0) {
        String.format("%d phút %02d giây", mins, secs)
    } else {
        String.format("%d giây", secs)
    }
}
