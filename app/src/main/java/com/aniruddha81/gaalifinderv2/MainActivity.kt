package com.aniruddha81.gaalifinderv2

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.aniruddha81.gaalifinderv2.ui.theme.GaaliFinderv2Theme
import com.aniruddha81.gaalifinderv2.viewmodel.AudioViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val viewModel = AudioViewModel(this)

        enableEdgeToEdge()
        setContent {
            GaaliFinderv2Theme {
                HomePage(viewModel)
            }
        }
    }
}
