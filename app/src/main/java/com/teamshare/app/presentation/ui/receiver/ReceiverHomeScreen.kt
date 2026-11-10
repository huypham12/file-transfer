package com.teamshare.app.presentation.ui.receiver

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.file_tranfer.R
import com.teamshare.app.core.constant.AppConstants
import com.teamshare.app.core.platform.network.NetworkConfig
import com.teamshare.app.domain.model.TransferType
import com.teamshare.app.presentation.ui.components.ErrorBanner
import com.teamshare.app.presentation.ui.components.formatFileSize
import com.teamshare.app.presentation.viewmodel.ReceiverConnectionState
import com.teamshare.app.presentation.viewmodel.ReceiverViewModel
import com.teamshare.app.presentation.viewmodel.TransferViewModel
import java.io.File

/**
 * Màn hình xem trước siêu dữ liệu tệp và đối chiếu dung lượng đĩa trống máy nhận (FR-RCV-02, FR-RCV-03).
 * Tuân thủ SDD Mục 4.2.1 (ReceiverHomeScreen).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiverHomeScreen(
    viewModel: ReceiverViewModel,
    transferViewModel: TransferViewModel? = null,
    onBackClick: () -> Unit,
    onStartTransfer: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val payload = uiState.qrPayload

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_receiver_home)) },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.cancelDownload()
                        onBackClick()
                    }) {
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
        },
        bottomBar = {
            Surface(
                shadowElevation = 8.dp,
                color = MaterialTheme.colorScheme.surface
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            viewModel.cancelDownload()
                            onBackClick()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(stringResource(R.string.btn_reject))
                    }

                    Button(
                        onClick = {
                            val targetDir = File(context.filesDir, "${AppConstants.StorageDirectory.TRANSFERS}/${AppConstants.StorageDirectory.COMPLETED}").apply { mkdirs() }
                            val sId = uiState.pendingSessionId ?: payload?.sessionId ?: "session_recv"
                            val fileName = if (payload != null && payload.fileName.isNotBlank()) {
                                if (payload.itemCount > 1 && payload.transferType == TransferType.BATCH_FILES) {
                                    "${payload.itemCount} tệp (${payload.fileName}...)"
                                } else {
                                    payload.fileName
                                }
                            } else if (payload != null && payload.itemCount > 1) {
                                "batch_${sId.take(8)}.zip"
                            } else if (payload?.transferType == TransferType.FOLDER_ZIP) {
                                "folder_${sId.take(8)}.zip"
                            } else {
                                "file_${sId.take(8)}"
                            }

                            // 1. Khởi tạo trạng thái phiên trên TransferViewModel
                            if (transferViewModel != null && payload != null) {
                                transferViewModel.initTransferSession(
                                    sessionId = sId,
                                    fileName = fileName,
                                    totalBytes = payload.totalBytes,
                                    peerDeviceModel = payload.senderDeviceModel,
                                    channel = payload.channel
                                )
                            }

                            // 2. Tạo ProgressCallback liên kết với TransferViewModel
                            val progressCallback = transferViewModel?.createTransferCallback(
                                sessionId = sId,
                                fileName = fileName
                            )

                            // 3. Bắt đầu phiên tải tệp với callback tiến độ
                            viewModel.requestConnection(
                                targetDir = targetDir,
                                callback = progressCallback,
                                onConnected = { downloadedFile ->
                                    transferViewModel?.onTransferFinished(downloadedFile)
                                }
                            )

                            // 4. Chuyển hướng sang màn hình theo dõi tiến trình
                            onStartTransfer(sId)
                        },
                        enabled = uiState.isStorageSufficient && uiState.connectionState != ReceiverConnectionState.CONNECTING,
                        modifier = Modifier
                            .weight(2f)
                            .height(50.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        if (uiState.connectionState == ReceiverConnectionState.CONNECTING) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.5.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.msg_connecting))
                        } else {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = stringResource(R.string.btn_accept_download),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.btn_accept_download),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        },
        modifier = modifier
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            val activeError = uiState.errorResId?.let { stringResource(it) } ?: uiState.errorMessage
            if (activeError != null) {
                ErrorBanner(
                    message = activeError,
                    onDismiss = { viewModel.clearError() }
                )
            }

            // Card Thông tin tệp tin nhận
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(56.dp)
                    ) {
                        val isBatch = payload?.transferType == TransferType.BATCH_FILES || (payload != null && payload.itemCount > 1) || payload?.transferType == TransferType.FOLDER_ZIP
                        Icon(
                            imageVector = if (isBatch) Icons.Default.FolderZip else Icons.Default.InsertDriveFile,
                            contentDescription = "File Type",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier
                                .padding(12.dp)
                                .size(32.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        val displayName = if (payload != null && payload.itemCount > 1) {
                            stringResource(R.string.receiver_batch_files, payload.itemCount)
                        } else if (payload?.transferType == TransferType.BATCH_FILES) {
                            stringResource(R.string.receiver_batch_files, 1)
                        } else {
                            stringResource(R.string.receiver_single_file)
                        }
                        Text(
                            text = displayName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.label_file_size, formatFileSize(payload?.totalBytes ?: 0L)),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            // Card Đo đạc bộ nhớ máy nhận (StatFs)
            val isSufficient = uiState.isStorageSufficient
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isSufficient) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                    } else {
                        MaterialTheme.colorScheme.errorContainer
                    }
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isSufficient) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = null,
                        tint = if (isSufficient) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Column {
                        Text(
                            text = if (isSufficient) stringResource(R.string.storage_sufficient_title) else stringResource(R.string.storage_insufficient_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (isSufficient) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.storage_free_info, formatFileSize(uiState.availableBytes), formatFileSize(payload?.totalBytes ?: 0L)),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isSufficient) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                        )
                    }
                }
            }

            // Card Thông tin Mạng & Bên gửi
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = stringResource(R.string.title_channel_details),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(stringResource(R.string.label_channel_type), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            text = stringResource(R.string.channel_wifi_lan),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(stringResource(R.string.label_host), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            text = "${payload?.senderIp ?: NetworkConfig.DEFAULT_FALLBACK_IP}:${payload?.senderPort ?: NetworkConfig.DEFAULT_LAN_PORT}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(stringResource(R.string.label_session_token), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            text = "${payload?.sessionToken?.take(16)}...",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}
