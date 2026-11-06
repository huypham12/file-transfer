package com.teamshare.app.presentation.ui.team.qr

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.HourglassDisabled
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.platform.LocalContext
import com.teamshare.app.core.platform.network.NetworkAddressHelper
import com.teamshare.app.domain.model.QrInvitePayload
import com.teamshare.app.presentation.ui.components.ErrorBanner
import com.teamshare.app.presentation.viewmodel.TeamViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrInviteScreen(
    teamId: String,
    viewModel: TeamViewModel,
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val uiState by viewModel.qrInviteUiState.collectAsState()
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var showNetworkConfigDialog by remember { mutableStateOf(false) }
    var relayConfigInput by remember { mutableStateOf(NetworkAddressHelper.configuredRelayHost) }

    LaunchedEffect(teamId) {
        viewModel.generateInviteQr(teamId)
    }

    // Sinh bitmap QR bất đồng bộ trên Dispatchers.Default khi có payload mới
    LaunchedEffect(uiState.payload) {
        uiState.payload?.let { payload ->
            withContext(Dispatchers.Default) {
                // Mã hóa chuỗi QR theo chuẩn định dạng chia tách dấu gạch đứng
                val qrContent = serializeQrPayload(payload)
                qrBitmap = QrCodeBitmapGenerator.generateQrBitmap(qrContent, size = 600)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Mời thành viên qua mã QR") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Quay lại"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showNetworkConfigDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Cấu hình mạng Relay"
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
            verticalArrangement = Arrangement.Center
        ) {
            if (uiState.errorMessage != null) {
                ErrorBanner(
                    message = uiState.errorMessage!!,
                    onDismiss = { viewModel.clearErrors() }
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            Text(
                text = "Quét để gia nhập nhóm",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Mã QR được ký số bởi Trưởng nhóm. Thành viên dùng ứng dụng TeamShare quét mã này để nhận chứng chỉ.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Khung hiển thị mã QR
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
                modifier = Modifier
                    .size(280.dp)
                    .border(2.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(24.dp))
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    if (uiState.isLoading || (qrBitmap == null && !uiState.isExpired)) {
                        CircularProgressIndicator(modifier = Modifier.size(48.dp))
                    } else if (qrBitmap != null) {
                        Image(
                            bitmap = qrBitmap!!.asImageBitmap(),
                            contentDescription = "Mã QR lời mời",
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp)
                                .alpha(if (uiState.isExpired) 0.15f else 1.0f)
                        )
                        if (uiState.isExpired) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(16.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.HourglassDisabled,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "MÃ ĐÃ HẾT HẠN",
                                    color = MaterialTheme.colorScheme.error,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Đồng hồ đếm ngược TTL
            val minutes = uiState.remainingSeconds / 60
            val seconds = uiState.remainingSeconds % 60
            val timeFormatted = String.format("%02d:%02d", minutes, seconds)

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier
                    .background(
                        color = if (uiState.isExpired) MaterialTheme.colorScheme.errorContainer
                        else MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(12.dp)
                    )
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Icon(
                    imageVector = if (uiState.isExpired) Icons.Default.HourglassDisabled else Icons.Default.HourglassBottom,
                    contentDescription = null,
                    tint = if (uiState.isExpired) MaterialTheme.colorScheme.onErrorContainer
                    else MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (uiState.isExpired) "Thời hạn: Đã hết hạn" else "Thời hạn hiệu lực: $timeFormatted",
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = if (uiState.isExpired) MaterialTheme.colorScheme.onErrorContainer
                    else MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            uiState.payload?.let { p ->
                val connInfo = if (!p.ownerHost.isNullOrBlank()) "${p.ownerHost}:${p.ownerPort}" else "Tự động phát hiện"
                val relayInfo = if (!p.relayUrl.isNullOrBlank()) p.relayUrl else "Chưa cấu hình"
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Điểm kết nối LAN: $connInfo",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "Kênh Relay: $relayInfo",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Nút làm mới mã QR
            Button(
                onClick = { viewModel.generateInviteQr(teamId) },
                enabled = !uiState.isLoading,
                modifier = Modifier.fillMaxWidth(0.8f)
            ) {
                Icon(imageVector = Icons.Default.Refresh, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = if (uiState.isExpired) "Tạo lại mã mới" else "Làm mới mã QR")
            }
        }

        // Hộp thoại cấu hình nâng cao Máy chủ Relay (Tùy chọn dành cho kiểm thử đa mạng)
        if (showNetworkConfigDialog) {
            AlertDialog(
                onDismissRequest = { showNetworkConfigDialog = false },
                title = { Text("Cấu hình Máy chủ Relay", fontWeight = FontWeight.Bold) },
                text = {
                    Column {
                        Text(
                            text = "Hệ thống tự động ưu tiên truyền trực tiếp qua Wi-Fi LAN. Khi hai máy khác mạng (4G hoặc qua máy ảo Emulator), kết nối sẽ chuyển tiếp qua Zero-Knowledge Relay:",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = relayConfigInput,
                            onValueChange = { relayConfigInput = it },
                            label = { Text("Địa chỉ IP / Host Relay") },
                            placeholder = { Text("ví dụ: 192.168.1.8") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val clean = relayConfigInput.trim()
                            NetworkAddressHelper.saveConfiguredRelayHost(context, clean)
                            showNetworkConfigDialog = false
                            viewModel.generateInviteQr(teamId)
                        }
                    ) {
                        Text("Áp dụng")
                    }
                },
                dismissButton = {
                    OutlinedButton(onClick = { showNetworkConfigDialog = false }) {
                        Text("Đóng")
                    }
                }
            )
        }
    }
}

/**
 * Đóng gói payload thành chuỗi định dạng TeamShare QR Protocol.
 */
fun serializeQrPayload(payload: QrInvitePayload): String {
    val sb = StringBuilder("teamshare://invite?teamId=${payload.teamId}")
    sb.append("&token=").append(payload.invitationToken)
    sb.append("&expiresAt=").append(payload.expiresAt)
    sb.append("&ownerPubKey=").append(payload.ownerPublicKey)
    sb.append("&sig=").append(payload.signature)
    if (payload.teamName.isNotBlank()) {
        sb.append("&name=").append(payload.teamName)
    }
    if (!payload.ownerHost.isNullOrBlank()) {
        sb.append("&host=").append(payload.ownerHost)
    }
    if (payload.ownerPort > 0) {
        sb.append("&port=").append(payload.ownerPort)
    }
    if (!payload.relayUrl.isNullOrBlank()) {
        sb.append("&relay=").append(payload.relayUrl)
    }
    return sb.toString()
}

/**
 * Phân tích chuỗi mã QR nhận được thành đối tượng QrInvitePayload.
 * Sử dụng giải mã tham số thuần túy để tương thích 100% trên cả Android Runtime và máy ảo JVM.
 */
fun parseQrPayload(content: String): QrInvitePayload? {
    try {
        val prefix = "teamshare://invite?"
        if (!content.startsWith(prefix)) return null
        val queryString = content.substring(prefix.length)
        val params = queryString.split("&").associate { pair ->
            val parts = pair.split("=", limit = 2)
            if (parts.size == 2) parts[0] to parts[1] else parts[0] to ""
        }
        val teamId = params["teamId"]?.takeIf { it.isNotBlank() } ?: return null
        val token = params["token"]?.takeIf { it.isNotBlank() } ?: return null
        val expiresAt = params["expiresAt"]?.toLongOrNull() ?: return null
        val ownerPubKey = params["ownerPubKey"]?.takeIf { it.isNotBlank() } ?: return null
        val sig = params["sig"]?.takeIf { it.isNotBlank() } ?: return null
        val teamName = params["name"]?.takeIf { it.isNotBlank() }
            ?: params["teamName"]?.takeIf { it.isNotBlank() } ?: ""
        val host = params["host"]?.takeIf { it.isNotBlank() }
        val port = params["port"]?.toIntOrNull() ?: 8988
        val relay = params["relay"]?.takeIf { it.isNotBlank() }

        return QrInvitePayload(
            teamId = teamId,
            invitationToken = token,
            expiresAt = expiresAt,
            ownerPublicKey = ownerPubKey,
            signature = sig,
            teamName = teamName,
            ownerHost = host,
            ownerPort = port,
            relayUrl = relay
        )
    } catch (e: Exception) {
        return null
    }
}

