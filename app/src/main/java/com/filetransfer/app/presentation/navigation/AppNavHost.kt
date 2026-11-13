package com.filetransfer.app.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.filetransfer.app.presentation.ui.audit.AuditLogScreen
import com.filetransfer.app.presentation.ui.history.TransferHistoryScreen
import com.filetransfer.app.presentation.ui.home.HomeScreen
import com.filetransfer.app.presentation.ui.receiver.ReceiverHomeScreen
import com.filetransfer.app.presentation.ui.receiver.ScannerScreen
import com.filetransfer.app.presentation.ui.sender.QrDisplayScreen
import com.filetransfer.app.presentation.ui.sender.SenderHomeScreen
import com.filetransfer.app.presentation.ui.transfer.TransferProgressScreen
import com.filetransfer.app.presentation.viewmodel.AuditViewModel
import com.filetransfer.app.presentation.viewmodel.HistoryViewModel
import com.filetransfer.app.presentation.viewmodel.ReceiverViewModel
import com.filetransfer.app.presentation.viewmodel.SenderViewModel
import com.filetransfer.app.presentation.viewmodel.TransferViewModel

/**
 * Đồ thị điều hướng trung tâm kết nối các màn hình Compose của ứng dụng chia sẻ tệp ngang hàng an toàn.
 * Tuân thủ Stage 7 Presentation Layer Navigation Specification.
 */
@Composable
fun AppNavHost(
    navController: NavHostController,
    senderViewModel: SenderViewModel,
    receiverViewModel: ReceiverViewModel,
    transferViewModel: TransferViewModel,
    auditViewModel: AuditViewModel,
    historyViewModel: HistoryViewModel? = null,
    modifier: Modifier = Modifier,
    startDestination: String = Screen.Home.route
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier
    ) {
        // 1. Màn hình Chính (Home Screen)
        composable(Screen.Home.route) {
            HomeScreen(
                transferViewModel = transferViewModel,
                receiverViewModel = receiverViewModel,
                onNavigateToSend = { navController.navigate(Screen.SenderHome.route) },
                onNavigateToScan = { navController.navigate(Screen.Scanner.route) },
                onNavigateToAudit = { navController.navigate(Screen.AuditLog.route) },
                onNavigateToHistory = { navController.navigate(Screen.TransferHistory.route) },
                onNavigateToProgress = { sessionId ->
                    navController.navigate(Screen.TransferProgress.createRoute(sessionId))
                },
                onNavigateToReceiverHome = {
                    navController.navigate(Screen.ReceiverHome.route)
                }
            )
        }

        // 2. Màn hình Chọn tệp & Cấu hình Người gửi (SenderHomeScreen)
        composable(Screen.SenderHome.route) {
            SenderHomeScreen(
                viewModel = senderViewModel,
                onBackClick = { navController.popBackStack() },
                onSessionCreated = { sessionId ->
                    navController.navigate(Screen.QrDisplay.createRoute(sessionId))
                }
            )
        }

        // 3. Màn hình Mã QR phiên 1-N & Phê duyệt thiết bị (QrDisplayScreen)
        composable(
            route = Screen.QrDisplay.route,
            arguments = listOf(navArgument(Screen.QrDisplay.ARG_SESSION_ID) { type = NavType.StringType })
        ) { backStackEntry ->
            val sessionId = backStackEntry.arguments?.getString(Screen.QrDisplay.ARG_SESSION_ID) ?: ""
            QrDisplayScreen(
                sessionId = sessionId,
                viewModel = senderViewModel,
                onBackClick = { navController.popBackStack() }
            )
        }

        // 4. Màn hình CameraX Quét mã QR (ScannerScreen)
        composable(Screen.Scanner.route) {
            ScannerScreen(
                viewModel = receiverViewModel,
                onBackClick = { navController.popBackStack() },
                onQrScanned = {
                    navController.navigate(Screen.ReceiverHome.route) {
                        popUpTo(Screen.Scanner.route) { inclusive = true }
                    }
                }
            )
        }

        // 5. Màn hình Xem trước tệp & Đo đĩa trống Người nhận (ReceiverHomeScreen)
        composable(Screen.ReceiverHome.route) {
            ReceiverHomeScreen(
                viewModel = receiverViewModel,
                transferViewModel = transferViewModel,
                onBackClick = { navController.popBackStack() },
                onStartTransfer = { sessionId ->
                    val targetRoute = if (sessionId.isNotBlank()) {
                        Screen.TransferProgress.createRoute(sessionId)
                    } else {
                        Screen.TransferProgress.createRoute("incoming_session")
                    }
                    navController.navigate(targetRoute) {
                        popUpTo(Screen.ReceiverHome.route) { inclusive = true }
                    }
                }
            )
        }

        // 6. Màn hình Chi tiết tiến trình truyền tải P2P (TransferProgressScreen)
        composable(
            route = Screen.TransferProgress.route,
            arguments = listOf(navArgument(Screen.TransferProgress.ARG_SESSION_ID) { type = NavType.StringType })
        ) { backStackEntry ->
            val sessionId = backStackEntry.arguments?.getString(Screen.TransferProgress.ARG_SESSION_ID) ?: ""
            LaunchedEffect(sessionId) {
                transferViewModel.attachSession(sessionId)
            }
            TransferProgressScreen(
                sessionId = sessionId,
                viewModel = transferViewModel,
                onBackClick = { navController.popBackStack() }
            )
        }

        // 7. Màn hình Nhật ký Kiểm toán an ninh (AuditLogScreen)
        composable(Screen.AuditLog.route) {
            AuditLogScreen(
                viewModel = auditViewModel,
                onBackClick = { navController.popBackStack() }
            )
        }

        // 8. Màn hình Lịch sử truyền nhận tệp (TransferHistoryScreen)
        composable(Screen.TransferHistory.route) {
            if (historyViewModel != null) {
                TransferHistoryScreen(
                    viewModel = historyViewModel,
                    onBackClick = { navController.popBackStack() }
                )
            }
        }
    }
}
