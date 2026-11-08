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
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Security
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.teamshare.app.core.transfer.lan.TransferEngineState
import com.teamshare.app.domain.model.TransferChannel
import com.teamshare.app.presentation.ui.components.formatFileSize
import com.teamshare.app.presentation.ui.theme.LanBadgeColor
import com.teamshare.app.presentation.ui.theme.RelayBadgeColor
import com.teamshare.app.presentation.ui.theme.SecurityWarningColor
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

    val channelColor = if (uiState.channel == TransferChannel.DIRECT_LAN) LanBadgeColor else RelayBadgeColor

    if (showCancelDialog) {
        AlertDialog(
            onDismissRequest = { showCancelDialog = false },
            title = { Text("Xác nhận hủy phiên") },
            text = { Text("Bạn có chắc chắn muốn hủy phiên truyền này không? Toàn bộ tệp tạm dở dang sẽ được tiêu hủy an toàn.") },
            confirmButton = {
                Button(
                    onClick = {
                        showCancelDialog = false
                        viewModel.cancelSession(sessionId)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Hủy phiên")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showCancelDialog = false }) {
                    Text("Không")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tiến trình truyền tệp P2P") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Quay lại"
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
                        TransferEngineState.COMPLETED -> {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = LanBadgeColor,
                                modifier = Modifier.size(56.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "HOÀN TẤT",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = LanBadgeColor
                            )
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
                        Text(
                            text = "Lỗi: ${uiState.errorMessage}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            // Phần 4: Nút Mở tệp an toàn với Sinh trắc học (Khi đã Hoàn tất)
            if (uiState.state == TransferEngineState.COMPLETED) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Tệp tin đã được lưu an toàn trong Sandbox",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = {
                                val activity = context as? FragmentActivity
                                val targetFilePath = uiState.completedFilePath
                                val file = if (!targetFilePath.isNullOrBlank()) File(targetFilePath) else File(context.filesDir, "transfers/completed/${uiState.fileName}")

                                if (activity != null) {
                                    viewModel.openFileSecurely(activity, file) { success, err ->
                                        if (!success) {
                                            biometricFeedbackMessage = err ?: "Xác thực không thành công"
                                        }
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Fingerprint,
                                contentDescription = "Sinh trắc học",
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Xác thực vân tay để mở tệp")
                        }

                        if (biometricFeedbackMessage != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = biometricFeedbackMessage ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

            // Phần 5: Nút điều khiển (Tạm dừng / Tiếp tục / Hủy / Quay lại)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (uiState.state) {
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
                            Text("Hủy phiên")
                        }
                    }
                    TransferEngineState.PAUSED -> {
                        Button(
                            onClick = { viewModel.resumeSession(sessionId) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Tiếp tục")
                        }
                        OutlinedButton(
                            onClick = { showCancelDialog = true },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(imageVector = Icons.Default.Cancel, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Hủy phiên")
                        }
                    }
                    else -> {
                        Button(
                            onClick = onBackClick,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Quay về Màn hình chính")
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
