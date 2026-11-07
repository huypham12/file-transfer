package com.teamshare.app.presentation.ui.team

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.file_tranfer.R
import com.teamshare.app.domain.model.Team
import com.teamshare.app.domain.model.TeamStatus
import com.teamshare.app.presentation.ui.components.EmptyStateView
import com.teamshare.app.presentation.ui.components.ErrorBanner
import com.teamshare.app.presentation.ui.theme.LanBadgeColor
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
    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }

    val filteredTeams = remember(uiState.teams, searchQuery) {
        if (searchQuery.isBlank()) uiState.teams
        else uiState.teams.filter {
            it.teamName.contains(searchQuery, ignoreCase = true) ||
            it.description.contains(searchQuery, ignoreCase = true)
        }
    }

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
                            text = stringResource(R.string.team_list_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        isSearchActive = !isSearchActive
                        if (!isSearchActive) searchQuery = ""
                    }) {
                        Icon(
                            imageVector = if (isSearchActive) Icons.Default.Close else Icons.Default.Search,
                            contentDescription = if (isSearchActive) "Đóng tìm kiếm" else "Tìm kiếm nhóm",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
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
            ExtendedFloatingActionButton(
                onClick = { showCreateDialog = true },
                icon = { Icon(imageVector = Icons.Default.Add, contentDescription = null) },
                text = { Text("Tạo nhóm mới", fontWeight = FontWeight.SemiBold) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 3.dp)
            )
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

                if (isSearchActive) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text(stringResource(R.string.team_list_search_hint)) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Close, contentDescription = "Xóa tìm kiếm", modifier = Modifier.size(18.dp))
                                }
                            }
                        },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                }

                if (uiState.teams.isEmpty() && !uiState.isLoading) {
                    EmptyStateView(
                        title = stringResource(R.string.team_list_empty_title),
                        subtitle = stringResource(R.string.team_list_empty_subtitle),
                        icon = Icons.Default.Group,
                        actionLabel = stringResource(R.string.team_list_btn_join_qr),
                        onAction = onScanQrClick,
                        modifier = Modifier.fillMaxSize()
                    )
                } else if (filteredTeams.isEmpty()) {
                    EmptyStateView(
                        title = "Không tìm thấy nhóm",
                        subtitle = "Không có nhóm nào phù hợp với từ khóa '$searchQuery'.",
                        icon = Icons.Default.Search,
                        actionLabel = "Xóa tìm kiếm",
                        onAction = { searchQuery = "" },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(filteredTeams, key = { it.teamId }) { team ->
                            TeamCard(team = team, onClick = { onTeamClick(team.teamId) })
                        }
                    }
                }
            }

            if (showCreateDialog) {
                CreateTeamDialog(
                    isLoading = uiState.isLoading,
                    onDismiss = { showCreateDialog = false },
                    onConfirm = { name, desc, ownerName ->
                        viewModel.createTeam(name, desc, ownerName)
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
    val initial = team.teamName.trim().take(1).uppercase()
    val isActive = team.status == TeamStatus.ACTIVE

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Team Avatar Circle (Initials badge)
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (initial.isNotBlank()) initial else "T",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = team.teamName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    // Status badge
                    Box(
                        modifier = Modifier
                            .background(
                                color = if (isActive) LanBadgeColor.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .background(
                                        color = if (isActive) LanBadgeColor else MaterialTheme.colorScheme.outline,
                                        shape = CircleShape
                                    )
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = if (isActive) "Hoạt động" else "Đã giải tán",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isActive) LanBadgeColor else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (team.description.isNotBlank()) team.description else "Không gian chia sẻ tài liệu và cộng tác nhóm",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.CalendarToday,
                        contentDescription = null,
                        modifier = Modifier.size(13.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Khởi tạo: $formattedDate",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = "Mở nhóm",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

