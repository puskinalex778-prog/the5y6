package com.vibe.radio

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.vibe.radio.ui.AppScreen
import com.vibe.radio.ui.PlayerViewModel
import com.vibe.radio.ui.VibeTheme

class MainActivity : ComponentActivity() {

    private val vm: PlayerViewModel by viewModels()

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Android 13+: разрешение на уведомление с управлением плеером
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        if (savedInstanceState == null) handleIntent(intent)

        setContent {
            VibeTheme {
                AppScreen(vm)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** «Открыть с помощью PLAY pay» из файлового менеджера или мессенджера. */
    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            intent.data?.let { vm.playExternal(it) }
        }
    }
}
