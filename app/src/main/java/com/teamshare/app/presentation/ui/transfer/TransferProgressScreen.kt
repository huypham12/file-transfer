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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Security
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.teamshare.app.core.transfer.lan.TransferEngineState
import com.teamshare.app.domain.model.TransferChannel
import com.teamshare.app.presentation.ui.theme.LanBadgeColor
import com.teamshare.app.presentation.ui.theme.RelayBadgeColor
import com.teamshare.app.presentation.ui.workspace.formatFileSize
import com.teamshare.app.presentation.viewmodel.TransferViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferProgressScreen(
    sessionId: String,
    viewModel: TransferViewModel,
    onBackClick: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

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
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Phần 1: Tên tệp & Kênh truyền
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = uiState.fileName.ifBlank { "Tệp truyền tải" },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))

                // Huy hiệu kênh truyền LAN hoặc Relay
                val (channelColor, channelTitle, channelIcon) = if (uiState.channel == TransferChannel.DIRECT_LAN) {
                    Triple(LanBadgeColor, "Direct LAN (P2P Socket TLS 1.3)", Icons.Default.Lan)
                } else {
                    Triple(RelayBadgeColor, "Zero-Knowledge Relay (E2EE Internet)", Icons.Default.Hub)
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .background(channelColor.copy(alpha = 0.15f), shape = RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = channelIcon,
                        contentDescription = null,
                        tint = channelColor,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = channelTitle,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = channelColor
                    )
                }

                // Mã SAS (nếu là kênh Relay E2EE)
                if (!uiState.sasCode.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .background(Color(0xFFEDE7F6), shape = RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = null,
                            tint = Color(0xFF512DA8),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Mã xác thực SAS: ${uiState.sasCode}",
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF512DA8)
                        )
                    }
                }
            }

            // Phần 2: Đồng hồ tròn biểu thị % tiến trình & tốc độ
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(240.dp)
            ) {
                CircularProgressIndicator(
                    progress = { uiState.progressPercent },
                    modifier = Modifier.fillMaxSize(),
                    strokeWidth = 14.dp,
                    color = when (uiState.state) {
                        TransferEngineState.COMPLETED -> LanBadgeColor
                        TransferEngineState.PAUSED -> Color(0xFFEF6C00)
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
                                TransferEngineState.PAUSED -> Color(0xFFEF6C00)
                                TransferEngineState.FAILED -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.primary
                            }
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

            // Phần 4: Nút điều khiển (Tạm dừng / Tiếp tục / Hủy / Quay lại)
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
                            onClick = { viewModel.cancelSession(sessionId) },
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
                            onClick = { viewModel.cancelSession(sessionId) },
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
                            Text("Quay về Không gian nhóm")
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

