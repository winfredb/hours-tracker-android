package com.example.hourstracker.view

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.hourstracker.model.JobSite
import com.example.hourstracker.model.WorkSession
import com.example.hourstracker.viewmodel.HoursViewModel

@Composable
fun HoursTrackerScreen(
    viewModel: HoursViewModel,
    jobSites: List<JobSite>,
    sessions: List<WorkSession>
) {
    var byDate by remember { mutableStateOf("") }
    var toDate by remember { mutableStateOf("") }
    var selectedJobSiteId by remember { mutableStateOf<Int?>(null) }
    var editSessionId by remember { mutableStateOf<Int?>(null) }
    var editBreakMinutes by remember { mutableStateOf("0") }
    var editStartTime by remember { mutableStateOf("08:00") }
    var editEndTime by remember { mutableStateOf("17:00") }
    var editDate by remember { mutableStateOf(java.time.LocalDate.now().toString()) }
    var pendingStop by remember { mutableStateOf(false) }
    var stopBreakMinutes by remember { mutableStateOf("0") }

    val clockRunning by viewModel.clockRunning.collectAsState()
    val clockPaused by viewModel.clockPaused.collectAsState()
    val startedAt by viewModel.startedAt.collectAsState()
    val elapsedSec by viewModel.elapsedSeconds.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()

    Column(
        modifier = Modifier
            .padding(16.dp)
            .fillMaxSize()
    ) {
        Text("Hours Tracker")

        // Large orange halo button: Start when idle, Pause/Resume when running.
        // Smaller square Stop button appears below it while the clock runs.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                if (!clockRunning) "Not working"
                else if (clockPaused) "Paused since $startedAt"
                else "Working since $startedAt",
                style = MaterialTheme.typography.titleMedium
            )

            if (clockRunning) {
                Text(
                    formatElapsedSec(elapsedSec),
                    style = MaterialTheme.typography.headlineLarge,
                    color = if (clockPaused) MaterialTheme.colorScheme.outline
                    else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                )
            }

            // Big orange halo — Start when idle, Pause/Resume when running
            Box(
                modifier = Modifier
                    .size(200.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFFF8C00))
                    .clickable {
                        if (clockRunning) {
                            if (clockPaused) viewModel.resumeClock() else viewModel.pauseClock()
                        } else {
                            viewModel.startClock()
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (!clockRunning) "▶ Start"
                    else if (clockPaused) "▶ Resume"
                    else "⏸ Pause",
                    fontSize = 28.sp,
                    color = Color.White
                )
            }

            // Smaller square Stop button, only while running
            if (clockRunning) {
                Spacer(Modifier.height(16.dp))
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFFD32F2F))
                        .clickable {
                            stopBreakMinutes = "0"
                            pendingStop = true
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text("■ Stop", fontSize = 15.sp, color = Color.White)
                }
            }
        }

        // Save status feedback
        if (statusMessage.isNotEmpty()) {
            Text(statusMessage, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
        }

        // Date range filter
        OutlinedTextField(
            value = byDate,
            onValueChange = { byDate = it },
            label = { Text("From") },
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
        )
        OutlinedTextField(
            value = toDate,
            onValueChange = { toDate = it },
            label = { Text("To") },
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
        )

        if (sessions.isEmpty()) {
            Text("No sessions recorded yet")
        } else {
            SessionsList(
                sessions = sessions,
                jobSites = jobSites,
                onEdit = { session ->
                    editSessionId = session.id
                    editDate = session.date
                    editStartTime = session.startTime
                    editEndTime = session.endTime
                    editBreakMinutes = session.breakMinutes.toString()
                    selectedJobSiteId = session.jobSiteId
                },
                onDelete = { session -> viewModel.deleteSession(session) }
            )
        }

        // Add job site (PDFs now auto-save to their own folder on each Stop)
        FloatingActionButton(onClick = {
            viewModel.addJobSite("New Job Site", null)
        }) {
            Text("+")
        }

        // Stop-clock dialog with break minutes
        if (pendingStop) {
            AlertDialog(
                onDismissRequest = { pendingStop = false },
                confirmButton = {
                    Button(onClick = {
                        viewModel.stopClock(stopBreakMinutes.toIntOrNull() ?: 0)
                        pendingStop = false
                    }) {
                        Text("Stop & Save")
                    }
                },
                dismissButton = {
                    Button(onClick = { pendingStop = false }) {
                        Text("Continue Working")
                    }
                },
                title = { Text("Stop Work Clock") },
                text = {
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        Text("Total elapsed: ${formatElapsedSec(elapsedSec)}")
                        OutlinedTextField(
                            value = stopBreakMinutes,
                            onValueChange = { stopBreakMinutes = it },
                            label = { Text("Break minutes") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            )
        }

        // Edit session dialog
        if (editSessionId != null) {
            AlertDialog(
                onDismissRequest = { editSessionId = null },
                confirmButton = {
                    Button(onClick = {
                        val updated = WorkSession(
                            id = editSessionId!!,
                            jobSiteId = selectedJobSiteId ?: 1,
                            date = editDate,
                            startTime = editStartTime,
                            endTime = editEndTime,
                            breakMinutes = editBreakMinutes.toIntOrNull() ?: 0,
                            notes = null
                        )
                        viewModel.updateSession(updated)
                        editSessionId = null
                    }) {
                        Text("Save")
                    }
                },
                dismissButton = {
                    Button(onClick = { editSessionId = null }) {
                        Text("Cancel")
                    }
                },
                title = { Text("Edit Session") },
                text = {
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        OutlinedTextField(
                            value = editDate,
                            onValueChange = { editDate = it },
                            label = { Text("Date") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = editStartTime,
                            onValueChange = { editStartTime = it },
                            label = { Text("Start Time") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = editEndTime,
                            onValueChange = { editEndTime = it },
                            label = { Text("End Time") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = editBreakMinutes,
                            onValueChange = { editBreakMinutes = it },
                            label = { Text("Break Minutes") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            )
        }
    }
}

@Composable
fun SessionsList(
    sessions: List<WorkSession>,
    jobSites: List<JobSite>,
    onEdit: (WorkSession) -> Unit,
    onDelete: (WorkSession) -> Unit
) {
    sessions.forEach { session ->
        val jobSite = jobSites.find { it.id == session.jobSiteId }
        Card(modifier = Modifier.padding(vertical = 8.dp).fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row {
                    Text("${session.date} - ${jobSite?.name ?: "Unknown"}")
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { onEdit(session) }) { Text("Edit") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { onDelete(session) }) { Text("Delete") }
                }
                Text("Start: ${session.startTime}  End: ${session.endTime}  Breaks: ${session.breakMinutes} min")
            }
        }
    }
}

private fun formatElapsedSec(totalSeconds: Long): String {
    val sec = totalSeconds
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val ss = sec % 60
    if (h > 0) return "${h}h ${m}m ${ss}s"
    if (m > 0) return "${m}m ${ss}s"
    return "${ss}s"
}