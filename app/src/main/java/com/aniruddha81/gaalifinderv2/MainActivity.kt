package com.aniruddha81.gaalifinderv2

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.aniruddha81.gaalifinderv2.appwrite.AppwriteRepository
import com.aniruddha81.gaalifinderv2.data.AudioDatabase
import com.aniruddha81.gaalifinderv2.data.AudioRepository
import com.aniruddha81.gaalifinderv2.ui.theme.GaaliFinderv2Theme
import com.aniruddha81.gaalifinderv2.viewmodel.AudioViewModel
import io.appwrite.Client
import io.appwrite.services.Storage

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val database = AudioDatabase.getDatabase(this)
        val dao = database.audioDao()

        val repository = AudioRepository(dao = database.audioDao())

        val appwriteRepository = AppwriteRepository(applicationContext, dao)

        val viewModel = AudioViewModel(
            repository = repository,
            context = this
        )

        enableEdgeToEdge()
        setContent {
            GaaliFinderv2Theme {
                HomePage(viewModel)
            }
        }
    }
}
