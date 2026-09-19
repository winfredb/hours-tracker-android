package com.example.hourstracker.view

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.example.hourstracker.model.JobSite
import com.example.hourstracker.model.WorkSession
import com.example.hourstracker.viewmodel.HoursViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HoursTrackerScreen(
    viewModel: HoursViewModel,
    jobSites: List<JobSite>,
    sessions: List<WorkSession>
) {
    var selectedJobSiteId by remember { mutableStateOf<Int?>(null) }
    var editSessionId by remember { mutableStateOf<Int?>(null) }
    var editBreakMinutes by remember { mutableStateOf("0") }
    var editStartTime by remember { mutableStateOf("08:00") }
    var editEndTime by remember { mutableStateOf("17:00") }
    var editDate by remember { mutableStateOf(java.time.LocalDate.now().toString()) }
    var sessionsExpanded by remember { mutableStateOf(false) }
    var pendingProjectStop by remember { mutableStateOf(false) }

    // Project drawer + management state
    var showProjectsDrawer by remember { mutableStateOf(false) }
    var showAddProject by remember { mutableStateOf(false) }
    var newProjectName by remember { mutableStateOf("") }
    var newProjectLocation by remember { mutableStateOf("") }
    var showManageProjects by remember { mutableStateOf(false) }
    var manageJobSiteId by remember { mutableStateOf<Int?>(null) }
    var renameName by remember { mutableStateOf("") }
    var renameLocation by remember { mutableStateOf("") }

    val clockRunning by viewModel.clockRunning.collectAsState()
    val clockPaused by viewModel.clockPaused.collectAsState()
    val startedAt by viewModel.startedAt.collectAsState()
    val elapsedSec by viewModel.elapsedSeconds.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()

    // Dark mode toggle
    var isDark by remember { mutableStateOf(false) }

    AppTheme(dark = isDark) {
        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxSize()
            ) {
                    // Large gradient halo button: Start when idle, Pause/Resume when running.
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp, bottom = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            when {
                                !clockRunning -> "Idle"
                                clockPaused -> "Paused since $startedAt"
                                else -> "Working since $startedAt"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        if (clockRunning) {
                            Text(
                                formatElapsedSec(elapsedSec),
                                style = MaterialTheme.typography.headlineLarge,
                                color = if (clockPaused) MaterialTheme.colorScheme.outline
                                else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 4.dp, bottom = 14.dp)
                            )
                        }

                        // Modern halo: soft outer glow ring + vertical gradient + rounded label.
                        Box(
                            modifier = Modifier
                                .size(236.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFFF6D00).copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(212.dp)
                                    .clip(CircleShape)
                                    .background(
                                        Brush.verticalGradient(
                                            colorStops = arrayOf(
                                                kotlin.Pair(0.0f, Color(0xFFFFAA00)),
                                                kotlin.Pair(1.0f, Color(0xFFFF4D00))
                                            )
                                        )
                                    )
                                    .clickable {
                                        if (clockRunning) {
                                            if (clockPaused) viewModel.resumeClock() else viewModel.pauseClock()
                                        } else {
                                            viewModel.startClock()
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Text(
                                        if (!clockRunning) "▶"
                                        else if (clockPaused) "▶"
                                        else "⏸",
                                        fontSize = 56.sp,
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        if (!clockRunning) "Start"
                                        else if (clockPaused) "Resume"
                                        else "Pause",
                                        fontSize = 20.sp,
                                        color = Color.White,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }

                        // Smaller square Stop button, only while running
                        if (clockRunning) {
                            Spacer(Modifier.height(18.dp))
                            Box(
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(
                                        Brush.verticalGradient(
                                            colorStops = arrayOf(
                                                kotlin.Pair(0.0f, Color(0xFFFF6B6B)),
                                                kotlin.Pair(1.0f, Color(0xFFD32F2F))
                                            )
                                        )
                                    )
                                    .clickable {
                                        // Pause immediately so time stops accruing, then ask which job.
                                        viewModel.pauseClock()
                                        pendingProjectStop = true
                                    },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("■ Stop", fontSize = 14.sp, color = Color.White)
                                }
                        }
                    }

                    // Save status feedback
                    if (statusMessage.isNotEmpty()) {
                        Text(
                            statusMessage,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    // Date range filter (removed per request)
                    if (sessions.isEmpty()) {
                        Text(
                            "No sessions recorded yet",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 24.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    } else {
                        // Collapsible summary: shows total hours, expands to the full list.
                        val totalMinutes = sessions.sumOf { workedMinutes(it) }
                        ElevatedCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .clickable { sessionsExpanded = !sessionsExpanded },
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary)
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "Sessions",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        "$sessions.size() sessions recorded",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Pill(
                                    "Total ${formatMinutesShort(totalMinutes)}",
                                    container = MaterialTheme.colorScheme.primaryContainer,
                                    content = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    if (sessionsExpanded) "▾" else "▸",
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (sessionsExpanded) {
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
                    }
                }

            // Floating project-menu button
            IconButton(
                onClick = { showProjectsDrawer = true },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
            ) {
                Icon(Icons.Filled.Menu, contentDescription = "Projects")
            }

            // Scrim behind the drawer
            if (showProjectsDrawer) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f))
                        .clickable { showProjectsDrawer = false }
                )
            }

            // Right-side project drawer
            AnimatedVisibility(
                visible = showProjectsDrawer,
                enter = slideInHorizontally { it },
                exit = slideOutHorizontally { it },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .zIndex(1f)
            ) {
                Surface(
                    modifier = Modifier
                        .width(300.dp)
                        .fillMaxHeight(),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text("Projects", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))

                        if (jobSites.isEmpty()) {
                            Text(
                                "No projects yet. Add one to start tracking.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            // Sorted projects list for reference (no selection here — pick the job when you stop the timer).
                            jobSites.sortedBy { it.name }.forEach { site ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                        .padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    ColorDot(site.color)
                                    Spacer(Modifier.width(12.dp))
                                    Column {
                                        Text(site.name, fontWeight = FontWeight.Medium)
                                        if (!site.location.isNullOrBlank()) {
                                            Text(
                                                site.location,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                                Spacer(Modifier.height(6.dp))
                            }
                        }

                        Spacer(Modifier.height(16.dp))
                        OutlinedButton(
                            onClick = {
                                showAddProject = true
                                showProjectsDrawer = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("＋ Add Project") }

                        if (jobSites.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            OutlinedButton(
                                onClick = {
                                    showManageProjects = true
                                    showProjectsDrawer = false
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Manage Projects") }
                        }

                        Spacer(Modifier.height(20.dp))
                        OutlinedButton(
                            onClick = { isDark = !isDark },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                if (isDark) "☀ Light mode" else "🌙 Dark mode"
                            )
                        }
                    }
                }
            }
        }

        // Project-picker dialog when stopping the clock
        if (pendingProjectStop) {
            AlertDialog(
                onDismissRequest = {
                    pendingProjectStop = false
                    viewModel.resumeClock()
                },
                confirmButton = {
                    TextButton(onClick = {
                        pendingProjectStop = false
                        viewModel.resumeClock()
                    }) { Text("Cancel") }
                },
                title = { Text("Which job were you working on?") },
                text = {
                    if (jobSites.isEmpty()) {
                        Text("No projects yet. Add one from the menu (☰).")
                    } else {
                        Column(modifier = Modifier.fillMaxWidth().padding(0.dp)) {
                            jobSites.forEach { site ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                        .clickable {
                                            pendingProjectStop = false
                                            viewModel.stopClock(site.id)
                                        }
                                        .padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    ColorDot(site.color)
                                    Spacer(Modifier.width(12.dp))
                                    Text(
                                        site.name,
                                        modifier = Modifier.weight(1f),
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Spacer(Modifier.height(8.dp))
                            }
                        }
                    }
                }
            )
        }

        // Add-project dialog
        if (showAddProject) {
            AlertDialog(
                onDismissRequest = { showAddProject = false },
                confirmButton = {
                    Button(onClick = {
                        viewModel.addJobSite(newProjectName, newProjectLocation.takeIf { it.isNotBlank() })
                        newProjectName = ""
                        newProjectLocation = ""
                        showAddProject = false
                    }) { Text("Add") }
                },
                dismissButton = {
                    TextButton(onClick = { showAddProject = false }) { Text("Cancel") }
                },
                title = { Text("Add Project") },
                text = {
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        OutlinedTextField(
                            value = newProjectName,
                            onValueChange = { newProjectName = it },
                            label = { Text("Project name") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = newProjectLocation,
                            onValueChange = { newProjectLocation = it },
                            label = { Text("Location (optional)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            )
        }

        // Manage projects dialog (rename / delete)
        if (showManageProjects) {
            AlertDialog(
                onDismissRequest = { showManageProjects = false },
                confirmButton = {
                    TextButton(onClick = { showManageProjects = false }) { Text("Close") }
                },
                title = { Text("Manage Projects") },
                text = {
                    Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                        jobSites.forEach { site ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                ColorDot(site.color)
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    site.name,
                                    modifier = Modifier.weight(1f),
                                    fontWeight = FontWeight.Medium
                                )
                                TextButton(onClick = {
                                    manageJobSiteId = site.id
                                    renameName = site.name
                                    renameLocation = site.location ?: ""
                                }) { Text("Rename") }
                                TextButton(onClick = { viewModel.deleteJobSite(site.id) }) { Text("Delete") }
                            }
                        }
                    }
                }
            )
        }

        // Rename dialog
        if (manageJobSiteId != null) {
            AlertDialog(
                onDismissRequest = { manageJobSiteId = null },
                confirmButton = {
                    Button(onClick = {
                        viewModel.renameJobSite(manageJobSiteId!!, renameName, renameLocation)
                        manageJobSiteId = null
                    }) { Text("Save") }
                },
                dismissButton = {
                    TextButton(onClick = { manageJobSiteId = null }) { Text("Cancel") }
                },
                title = { Text("Rename Project") },
                text = {
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        OutlinedTextField(
                            value = renameName,
                            onValueChange = { renameName = it },
                            label = { Text("Project name") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = renameLocation,
                            onValueChange = { renameLocation = it },
                            label = { Text("Location (optional)") },
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
                    }) { Text("Save") }
                },
                dismissButton = {
                    TextButton(onClick = { editSessionId = null }) { Text("Cancel") }
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

/** Wraps the UI in a modern, cohesive Material 3 color scheme (light or dark). */
@Composable
fun AppTheme(dark: Boolean, content: @Composable () -> Unit) {
    val scheme = if (dark) darkColorScheme(
        Color(0xFF9EC3FF),        // primary
        Color(0xFF0B1F53),        // onPrimary
        Color(0xFF1E3B8F),        // primaryContainer
        Color(0xFFD9E3FF),        // onPrimaryContainer
        Color(0xFF2A5BD7),        // inversePrimary
        Color(0xFFB7C4DB),        // secondary
        Color(0xFF243040),        // onSecondary
        Color(0xFF3A4A61),        // secondaryContainer
        Color(0xFFD7E1EE),        // onSecondaryContainer
        Color(0xFF8FD4B8),        // tertiary
        Color(0xFF10352C),        // onTertiary
        Color(0xFF1F5F4D),        // tertiaryContainer
        Color(0xFFBDE9DA),        // onTertiaryContainer
        Color(0xFF101318),        // background
        Color(0xFFE3E5E9),        // onBackground
        Color(0xFF101318),        // surface
        Color(0xFFE3E5E9),        // onSurface
        Color(0xFF23262E),        // surfaceVariant
        Color(0xFFBEC3CC),        // onSurfaceVariant
        Color(0xFF9EC3FF),        // surfaceTint
        Color(0xFFE3E5E9),        // inverseSurface
        Color(0xFF101318),        // inverseOnSurface
        Color(0xFFF0A8A0),        // error
        Color(0xFF3C0E08),        // onError
        Color(0xFF6B1A14),        // errorContainer
        Color(0xFFFFDAD8),        // onErrorContainer
        Color(0xFF8A9099),        // outline
        Color(0xFF61666D),        // outlineVariant
        Color(0xFF000000)         // scrim
    ) else lightColorScheme(
        Color(0xFF2A5BD7),        // primary
        Color(0xFFFFFFFF),        // onPrimary
        Color(0xFFD9E3FF),        // primaryContainer
        Color(0xFF0B1F53),        // onPrimaryContainer
        Color(0xFF9EC3FF),        // inversePrimary
        Color(0xFF43556B),        // secondary
        Color(0xFFFFFFFF),        // onSecondary
        Color(0xFFD7E1EE),        // secondaryContainer
        Color(0xFF1F3045),        // onSecondaryContainer
        Color(0xFF0E6C5C),        // tertiary
        Color(0xFFFFFFFF),        // onTertiary
        Color(0xFFBDE9DA),        // tertiaryContainer
        Color(0xFF1B3B30),        // onTertiaryContainer
        Color(0xFFF7F8FB),        // background
        Color(0xFF1B1C20),        // onBackground
        Color(0xFFF7F8FB),        // surface
        Color(0xFF1B1C20),        // onSurface
        Color(0xFFE6E9F2),        // surfaceVariant
        Color(0xFF3D4048),        // onSurfaceVariant
        Color(0xFF2A5BD7),        // surfaceTint
        Color(0xFF2E3138),        // inverseSurface
        Color(0xFFEBEDF4),        // inverseOnSurface
        Color(0xFFC62828),        // error
        Color(0xFFFFFFFF),        // onError
        Color(0xFFFFDAD8),        // errorContainer
        Color(0xFF421011),        // onErrorContainer
        Color(0xFF757881),        // outline
        Color(0xFFC3C7D1),        // outlineVariant
        Color(0xFF000000)         // scrim
    )

    MaterialTheme(
        colorScheme = scheme,
        shapes = MaterialTheme.shapes,
        typography = MaterialTheme.typography
    ) {
        content()
    }
}

@Composable
fun Pill(text: String, container: Color, content: Color) {
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(container)
            .padding(horizontal = 12.dp, vertical = 5.dp),
        color = container
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = content)
    }
}

@Composable
fun ColorDot(colorHex: String) {
    Box(
        modifier = Modifier
            .size(12.dp)
            .clip(CircleShape)
            .background(parseHexColor(colorHex))
    )
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
        val projectColor = parseHexColor(jobSite?.color ?: "#6750A4")
        ElevatedCard(
            modifier = Modifier.padding(vertical = 8.dp).fillMaxWidth(),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ColorDot(jobSite?.color ?: "#6750A4")
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            session.date,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            jobSite?.name ?: "Unknown project",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    // Worked-hours badge, tinted with the project color
                    val worked = formatMinutesShort(workedMinutes(session))
                    Pill(
                        worked,
                        container = projectColor.copy(alpha = 0.16f),
                        content = MaterialTheme.colorScheme.onSurface
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${session.startTime} → ${session.endTime}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (session.breakMinutes > 0) {
                        Spacer(Modifier.width(8.dp))
                        Pill(
                            "${session.breakMinutes}m break",
                            container = MaterialTheme.colorScheme.secondaryContainer,
                            content = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { onEdit(session) }) { Text("Edit") }
                    TextButton(onClick = { onDelete(session) }) { Text("Delete") }
                }
            }
        }
    }
}

private fun parseHexColor(hex: String): Color {
    val h = hex.removePrefix("#").trim()
    var v: Long = 0xFF6750A4L
    if (h.isNotEmpty()) {
        v = 0xFF000000L + java.lang.Long.parseLong(h, 16)
    }
    return Color(v)
}

private fun workedMinutes(session: WorkSession): Int {
    val s = java.time.LocalTime.parse(session.startTime)
    val e = java.time.LocalTime.parse(session.endTime)
    var diff = e.toSecondOfDay() - s.toSecondOfDay()
    if (diff < 0) diff += 24 * 3600
    return diff / 60 - session.breakMinutes
}

private fun formatMinutesShort(totalMinutes: Int): String {
    val h = totalMinutes / 60
    val m = totalMinutes % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
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