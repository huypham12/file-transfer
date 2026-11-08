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
import com.teamshare.app.core.platform.service.NotificationManagerHelper
import com.teamshare.app.core.platform.window.WindowSecurityManager
import com.teamshare.app.core.security.sanitizer.SafeLogger
import com.teamshare.app.presentation.di.ViewModelFactory
import com.teamshare.app.presentation.navigation.AppNavHost
import com.teamshare.app.presentation.navigation.Screen
import com.teamshare.app.presentation.ui.share.ShareIntentHandlerActivity
import com.teamshare.app.presentation.ui.theme.TeamShareTheme

/**
 * Cửa sổ tương tác chính của ứng dụng TeamShare.
 * Kế thừa FragmentActivity hỗ trợ BiometricPrompt và Jetpack Compose,
 * tích hợp Material 3 Theme, AppNavHost và tiếp nhận chia sẻ tệp từ Android Sharesheet (SRS FR-MOB-01).
 */
class MainActivity : FragmentActivity() {

    private val viewModelFactory by lazy { ViewModelFactory(applicationContext) }
    private var pendingSessionId by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowSecurityManager.applyFlagSecure(this)
        enableEdgeToEdge()

        com.teamshare.app.core.platform.network.NetworkAddressHelper.init(applicationContext)

        handleIncomingIntent(intent)

        setContent {
            TeamShareTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()

                    LaunchedEffect(pendingSessionId) {
                        pendingSessionId?.let { sId ->
                            navController.navigate(Screen.TransferProgress.createRoute(sId)) {
                                launchSingleTop = true
                            }
                            pendingSessionId = null
                        }
                    }

                    AppNavHost(
                        navController = navController,
                        transferViewModel = viewModelFactory.transferViewModel,
                        auditViewModel = viewModelFactory.auditViewModel
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
                SafeLogger.i("MainActivity", "Tiếp nhận ${sharedUris.size} tệp từ Sharesheet (sẵn sàng cho Stage 5 Sender Flow)")
            }
        }
    }
}


