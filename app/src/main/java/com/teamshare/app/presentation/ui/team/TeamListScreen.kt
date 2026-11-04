package com.teamshare.app.presentation.ui.team

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.teamshare.app.domain.model.Team
import com.teamshare.app.domain.model.TeamStatus
import com.teamshare.app.presentation.ui.components.EmptyStateView
import com.teamshare.app.presentation.ui.components.ErrorBanner
import com.teamshare.app.presentation.viewmodel.TeamViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeamListScreen(
    viewModel: TeamViewModel,
    onTeamClick: (teamId: String) -> Unit,
    onScanQrClick: () -> Unit
) {
    val uiState by viewModel.teamListUiState.collectAsState()
    var showCreateDialog by remember { mutableStateOf(false) }

    // Tự động điều hướng vào nhóm mới tạo
    LaunchedEffect(uiState.newlyCreatedTeam) {
        uiState.newlyCreatedTeam?.let { team ->
            viewModel.clearNewlyCreatedTeam()
            onTeamClick(team.teamId)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "TeamShare",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onScanQrClick) {
                        Icon(
                            imageVector = Icons.Default.QrCodeScanner,
                            contentDescription = "Quét mã QR gia nhập",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showCreateDialog = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(imageVector = Icons.Default.Add, contentDescription = "Tạo nhóm mới")
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                if (uiState.errorMessage != null) {
                    ErrorBanner(
                        message = uiState.errorMessage!!,
                        onDismiss = { viewModel.clearErrors() }
                    )
                }

                if (uiState.teams.isEmpty() && !uiState.isLoading) {
                    EmptyStateView(
                        title = "Chưa tham gia nhóm nào",
                        subtitle = "Tạo nhóm mới để bắt đầu chia sẻ tài liệu an toàn hoặc quét mã QR lời mời từ Trưởng nhóm.",
                        icon = Icons.Default.Group,
                        actionLabel = "Gia nhập qua mã QR",
                        onAction = onScanQrClick,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(uiState.teams, key = { it.teamId }) { team ->
                            TeamCard(team = team, onClick = { onTeamClick(team.teamId) })
                        }
                    }
                }
            }

            if (showCreateDialog) {
                CreateTeamDialog(
                    isLoading = uiState.isLoading,
                    onDismiss = { showCreateDialog = false },
                    onConfirm = { name, desc ->
                        viewModel.createTeam(name, desc)
                        showCreateDialog = false
                    }
                )
            }
        }
    }
}

@Composable
fun TeamCard(
    team: Team,
    onClick: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()) }
    val formattedDate = remember(team.createdAt) { dateFormat.format(Date(team.createdAt)) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = team.teamName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                AssistChip(
                    onClick = {},
                    label = {
                        Text(
                            text = if (team.status == TeamStatus.ACTIVE) "Hoạt động" else "Đã giải tán",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                )
            }

            if (team.description.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = team.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Khởi tạo: $formattedDate",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )
            }
        }
    }
}

