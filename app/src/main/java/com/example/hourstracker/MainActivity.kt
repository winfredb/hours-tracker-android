package com.example.hourstracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.hourstracker.view.HoursTrackerScreen
import com.example.hourstracker.viewmodel.HoursViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val viewModel: HoursViewModel = hiltViewModel()
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