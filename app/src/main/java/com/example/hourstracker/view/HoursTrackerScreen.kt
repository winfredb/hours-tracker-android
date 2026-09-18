package com.example.hourstracker.view

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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

    Column(
        modifier = Modifier
            .padding(16.dp)
            .fillMaxSize()
    ) {
        Text("Hours Tracker")

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

        // Action buttons
        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Button(onClick = {
                val outputPath = "/sdcard/HoursTracker/pay_period.pdf"
                viewModel.exportPdf(outputPath, byDate, toDate)
            }) {
                Text("Export PDF for Period")
            }
            Spacer(Modifier.width(8.dp))
            FloatingActionButton(onClick = {
                viewModel.addJobSite("New Job Site", null)
            }) {
                Text("+")
            }
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