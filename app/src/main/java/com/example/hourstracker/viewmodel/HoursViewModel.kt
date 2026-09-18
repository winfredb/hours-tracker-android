package com.example.hourstracker.viewmodel

import com.example.hourstracker.database.HoursTrackerDatabase
import com.example.hourstracker.database.JobSiteDao
import com.example.hourstracker.database.WorkSessionDao
import com.example.hourstracker.model.JobSite
import com.example.hourstracker.model.WorkSession
import dagger.hilt.android.lifecycle.HiltViewModel
import hilt.primaryConstructor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectAsState
import kotlinx.coroutines.isCancelled
import kotlinx.coroutines.viewModelScope
import javax.inject.Inject

@HiltViewModel
class HoursViewModel @Inject constructor(
    private val database: HoursTrackerDatabase
) : ViewModel() {

    private val _jobSites = MutableFlow<List<JobSite>>(emptyList())
    val jobSites: Flow<List<JobSite>> = _jobSites.asStateFlow()

    private val _sessions = MutableFlow<List<WorkSession>>(emptyList())
    val sessions: Flow<List<WorkSession>> = _sessions.asStateFlow()

    init {
        loadData()
    }

    fun loadData() {
        viewModelScope.launch {
            _jobSites.value = database.jobSiteDao.getAllJobSites().firstOrNull() ?: emptyList()
            _sessions.value = database.workSessionDao.getAllSessions().firstOrNull() ?: emptyList()
        }
    }

    fun addSession(session: WorkSession) {
        viewModelScope.launch {
            database.workSessionDao.insertSession(session)
            _sessions.value = database.workSessionDao.getAllSessions().firstOrNull() ?: emptyList()
        }
    }

    fun updateSession(session: WorkSession) {
        viewModelScope.launch {
            database.workSessionDao.updateSession(session)
            _sessions.value = database.workSessionDao.getAllSessions().firstOrNull() ?: emptyList()
        }
    }

    fun deleteSession(session: WorkSession) {
        viewModelScope.launch {
            database.workSessionDao.deleteSession(session)
            _sessions.value = database.workSessionDao.getAllSessions().firstOrNull() ?: emptyList()
        }
    }

    fun getSessionsInPeriod(start: String, end: String): Flow<List<WorkSession>> {
        return database.workSessionDao.getSessionsInPeriod(start, end)
    }

    fun exportPdf(outputPath: String, startDate: String, endDate: String) {
        viewModelScope.launch {
            val sessions = database.workSessionDao.getSessionsInPeriod(startDate, endDate).firstOrNull() ?: emptyList()
            val jobSites = database.jobSiteDao.getAllJobSites().firstOrNull() ?: emptyList()

            // Generate PDF
            generatePdf(sessions, jobSites, outputPath)
        }
    }

    private fun generatePdf(sessions: List<WorkSession>, jobSites: List<JobSite>, outputPath: String) {
        // Simple PDF generation using iText logic
        // In a real app, you'd use the full iText API
        val totalHours = sessions.sumBy { calculateHours(it.startTime, it.endTime) - it.breakMinutes }

        // For now, just log the data
        println("PDF Export - Total Hours: $totalHours")
        println("Sessions: ${sessions.size}")
        println("Job Sites: ${jobSites.size}")

        // TODO: Implement full PDF generation with iText 7
        // This would create a proper formatted PDF report
    }

    private fun calculateHours(start: String, end: String): Double {
        val s = java.time.LocalTime.parse(start)
        val e = java.time.LocalTime.parse(end)
        return e.minusHours(s.hour).minusMinutes(s.minute).toMinutes() / 60.0
    }
}