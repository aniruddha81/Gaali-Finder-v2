package com.aniruddha81.gaalifinderv2

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.aniruddha81.gaalifinderv2.core.media.AudioPlayer
import com.aniruddha81.gaalifinderv2.ui.home.HomeScreen
import com.aniruddha81.gaalifinderv2.ui.theme.GaaliFinderTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var player: AudioPlayer

    /**
     * Asking is best-effort: the app is fully usable without notifications, so a denial is
     * simply accepted rather than nagged about.
     */
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        requestNotificationPermissionIfNeeded()
        stopPlaybackWhenBackgrounded()

        setContent {
            GaaliFinderTheme {
                HomeScreen()
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

        val permission = Manifest.permission.POST_NOTIFICATIONS
        val granted = ContextCompat.checkSelfPermission(this, permission) ==
            PackageManager.PERMISSION_GRANTED

        if (!granted) {
            runCatching { notificationPermissionLauncher.launch(permission) }
        }
    }

    /**
     * Playback is tied to the activity being visible.
     *
     * The player is an application-scoped singleton, so without this a clip would keep playing
     * after the user left the app — which for a soundboard is never what they meant.
     */
    private fun stopPlaybackWhenBackgrounded() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    // Suspends until the activity stops, then the finally block runs.
                    awaitCancellation()
                } finally {
                    player.stopBlocking()
                }
            }
        }
    }
}
