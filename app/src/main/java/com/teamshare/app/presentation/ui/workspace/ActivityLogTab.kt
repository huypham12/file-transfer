package com.teamshare.app.presentation.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.unit.dp
import com.teamshare.app.domain.model.AuditLog
import com.teamshare.app.domain.model.AuditSeverity
import com.teamshare.app.presentation.ui.components.EmptyStateView
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
    val dateFormat = remember { SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()) }
    val formattedDate = remember(log.timestamp) { dateFormat.format(Date(log.timestamp)) }

    val (badgeColor, badgeText, icon) = when (log.severity) {
        AuditSeverity.SECURITY_ALERT -> Triple(SecurityAlertColor, "BẢO MẬT", Icons.Default.Shield)
        AuditSeverity.WARNING -> Triple(SecurityWarningColor, "CẢNH BÁO", Icons.Default.Warning)
        AuditSeverity.INFO -> Triple(SecurityInfoColor, "THÔNG TIN", Icons.Default.Info)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .background(badgeColor.copy(alpha = 0.2f), shape = RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
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
                        text = log.eventType.name,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Text(
                    text = formattedDate,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = log.details,
                style = MaterialTheme.typography.bodyMedium
            )

            if (log.actorId.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                val actorSnippet = if (log.actorId.length > 16) {
                    "${log.actorId.take(8)}...${log.actorId.takeLast(6)}"
                } else log.actorId
                Text(
                    text = "Tác nhân: $actorSnippet",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
    }
}

