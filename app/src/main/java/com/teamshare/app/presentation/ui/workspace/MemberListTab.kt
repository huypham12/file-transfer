package com.teamshare.app.presentation.ui.workspace

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.teamshare.app.domain.model.MemberRole
import com.teamshare.app.domain.model.TeamMember
import com.teamshare.app.presentation.ui.components.ConfirmationDialog
import com.teamshare.app.presentation.ui.components.EmptyStateView
import com.teamshare.app.presentation.ui.components.ErrorBanner
import com.teamshare.app.presentation.ui.components.SuccessBanner
import com.teamshare.app.presentation.viewmodel.TeamViewModel

@Composable
fun MemberListTab(
    teamId: String,
    viewModel: TeamViewModel,
    onInviteQrClick: () -> Unit
) {
    val uiState by viewModel.workspaceUiState.collectAsState()
    var memberToRevoke by remember { mutableStateOf<TeamMember?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (uiState.errorMessage != null) {
                ErrorBanner(
                    message = uiState.errorMessage!!,
                    onDismiss = { viewModel.clearErrors() }
                )
            }

            if (uiState.actionSuccessMessage != null) {
                SuccessBanner(
                    message = uiState.actionSuccessMessage!!,
                    onDismiss = { viewModel.clearErrors() }
                )
            }

            if (uiState.members.isEmpty() && !uiState.isLoading) {
                EmptyStateView(
                    title = "Chưa có thành viên nào",
                    subtitle = if (uiState.isOwner) "Nhấn nút 'Mời QR' ở góc dưới để tạo mã QR kết nối thành viên mới vào nhóm."
                               else "Nhóm chưa có thành viên nào khác.",
                    icon = Icons.Default.Person,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(uiState.members, key = { it.memberId }) { member ->
                        MemberCard(
                            member = member,
                            isCurrentUserOwner = uiState.isOwner,
                            onRevokeClick = { memberToRevoke = member }
                        )
                    }
                }
            }
        }

        // Nếu người dùng là Owner, hiển thị nút FAB Mời thành viên qua QR
        if (uiState.isOwner) {
            ExtendedFloatingActionButton(
                onClick = onInviteQrClick,
                icon = { Icon(imageVector = Icons.Default.QrCode, contentDescription = null) },
                text = { Text("Mời QR", fontWeight = FontWeight.SemiBold) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 3.dp),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
            )
        }

        // Hộp thoại xác nhận tước quyền thành viên
        if (memberToRevoke != null) {
            ConfirmationDialog(
                title = "Thu hồi quyền thành viên?",
                message = "Cảnh báo: Hành động này sẽ tước quyền truy cập nhóm của '${memberToRevoke!!.memberName}', hủy cưỡng bức mọi phiên truyền tải tệp đang chạy với thành viên này trong < 200 ms. Bạn có chắc chắn?",
                confirmText = "Tước quyền",
                isDestructive = true,
                onConfirm = {
                    viewModel.revokeMember(teamId, memberToRevoke!!.memberId)
                    memberToRevoke = null
                },
                onDismiss = { memberToRevoke = null }
            )
        }
    }
}

@Composable
fun MemberCard(
    member: TeamMember,
    isCurrentUserOwner: Boolean,
    onRevokeClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .background(
                        color = if (member.isOwner) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.secondaryContainer,
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (member.isOwner) Icons.Default.Security else Icons.Default.Person,
                    contentDescription = null,
                    tint = if (member.isOwner) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = member.memberName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .background(
                                color = if (member.isOwner) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                                       else MaterialTheme.colorScheme.secondary.copy(alpha = 0.1f),
                                shape = RoundedCornerShape(6.dp)
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = if (member.isOwner) "Trưởng nhóm" else "Thành viên",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            color = if (member.isOwner) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.secondary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                val fingerprintSnippet = if (member.deviceFingerprint.length > 12) {
                    "${member.deviceFingerprint.take(6)}...${member.deviceFingerprint.takeLast(6)}"
                } else member.deviceFingerprint

                Text(
                    text = "ID: $fingerprintSnippet",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Nút tước quyền (Chỉ Owner mới có quyền tước quyền các member khác)
            if (isCurrentUserOwner && !member.isOwner && member.isActive) {
                IconButton(onClick = onRevokeClick) {
                    Icon(
                        imageVector = Icons.Default.PersonRemove,
                        contentDescription = "Tước quyền",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

