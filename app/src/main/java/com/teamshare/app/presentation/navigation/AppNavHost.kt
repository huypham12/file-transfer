package com.teamshare.app.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.teamshare.app.presentation.ui.team.TeamListScreen
import com.teamshare.app.presentation.ui.team.qr.QrInviteScreen
import com.teamshare.app.presentation.ui.team.qr.QrScannerScreen
import com.teamshare.app.presentation.ui.transfer.TransferProgressScreen
import com.teamshare.app.presentation.ui.workspace.WorkspaceScreen
import com.teamshare.app.presentation.viewmodel.AuditViewModel
import com.teamshare.app.presentation.viewmodel.DocumentViewModel
import com.teamshare.app.presentation.viewmodel.TeamViewModel
import com.teamshare.app.presentation.viewmodel.TransferViewModel

/**
 * Đồ thị điều hướng trung tâm kết nối các màn hình Compose của ứng dụng TeamShare.
 */
@Composable
fun AppNavHost(
    navController: NavHostController,
    teamViewModel: TeamViewModel,
    documentViewModel: DocumentViewModel,
    transferViewModel: TransferViewModel,
    auditViewModel: AuditViewModel,
    modifier: Modifier = Modifier,
    startDestination: String = Screen.TeamList.route
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier
    ) {
        // Màn hình 1: Danh sách nhóm
        composable(Screen.TeamList.route) {
            TeamListScreen(
                viewModel = teamViewModel,
                onTeamClick = { teamId ->
                    navController.navigate(Screen.Workspace.createRoute(teamId))
                },
                onScanQrClick = {
                    navController.navigate(Screen.QrScanner.route)
                }
            )
        }

        // Màn hình 2: Không gian làm việc nhóm (Workspace 3 tab)
        composable(
            route = Screen.Workspace.route,
            arguments = listOf(navArgument(Screen.Workspace.ARG_TEAM_ID) { type = NavType.StringType })
        ) { backStackEntry ->
            val teamId = backStackEntry.arguments?.getString(Screen.Workspace.ARG_TEAM_ID) ?: ""
            WorkspaceScreen(
                teamId = teamId,
                teamViewModel = teamViewModel,
                documentViewModel = documentViewModel,
                transferViewModel = transferViewModel,
                auditViewModel = auditViewModel,
                onBackClick = { navController.popBackStack() },
                onInviteQrClick = {
                    navController.navigate(Screen.QrInvite.createRoute(teamId))
                },
                onViewTransfer = { sessionId ->
                    navController.navigate(Screen.TransferProgress.createRoute(sessionId))
                },
                onRequestDownload = { docId ->
                    val doc = documentViewModel.catalogUiState.value.documents.find { it.docId == docId }
                    val fileName = doc?.fileName ?: "document_$docId"
                    val fileSize = doc?.fileSize ?: 0L
                    val currentMemberId = teamViewModel.workspaceUiState.value.currentMemberId ?: ""
                    val currentMemberName = teamViewModel.workspaceUiState.value.members.find { it.memberId == currentMemberId }?.memberName ?: ""

                    val sessionId = transferViewModel.startDownload(
                        docId = docId,
                        fileName = fileName,
                        fileSize = fileSize,
                        teamId = teamId,
                        requesterId = currentMemberId,
                        requesterName = currentMemberName,
                        publisherId = doc?.publisherId ?: "",
                        peerHost = doc?.publisherHost,
                        peerPort = doc?.publisherPort ?: 0,
                        expectedSha256 = doc?.expectedSha256,
                        onCompleted = {
                            documentViewModel.loadDocuments(teamId)
                        }
                    )
                    navController.navigate(Screen.TransferProgress.createRoute(sessionId))
                },
                onOpenDocument = { docId ->
                    documentViewModel.openDocument(docId)
                }
            )
        }

        // Màn hình 3: Lời mời mã QR động có chữ ký số
        composable(
            route = Screen.QrInvite.route,
            arguments = listOf(navArgument(Screen.QrInvite.ARG_TEAM_ID) { type = NavType.StringType })
        ) { backStackEntry ->
            val teamId = backStackEntry.arguments?.getString(Screen.QrInvite.ARG_TEAM_ID) ?: ""
            QrInviteScreen(
                teamId = teamId,
                viewModel = teamViewModel,
                onBackClick = { navController.popBackStack() }
            )
        }

        // Màn hình 4: Quét mã QR bằng CameraX + ML Kit
        composable(Screen.QrScanner.route) {
            QrScannerScreen(
                viewModel = teamViewModel,
                onBackClick = { navController.popBackStack() },
                onJoinSuccess = { teamId ->
                    navController.navigate(Screen.Workspace.createRoute(teamId)) {
                        popUpTo(Screen.TeamList.route)
                    }
                }
            )
        }

        // Màn hình 5: Chi tiết tiến trình truyền tải P2P
        composable(
            route = Screen.TransferProgress.route,
            arguments = listOf(navArgument(Screen.TransferProgress.ARG_SESSION_ID) { type = NavType.StringType })
        ) { backStackEntry ->
            val sessionId = backStackEntry.arguments?.getString(Screen.TransferProgress.ARG_SESSION_ID) ?: ""
            androidx.compose.runtime.LaunchedEffect(sessionId) {
                transferViewModel.attachSession(sessionId)
            }
            TransferProgressScreen(
                sessionId = sessionId,
                viewModel = transferViewModel,
                onBackClick = { navController.popBackStack() }
            )
        }
    }
}

