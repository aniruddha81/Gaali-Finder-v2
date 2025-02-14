package com.aniruddha81.gaalifinderv2.ui

import android.content.Intent
import android.os.Bundle
import android.os.PersistableBundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.aniruddha81.gaalifinderv2.MainActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SplashScreen : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {

        }
        lifecycleScope.launch {
            preloadData()
            startActivity(Intent(this@SplashScreen, MainActivity::class.java))
            finish()
        }
    }

    private suspend fun preloadData() {
        // Simulate data preloading
        delay(2000)
    }
}