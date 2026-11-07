package com.teamshare.app.presentation.ui.workspace

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.teamshare.app.domain.model.AuditEventType
import com.teamshare.app.domain.model.AuditLog
import com.teamshare.app.domain.model.AuditSeverity
import com.teamshare.app.presentation.ui.components.EmptyStateView
import com.teamshare.app.presentation.ui.theme.LanBadgeColor
import com.teamshare.app.presentation.ui.theme.SecurityAlertColor
import com.teamshare.app.presentation.ui.theme.SecurityInfoColor
import com.teamshare.app.presentation.ui.theme.SecurityWarningColor
import com.teamshare.app.presentation.viewmodel.AuditViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ActivityLogTab(
    teamId: String,
    viewModel: AuditViewModel
) {
    val uiState by viewModel.uiState.collectAsState()

    if (uiState.logs.isEmpty() && !uiState.isLoading) {
        EmptyStateView(
            title = "Chưa có hoạt động nào",
            subtitle = "Mọi thao tác quan trọng (tạo nhóm, mời thành viên, chia sẻ tệp, cảnh báo bảo mật) sẽ được ghi vết bất biến tại đây.",
            icon = Icons.Default.History,
            modifier = Modifier.fillMaxSize()
        )
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(uiState.logs, key = { it.logId }) { log ->
                AuditLogCard(log = log)
            }
        }
    }
}

@Composable
fun AuditLogCard(log: AuditLog) {
    val dateFormat = remember { SimpleDateFormat("HH:mm • dd/MM/yyyy", Locale.getDefault()) }
    val formattedDate = remember(log.timestamp) { dateFormat.format(Date(log.timestamp)) }

    val (badgeColor, badgeText, icon) = when (log.severity) {
        AuditSeverity.SECURITY_ALERT -> Triple(SecurityAlertColor, "BẢO MẬT", Icons.Default.Shield)
        AuditSeverity.WARNING -> Triple(SecurityWarningColor, "CẢNH BÁO", Icons.Default.Warning)
        AuditSeverity.INFO -> Triple(SecurityInfoColor, "THÔNG TIN", Icons.Default.Info)
    }

    val eventTitle = remember(log.eventType) {
        when (log.eventType) {
            AuditEventType.TEAM_CREATED -> "Khởi tạo nhóm"
            AuditEventType.TEAM_DISBANDED -> "Giải tán nhóm"
            AuditEventType.INVITATION_GENERATED -> "Tạo mã QR lời mời"
            AuditEventType.MEMBER_JOINED -> "Thành viên gia nhập"
            AuditEventType.MEMBER_REVOKED -> "Thu hồi quyền thành viên"
            AuditEventType.DOCUMENT_PUBLISHED -> "Công bố tài liệu"
            AuditEventType.TRANSFER_STARTED -> "Bắt đầu truyền tệp"
            AuditEventType.TRANSFER_COMPLETED -> "Truyền tệp hoàn tất"
            AuditEventType.TRANSFER_PAUSED -> "Tạm dừng truyền tệp"
            AuditEventType.CHECKSUM_FAILED -> "Lỗi kiểm tra toàn vẹn"
            AuditEventType.SECURITY_ALERT -> "Cảnh báo an ninh"
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
        ) {
            // Dải màu biểu thị mức độ nghiêm trọng ở cạnh trái
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(badgeColor)
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .background(badgeColor.copy(alpha = 0.12f), shape = RoundedCornerShape(6.dp))
                            .padding(horizontal = 7.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = badgeText,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = badgeColor
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = eventTitle,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = formattedDate,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = log.details,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                val actorDisplay = when {
                    log.actorName.isNotBlank() -> log.actorName
                    log.actorId.length > 16 -> "${log.actorId.take(8)}...${log.actorId.takeLast(6)}"
                    log.actorId.isNotBlank() -> log.actorId
                    else -> ""
                }
                if (actorDisplay.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Tác nhân: $actorDisplay",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                        if (log.signature.isNotBlank()) {
                            Text(
                                text = "✓ Đã xác thực E2EE",
                                style = MaterialTheme.typography.labelSmall,
                                color = LanBadgeColor,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
    }
}

