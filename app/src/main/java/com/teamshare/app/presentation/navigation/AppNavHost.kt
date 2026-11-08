package com.teamshare.app.presentation.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.teamshare.app.presentation.ui.transfer.TransferProgressScreen
import com.teamshare.app.presentation.viewmodel.AuditViewModel
import com.teamshare.app.presentation.viewmodel.TransferViewModel

/**
 * Đồ thị điều hướng trung tâm kết nối các màn hình Compose của ứng dụng chia sẻ tệp ngang hàng an toàn.
 */
@Composable
fun AppNavHost(
    navController: NavHostController,
    transferViewModel: TransferViewModel,
    auditViewModel: AuditViewModel,
    modifier: Modifier = Modifier,
    startDestination: String = Screen.Home.route
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier
    ) {
        // Màn hình chính tạm thời (Minimal Home - sẵn sàng cho Stage 7 Compose Refactoring)
        composable(Screen.Home.route) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "Secure Local Drop",
                    style = MaterialTheme.typography.headlineMedium
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Chia sẻ tệp ngang hàng an toàn qua LAN & Relay",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = {
                        val activeSessionId = transferViewModel.uiState.value.sessionId
                        val targetRoute = if (activeSessionId.isNotBlank()) {
                            Screen.TransferProgress.createRoute(activeSessionId)
                        } else {
                            Screen.TransferProgress.createRoute("demo_session")
                        }
                        navController.navigate(targetRoute)
                    }
                ) {
                    Text("Xem tiến trình truyền tệp")
                }
            }
        }

        // Màn hình chi tiết tiến trình truyền tải P2P
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
