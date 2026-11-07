package com.teamshare.app.presentation.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.teamshare.app.core.transfer.lan.TransferEngineState
import com.teamshare.app.presentation.ui.transfer.formatSpeed
import com.teamshare.app.presentation.viewmodel.TransferUiState

/**
 * Thanh tiến trình truyền tệp thu nhỏ (Mini Transfer Bar) hiển thị ở đáy WorkspaceScreen.
 * Cho phép người dùng theo dõi tiến độ % và tốc độ truyền tệp nền liên tục mà không làm gián đoạn
 * việc duyệt tài liệu, đồng thời cung cấp điểm chạm điều hướng mở lại TransferProgressScreen (SRS FR-TRF-05 & SC-01).
 */
@Composable
fun MiniTransferBar(
    uiState: TransferUiState,
    onBarClick: (sessionId: String) -> Unit,
    onPauseClick: (sessionId: String) -> Unit,
    onResumeClick: (sessionId: String) -> Unit,
    onCancelClick: (sessionId: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val isTransferActive = uiState.sessionId.isNotBlank() && (
        uiState.state == TransferEngineState.TRANSFERRING ||
        uiState.state == TransferEngineState.HANDSHAKING ||
        uiState.state == TransferEngineState.CONNECTING ||
        uiState.state == TransferEngineState.PAUSED ||
        uiState.state == TransferEngineState.VERIFYING
    )

    AnimatedVisibility(
        visible = isTransferActive,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = modifier
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .clickable { onBarClick(uiState.sessionId) },
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        if (uiState.state == TransferEngineState.PAUSED) {
                            Icon(
                                imageVector = Icons.Default.Pause,
                                contentDescription = "Tạm dừng",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(22.dp)
                            )
                        } else {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.5.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        Spacer(modifier = Modifier.width(10.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = uiState.fileName.ifBlank { "Tệp đang truyền" },
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "${(uiState.progressPercent * 100).toInt()}%",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (uiState.state == TransferEngineState.PAUSED) "Đã tạm dừng"
                                    else formatSpeed(uiState.speedBytesPerSec),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                )
                            }
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (uiState.state == TransferEngineState.PAUSED) {
                            IconButton(
                                onClick = { onResumeClick(uiState.sessionId) },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = "Tiếp tục truyền",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        } else {
                            IconButton(
                                onClick = { onPauseClick(uiState.sessionId) },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Pause,
                                    contentDescription = "Tạm dừng truyền",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        IconButton(
                            onClick = { onCancelClick(uiState.sessionId) },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Hủy phiên truyền",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                LinearProgressIndicator(
                    progress = { uiState.progressPercent },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }
        }
    }
}

