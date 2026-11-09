package com.teamshare.app.presentation.navigation

/**
 * Định nghĩa các tuyến điều hướng trong ứng dụng chia sẻ tệp ngang hàng an toàn (Secure Local Drop).
 * Tuân thủ SDD Mục 4.2 và Stage 7 Navigation Specification.
 */
sealed class Screen(val route: String) {
    object Home : Screen("home")

    object SenderHome : Screen("sender_home")

    object QrDisplay : Screen("qr_display/{sessionId}") {
        const val ARG_SESSION_ID = "sessionId"
        fun createRoute(sessionId: String): String = "qr_display/$sessionId"
    }

    object Scanner : Screen("scanner")

    object ReceiverHome : Screen("receiver_home")

    object TransferProgress : Screen("transfer_progress/{sessionId}") {
        const val ARG_SESSION_ID = "sessionId"
        fun createRoute(sessionId: String): String = "transfer_progress/$sessionId"
    }

    object TransferHistory : Screen("transfer_history")

    object AuditLog : Screen("audit_log")
}
