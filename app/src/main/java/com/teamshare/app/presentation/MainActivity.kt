package com.teamshare.app.presentation

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.teamshare.app.core.platform.network.NetworkConfig
import com.teamshare.app.core.platform.service.NotificationManagerHelper
import com.teamshare.app.core.platform.window.WindowSecurityManager
import com.teamshare.app.core.security.sanitizer.SafeLogger
import com.teamshare.app.presentation.di.ViewModelFactory
import com.teamshare.app.presentation.navigation.AppNavHost
import com.teamshare.app.presentation.navigation.Screen
import com.teamshare.app.presentation.ui.share.ShareIntentHandlerActivity
import com.teamshare.app.presentation.ui.theme.TeamShareTheme

/**
 * Activity chính của ứng dụng chia sẻ tệp ngang hàng an toàn (Secure Local Drop).
 * Cấu hình chế độ bảo mật chống quay màn hình qua [WindowSecurityManager.applyFlagSecure],
 * tích hợp Material 3 Theme, AppNavHost và tiếp nhận chia sẻ tệp từ Android Sharesheet (SRS FR-MOB-01).
 */
class MainActivity : FragmentActivity() {

    private val viewModelFactory by lazy { ViewModelFactory(applicationContext) }
    private var pendingSessionId by mutableStateOf<String?>(null)
    private var pendingNavigateToSendHome by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowSecurityManager.applyFlagSecure(this)
        enableEdgeToEdge()

        NetworkConfig.init(applicationContext)
        lifecycleScope.launch(Dispatchers.IO) {
            NetworkConfig.autoDetectRelayHostFromEmulator()
        }

        handleIncomingIntent(intent)

        setContent {
            TeamShareTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()

                    // Deep-link từ Notification truyền tệp
                    LaunchedEffect(pendingSessionId) {
                        pendingSessionId?.let { sId ->
                            navController.navigate(Screen.TransferProgress.createRoute(sId)) {
                                launchSingleTop = true
                            }
                            pendingSessionId = null
                        }
                    }

                    // Tiếp nhận chia sẻ từ Sharesheet ngoài
                    LaunchedEffect(pendingNavigateToSendHome) {
                        if (pendingNavigateToSendHome) {
                            navController.navigate(Screen.SenderHome.route) {
                                launchSingleTop = true
                            }
                            pendingNavigateToSendHome = false
                        }
                    }

                    AppNavHost(
                        navController = navController,
                        senderViewModel = viewModelFactory.senderViewModel,
                        receiverViewModel = viewModelFactory.receiverViewModel,
                        transferViewModel = viewModelFactory.transferViewModel,
                        auditViewModel = viewModelFactory.auditViewModel,
                        historyViewModel = viewModelFactory.historyViewModel
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return

        // 1. Kiểm tra deep-link từ Notification truyền tệp (SC-01 / P1-T2)
        val notifSessionId = intent.getStringExtra(NotificationManagerHelper.EXTRA_SESSION_ID)
        if (!notifSessionId.isNullOrBlank()) {
            pendingSessionId = notifSessionId
        }

        // 2. Kiểm tra tệp tin chia sẻ ngoài từ Sharesheet
        val isFromShare = intent.getBooleanExtra(ShareIntentHandlerActivity.EXTRA_FROM_SHARE_INTENT, false)
        if (isFromShare) {
            val sharedUris: ArrayList<Uri>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayListExtra(ShareIntentHandlerActivity.EXTRA_SHARED_URIS, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra(ShareIntentHandlerActivity.EXTRA_SHARED_URIS)
            }
            if (!sharedUris.isNullOrEmpty()) {
                SafeLogger.i("MainActivity", "Tiếp nhận ${sharedUris.size} tệp từ Sharesheet, chuyển hướng sang SenderHomeScreen")
                viewModelFactory.senderViewModel.onUrisSelected(sharedUris, applicationContext)
                pendingNavigateToSendHome = true
            }
        }
    }
}
