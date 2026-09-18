package com.example.hourstracker.view

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.hourstracker.database.HoursTrackerDatabase
import com.example.hourstracker.model.JobSite
import com.example.hourstracker.model.WorkSession
import com.example.hourstracker.viewmodel.HoursViewModel
import kotlinx.coroutines.flow.collectAsState

@Composable
fun HoursTrackerScreen(
    viewModel: HoursViewModel = viewModel(),
    jobSites: List<JobSite> = emptyList(),
    sessions: List<WorkSession> = emptyList()
) {
    var byDate by remember { mutableStateOf("") }
    var toDate by remember { mutableStateOf("") }
    var selectedJobSiteId by remember { mutableStateOf<Int?>(null) }
    var editSessionId by remember { mutableStateOf<Int?>(null) }
    var editBreakMinutes by remember { mutableStateOf(0) }
    var editStartTime by remember { mutableStateOf("08:00") }
    var editEndTime by remember { mutableStateOf("17:00") }
    var editDate by remember { mutableStateOf(java.time.LocalDate.now().toString()) }

    Column(
        modifier = Modifier
            .padding(16.dp)
            .fillMaxSize()
    ) {
        // Header
        Text(
            text = "Hours Tracker",
            style = MaterialTypography.headingLarge,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // Job Sites FAB
        FloatingActionButton(
            onClick = {
                // Add new job site - open dialog
            }
        ) {
            Icon(Icons.Default.Add, contentDescription = "Add Job Site")
        }

        // Date range filter
        OutlinedTextField(
            value = byDate,
            onValueChange = { byDate = it },
            label = { Text("From") ),
            modifier = Modifier.width(Infinity).margin(bottom = 8.dp)
        )
        OutlinedTextField(
            value = toDate,
            onValueChange = { toDate = it },
            label = { Text("To") ),
            modifier = Modifier.width(Infinity).margin(bottom = 16.dp)
        )

        // Sessions list
        if (sessions.isEmpty()) {
            Text(
                text = "No sessions recorded yet",
                modifier = Modifier.padding(top = 32.dp)
            )
        } else {
            SessionsList(
                sessions = sessions,
                jobSites = jobSites,
                onEdit = { session ->
                    editSessionId = session.id
                    editDate = java.time.LocalDate.parse(session.date)
                    editStartTime = session.startTime
                    editEndTime = session.endTime
                    editBreakMinutes = session.breakMinutes
                    selectedJobSiteId = session.jobSiteId
                },
                onDelete = { session ->
                    viewModel.deleteSession(session)
                }
            )
        }

        // Action buttons at bottom
        Button(onClick = {
            // Export PDF
            val outputPath = "/sdcard/HoursTracker/pay_period.pdf"
            viewModel.exportPdf(outputPath, byDate, toDate)
        }) {
            Text("Export PDF for Period")
        }.modifier = Modifier.fillMaxWidth().padding(top = 8.dp)

        // Edit session dialog (shown inline for simplicity)
        if (editSessionId != null) {
            AlertDialog(
                onDismissRequest = { editSessionId = null },
                title = { Text("Edit Session") },
                text = {
                    Column(
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = editDate,
                            onValueChange = { editDate = it },
                            label = { Text("Date") )
                        )
                        OutlinedTextField(
                            value = editStartTime,
                            onValueChange = { editStartTime = it },
                            label = { Text("Start Time") )
                        )
                        OutlinedTextField(
                            value = editEndTime,
                            onValueChange = { editEndTime = it },
                            label = { Text("End Time") )
                        )
                        OutlinedTextField(
                            value = editBreakMinutes.toString(),
                            onValueChange = { editBreakMinutes = it.toInt() },
                            label = { Text("Break Minutes") )
                        )
                        // Job site selector
                        outlinedTextField(
                            value = selectedJobSiteId?.let { jobSites.find { it.id == it }?.name ?: "Select Job Site" } ?: "Select Job Site",
                            onValueChange = { /* handle selection */ },
                            label = { Text("Job Site") )
                        )
                    }
                },
                actions = {
                    Button(onClick = {
                        // Save edited session
                        val updatedSession = WorkSession(
                            id = editSessionId!!,
                            jobSiteId = selectedJobSiteId!!,
                            date = editDate,
                            startTime = editStartTime,
                            endTime = editEndTime,
                            breakMinutes = editBreakMinutes,
                            notes = null
                        )
                        viewModel.updateSession(updatedSession)
                        editSessionId = null
                    }) {
                        Text("Save")
                    }
                    Button(onClick = { editSessionId = null }) {
                        Text("Cancel")
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
    sessions.mapIndexed { index, session ->
        val jobSite = jobSites.find { it.id == session.jobSiteId }
        Card(
            modifier = Modifier
                .padding(vertical = 8.dp)
                .fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row {
                    Text(
                        text = "${session.date} - ${jobSite?.name ?: "Unknown"}",
                        style = MaterialTypography.bodyLarge
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = { onEdit(session) }
                    ) {
                        Text("Edit")
                    }
                    OutlinedButton(
                        onClick = { onDelete(session) }
                    ) {
                        Text("Delete")
                    }
                }

                Row {
                    Text("Start: ${session.startTime}")
                    Spacer(Modifier.width(8.dp))
                    Text("End: ${session.endTime}")
                    Spacer(Modifier.width(8.dp))
                    Text("Breaks: ${session.breakMinutes} min")
                }
            }
        }
    }
}