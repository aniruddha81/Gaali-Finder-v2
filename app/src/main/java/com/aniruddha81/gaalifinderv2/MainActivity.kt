package com.aniruddha81.gaalifinderv2

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.aniruddha81.gaalifinderv2.ui.theme.GaaliFinderv2Theme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        installSplashScreen()

        enableEdgeToEdge()
        setContent {
            GaaliFinderv2Theme {
                RequestNotificationPermission()
                HomePage()
            }
        }
    }
}

@Composable
fun RequestNotificationPermission() {
    val context = LocalContext.current

    // Notification permission is only required for Android 13 (Tiramisu) and above
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val permission = android.Manifest.permission.POST_NOTIFICATIONS

        val launcher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            if (!isGranted) {
                Toast.makeText(context, "Notification permission denied", Toast.LENGTH_SHORT).show()
            }
        }

        LaunchedEffect(Unit) {
            if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
                launcher.launch(permission)
            }
        }
    }
}

