package com.teamshare.app.presentation.navigation

/**
 * Định nghĩa các tuyến điều hướng trong ứng dụng chia sẻ tệp ngang hàng an toàn.
 */
sealed class Screen(val route: String) {
    object Home : Screen("home")

    object TransferProgress : Screen("transfer_progress/{sessionId}") {
        const val ARG_SESSION_ID = "sessionId"
        fun createRoute(sessionId: String): String = "transfer_progress/$sessionId"
    }
}
