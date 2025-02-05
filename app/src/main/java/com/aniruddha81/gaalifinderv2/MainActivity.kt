package com.aniruddha81.gaalifinderv2

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import com.aniruddha81.gaalifinderv2.ui.theme.GaaliFinderv2Theme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val viewModel = ViewModelProvider(this)[ViewModel::class.java]
        enableEdgeToEdge()
        setContent {
            GaaliFinderv2Theme {
                Scaffold(modifier = Modifier.fillMaxSize()) { _ ->
                    HomePage(viewModel)
                }
            }
        }
    }
}
