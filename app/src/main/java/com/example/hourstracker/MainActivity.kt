package com.example.hourstracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.hilt.lifecycle.HiltViewModel
import com.example.hourstracker.viewmodel.HoursViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectAsState
import kotlinx.coroutines.flow.Flow

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Get the ViewModel
        val viewModel: HoursViewModel = viewModel(HoursViewModel::class.java)

        setContent {
            // Observe UI state
            val jobSites by viewModel.jobSites.collectAsState()
            val sessions by viewModel.sessions.collectAsState()

            HoursTrackerScreen(
                viewModel = viewModel,
                jobSites = jobSites,
                sessions = sessions
            )
        }
    }
}