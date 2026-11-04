package com.teamshare.app.presentation.navigation

/**
 * Định nghĩa các tuyến điều hướng trong ứng dụng TeamShare.
 */
sealed class Screen(val route: String) {
    object TeamList : Screen("team_list")

    object Workspace : Screen("workspace/{teamId}") {
        const val ARG_TEAM_ID = "teamId"
        fun createRoute(teamId: String): String = "workspace/$teamId"
    }

    object QrInvite : Screen("qr_invite/{teamId}") {
        const val ARG_TEAM_ID = "teamId"
        fun createRoute(teamId: String): String = "qr_invite/$teamId"
    }

    object QrScanner : Screen("qr_scanner")

    object TransferProgress : Screen("transfer_progress/{sessionId}") {
        const val ARG_SESSION_ID = "sessionId"
        fun createRoute(sessionId: String): String = "transfer_progress/$sessionId"
    }
}

