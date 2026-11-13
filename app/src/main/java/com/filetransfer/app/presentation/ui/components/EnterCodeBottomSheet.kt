package com.filetransfer.app.presentation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.file_tranfer.R
import com.filetransfer.app.core.platform.share.ShareCodeHelper
import com.filetransfer.app.domain.model.SessionQrPayload
import com.filetransfer.app.domain.model.TransferChannel
import com.filetransfer.app.presentation.viewmodel.ReceiverViewModel

/**
 * Hộp thoại đáy (ModalBottomSheet) cho phép Người nhận dán hoặc nhập mã chia sẻ thủ công.
 * Hỗ trợ tự động trích xuất payload sạch, Live Validation và Xem trước thông tin tệp.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnterCodeBottomSheet(
    viewModel: ReceiverViewModel,
    onDismiss: () -> Unit,
    onCodeAccepted: (SessionQrPayload) -> Unit,
    initialCode: String = "",
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
) {
    val clipboardManager = LocalClipboardManager.current
    var inputText by remember { mutableStateOf(initialCode) }
    var validatedPayload by remember { mutableStateOf<SessionQrPayload?>(null) }
    var validationError by remember { mutableStateOf<String?>(null) }

    // Tự động kiểm tra tính hợp lệ khi inputText thay đổi
    LaunchedEffect(inputText) {
        val cleaned = ShareCodeHelper.extractPayload(inputText)
        if (cleaned.isNotBlank()) {
            val result = viewModel.validateShareCode(cleaned)
            result.onSuccess { payload ->
                validatedPayload = payload
                validationError = null
            }.onFailure { error ->
                validatedPayload = null
                validationError = error.message ?: "Mã chia sẻ không đúng định dạng hoặc đã hết hạn"
            }
        } else {
            validatedPayload = null
            validationError = null
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Key,
                        contentDescription = "Mã nhận tệp",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier
                            .padding(10.dp)
                            .size(24.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = stringResource(R.string.enter_code_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(R.string.enter_code_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Ô nhập văn bản mã chia sẻ
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                label = { Text(stringResource(R.string.label_share_code)) },
                placeholder = { Text(stringResource(R.string.placeholder_share_code)) },
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                trailingIcon = {
                    if (inputText.isNotBlank()) {
                        IconButton(onClick = { inputText = "" }) {
                            Icon(imageVector = Icons.Default.Clear, contentDescription = stringResource(R.string.action_clear_all))
                        }
                    } else {
                        IconButton(onClick = {
                            val clipText = clipboardManager.getText()?.text
                            if (!clipText.isNullOrBlank()) {
                                inputText = clipText
                            }
                        }) {
                            Icon(
                                imageVector = Icons.Default.ContentPaste,
                                contentDescription = stringResource(R.string.btn_paste_clipboard),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            )

            // Card Xem trước Live Preview khi mã hợp lệ
            validatedPayload?.let { payload ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Mã hợp lệ",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Mã chia sẻ hợp lệ! Sẵn sàng nhận tệp:",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        // Thiết bị gửi
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.PhoneAndroid,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            val senderName = payload.senderDeviceModel.ifBlank { "Thiết bị filetransfer" }
                            Text(
                                text = "Người gửi: $senderName",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        // Dung lượng & số tệp
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (payload.channel == TransferChannel.DIRECT_LAN) Icons.Default.Lan else Icons.Default.Public,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            val channelName = if (payload.channel == TransferChannel.DIRECT_LAN) "Mạng LAN Wi-Fi" else "Chuyển tiếp Relay E2EE"
                            Text(
                                text = "Dữ liệu: ${payload.itemCount} tệp (${formatFileSize(payload.totalBytes)}) • Kênh: $channelName",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // Thời gian hết hạn
                        val remainingSec = ((payload.expiresAt - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
                        Text(
                            text = "Thời hạn còn lại: %02d:%02d".format(remainingSec / 60, remainingSec % 60),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // Thông báo lỗi nếu mã không hợp lệ
            if (validationError != null && inputText.isNotBlank()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.ErrorOutline,
                        contentDescription = "Lỗi",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = validationError ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            // Hàng nút bấm Hành động
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(stringResource(R.string.btn_close))
                }

                Button(
                    onClick = {
                        val payload = validatedPayload
                        if (payload != null) {
                            viewModel.onManualCodeEntered(inputText) { acceptedPayload ->
                                onCodeAccepted(acceptedPayload)
                            }
                        }
                    },
                    enabled = validatedPayload != null,
                    modifier = Modifier
                        .weight(1.3f)
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.btn_accept_download),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

