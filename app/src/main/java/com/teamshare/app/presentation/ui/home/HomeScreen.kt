package com.teamshare.app.presentation.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.file_tranfer.R
import com.teamshare.app.core.platform.share.ShareCodeHelper
import com.teamshare.app.core.transfer.lan.TransferEngineState
import com.teamshare.app.presentation.ui.components.EnterCodeBottomSheet
import com.teamshare.app.presentation.viewmodel.ReceiverViewModel
import com.teamshare.app.presentation.viewmodel.TransferViewModel

/**
 * Màn hình chính ứng dụng TeamShare (Secure Local Drop).
 * Tuân thủ SRS Mục 4.1 và SDD Mục 4.2.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    transferViewModel: TransferViewModel,
    receiverViewModel: ReceiverViewModel? = null,
    onNavigateToSend: () -> Unit,
    onNavigateToScan: () -> Unit,
    onNavigateToAudit: () -> Unit,
    onNavigateToHistory: () -> Unit = {},
    onNavigateToProgress: (String) -> Unit,
    onNavigateToReceiverHome: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val transferUiState by transferViewModel.uiState.collectAsState()
    val clipboardManager = LocalClipboardManager.current
    var showEnterCodeSheet by remember { mutableStateOf(false) }
    var autoDetectedCode by remember { mutableStateOf<String?>(null) }

    // Kiểm tra Clipboard thông minh khi mở màn hình chính (Smart Clipboard Auto-Detect)
    LaunchedEffect(Unit) {
        if (receiverViewModel != null) {
            try {
                val clipText = clipboardManager.getText()?.text ?: ""
                val cleaned = ShareCodeHelper.extractPayload(clipText)
                if (cleaned.isNotBlank()) {
                    val testResult = receiverViewModel.validateShareCode(cleaned)
                    if (testResult.isSuccess) {
                        autoDetectedCode = cleaned
                    }
                }
            } catch (_: Throwable) {
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = "Logo",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "TeamShare",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Secure Local Drop",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Thẻ Trạng thái Mạng LAN
            NetworkStatusCard()

            // Banner gợi ý thông minh từ Clipboard khi phát hiện mã chia sẻ
            autoDetectedCode?.let {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showEnterCodeSheet = true },
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                            modifier = Modifier.size(38.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentPaste,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier
                                    .padding(8.dp)
                                    .size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Phát hiện mã chia sẻ trong Clipboard",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                text = "Chạm để xem thông tin và nhận tệp ngay",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                            )
                        }
                    }
                }
            }

            // Nếu đang có phiên truyền tệp dở dang, hiển thị Mini Banner
            if (transferUiState.sessionId.isNotBlank() &&
                transferUiState.state != TransferEngineState.IDLE &&
                transferUiState.state != TransferEngineState.COMPLETED
            ) {
                ActiveTransferBanner(
                    state = transferUiState,
                    onClick = { onNavigateToProgress(transferUiState.sessionId) }
                )
            }

            Text(
                text = "Hành động chia sẻ",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // 1. Hero Card: Gửi tệp tin (Sender)
            HeroActionCard(
                title = "Gửi tệp / Thư mục",
                subtitle = "Tạo mã QR phát tệp 1-N trong mạng Wi-Fi LAN",
                icon = Icons.AutoMirrored.Filled.Send,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                onClick = onNavigateToSend
            )

            // 2. Hero Card: Quét QR nhận tệp (Receiver)
            HeroActionCard(
                title = "Quét QR nhận tệp",
                subtitle = "Bật camera quét mã và tải tệp tức thời",
                icon = Icons.Default.QrCodeScanner,
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                onClick = onNavigateToScan
            )

            // 3. Action Card: Nhập mã nhận tệp (Từ xa qua Zalo/Telegram)
            HeroActionCard(
                title = "Nhập mã nhận tệp",
                subtitle = "Dán mã chia sẻ từ xa qua Zalo/Telegram (Không cần Camera)",
                icon = Icons.Default.Key,
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                onClick = { showEnterCodeSheet = true }
            )

            Text(
                text = "Bảo mật & Quản trị",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // 4. Action Card: Lịch sử truyền nhận tệp (FR-HIS-01)
            HeroActionCard(
                title = stringResource(R.string.title_transfer_history),
                subtitle = "Quản lý, mở tệp và xuất các tệp đã gửi/đã nhận",
                icon = Icons.Default.History,
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = onNavigateToHistory
            )

            // 5. Action Card: Nhật ký kiểm toán an ninh
            HeroActionCard(
                title = "Nhật ký kiểm toán an ninh",
                subtitle = "Tra cứu nhật ký bất biến & đối chiếu mã băm SHA-256",
                icon = Icons.Default.Security,
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = onNavigateToAudit
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    // Hộp thoại BottomSheet Nhập mã nhận tệp (Trực tiếp từ màn hình chính)
    if (showEnterCodeSheet && receiverViewModel != null) {
        EnterCodeBottomSheet(
            viewModel = receiverViewModel,
            onDismiss = { showEnterCodeSheet = false },
            onCodeAccepted = {
                showEnterCodeSheet = false
                onNavigateToReceiverHome?.invoke()
            },
            initialCode = autoDetectedCode ?: ""
        )
    }
}

@Composable
private fun NetworkStatusCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                modifier = Modifier.size(42.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Lan,
                    contentDescription = "Mạng nội bộ",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(10.dp)
                        .size(22.dp)
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column {
                Text(
                    text = "Mạng Wi-Fi Cục bộ (LAN TLS 1.3)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Sẵn sàng kết nối P2P tốc độ cao 25–45 MB/s",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun HeroActionCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    containerColor: androidx.compose.ui.graphics.Color,
    contentColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor
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
                color = contentColor.copy(alpha = 0.15f),
                modifier = Modifier.size(52.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = contentColor,
                    modifier = Modifier
                        .padding(12.dp)
                        .size(28.dp)
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.85f)
                )
            }
        }
    }
}

@Composable
private fun ActiveTransferBanner(
    state: com.teamshare.app.presentation.viewmodel.TransferUiState,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Lan,
                contentDescription = "Đang truyền",
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Đang truyền: ${state.fileName.ifBlank { "Tệp dữ liệu" }}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Tiến độ: ${(state.progressPercent * 100).toInt()}% • Nhấn để xem chi tiết",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

