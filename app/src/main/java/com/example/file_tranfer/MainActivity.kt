package com.example.file_tranfer

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import androidx.navigation.compose.rememberNavController
import com.teamshare.app.presentation.di.ViewModelFactory
import com.teamshare.app.presentation.navigation.AppNavHost
import com.teamshare.app.presentation.ui.share.ShareIntentHandlerActivity
import com.teamshare.app.presentation.ui.theme.TeamShareTheme

/**
 * Cửa sổ tương tác chính của ứng dụng TeamShare.
 * Kế thừa FragmentActivity hỗ trợ BiometricPrompt và Jetpack Compose,
 * tích hợp Material 3 Theme, AppNavHost và tiếp nhận chia sẻ tệp từ Android Sharesheet (SRS FR-MOB-01).
 */
class MainActivity : FragmentActivity() {

    private val viewModelFactory by lazy { ViewModelFactory(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        handleIncomingShareIntent(intent)

        setContent {
            TeamShareTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()

                    AppNavHost(
                        navController = navController,
                        teamViewModel = viewModelFactory.teamViewModel,
                        documentViewModel = viewModelFactory.documentViewModel,
                        transferViewModel = viewModelFactory.transferViewModel,
                        auditViewModel = viewModelFactory.auditViewModel
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingShareIntent(intent)
    }

    private fun handleIncomingShareIntent(intent: Intent?) {
        if (intent == null) return
        val isFromShare = intent.getBooleanExtra(ShareIntentHandlerActivity.EXTRA_FROM_SHARE_INTENT, false)
        if (isFromShare) {
            val sharedUris: ArrayList<Uri>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayListExtra(ShareIntentHandlerActivity.EXTRA_SHARED_URIS, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra(ShareIntentHandlerActivity.EXTRA_SHARED_URIS)
            }
            if (!sharedUris.isNullOrEmpty()) {
                viewModelFactory.documentViewModel.setPendingSharedUris(sharedUris)
            }
        }
    }
}
