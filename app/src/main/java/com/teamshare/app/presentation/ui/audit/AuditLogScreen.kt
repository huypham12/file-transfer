package com.teamshare.app.presentation.ui.audit

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.file_tranfer.R
import com.teamshare.app.domain.model.AuditLog
import com.teamshare.app.domain.model.AuditSeverity
import com.teamshare.app.presentation.ui.theme.SecurityWarningColor
import com.teamshare.app.presentation.viewmodel.AuditViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Màn hình tra cứu và lọc nhật ký kiểm toán an ninh bất biến (FR-AUD-01).
 * Tuân thủ SDD Mục 4.2.1 (AuditLogScreen).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuditLogScreen(
    viewModel: AuditViewModel,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.loadLogs()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_audit_log)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.loadLogs() }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.action_refresh)
                        )
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
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Thanh tìm kiếm
            OutlinedTextField(
                value = uiState.searchQuery,
                onValueChange = { viewModel.search(it) },
                placeholder = { Text(stringResource(R.string.placeholder_search_events)) },
                leadingIcon = {
                    Icon(imageVector = Icons.Default.Search, contentDescription = null)
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            // Bộ lọc mức độ nghiêm trọng (Severity Filter Chips)
            val severities = listOf(
                "ALL" to stringResource(R.string.audit_filter_all),
                "INFO" to stringResource(R.string.audit_filter_info),
                "WARNING" to stringResource(R.string.audit_filter_warning),
                "SECURITY_ALERT" to stringResource(R.string.audit_filter_security)
            )
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(severities) { (key, label) ->
                    FilterChip(
                        selected = uiState.filterSeverity.equals(key, ignoreCase = true),
                        onClick = { viewModel.filterBySeverity(key) },
                        label = { Text(label) }
                    )
                }
            }

            // Danh sách nhật ký kiểm toán
            val displayLogs = if (uiState.searchQuery.isNotBlank() || uiState.filterSeverity != "ALL") {
                uiState.filteredLogs
            } else {
                uiState.logs
            }

            if (uiState.isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (displayLogs.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.audit_empty_title),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(displayLogs, key = { it.logId }) { log ->
                        AuditLogItemCard(log = log)
                    }
                }
            }
        }
    }
}

@Composable
private fun AuditLogItemCard(log: AuditLog) {
    val severityColor = when (log.severity) {
        AuditSeverity.SECURITY_ALERT -> MaterialTheme.colorScheme.error
        AuditSeverity.WARNING -> SecurityWarningColor
        AuditSeverity.INFO -> MaterialTheme.colorScheme.primary
    }

    val dateFormatter = SimpleDateFormat("HH:mm:ss dd/MM/yyyy", Locale.getDefault())
    val dateText = try {
        dateFormatter.format(Date(log.timestamp))
    } catch (_: Throwable) {
        "N/A"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = CircleShape,
                        color = severityColor.copy(alpha = 0.15f),
                        modifier = Modifier.size(24.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(severityColor, CircleShape)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = formatAuditEventType(log.eventType),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = severityColor
                    )
                }

                Text(
                    text = dateText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = log.details,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            if (log.actorDevice.isNotBlank() && log.actorDevice != "local") {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.history_peer_label, log.actorDevice),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun formatAuditEventType(eventType: com.teamshare.app.domain.model.AuditEventType): String {
    return when (eventType) {
        com.teamshare.app.domain.model.AuditEventType.SESSION_CREATED -> "Khởi tạo phiên chia sẻ"
        com.teamshare.app.domain.model.AuditEventType.SESSION_APPROVED -> "Đã duyệt kết nối"
        com.teamshare.app.domain.model.AuditEventType.SESSION_REJECTED -> "Từ chối kết nối"
        com.teamshare.app.domain.model.AuditEventType.DEVICE_CONNECTED -> "Thiết bị kết nối"
        com.teamshare.app.domain.model.AuditEventType.TRANSFER_STARTED -> "Bắt đầu truyền tệp"
        com.teamshare.app.domain.model.AuditEventType.TRANSFER_COMPLETED -> "Truyền nhận thành công"
        com.teamshare.app.domain.model.AuditEventType.TRANSFER_PAUSED -> "Tạm dừng truyền tệp"
        com.teamshare.app.domain.model.AuditEventType.TRANSFER_RESUMED -> "Tiếp tục truyền tệp"
        com.teamshare.app.domain.model.AuditEventType.TRANSFER_CANCELLED -> "Đã hủy phiên truyền"
        com.teamshare.app.domain.model.AuditEventType.CHECKSUM_VERIFIED -> "Xác thực SHA-256 khớp"
        com.teamshare.app.domain.model.AuditEventType.CHECKSUM_FAILED -> "Lỗi toàn vẹn dữ liệu"
        com.teamshare.app.domain.model.AuditEventType.SECURITY_ALERT -> "Cảnh báo An ninh"
        else -> eventType.name
    }
}
