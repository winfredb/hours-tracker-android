package com.example.hourstracker.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.hourstracker.database.HoursTrackerDatabase
import com.example.hourstracker.model.JobSite
import com.example.hourstracker.model.WorkSession
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@HiltViewModel
class HoursViewModel @Inject constructor(
    private val database: HoursTrackerDatabase
) : ViewModel() {

    private val _jobSites: MutableStateFlow<List<JobSite>> = MutableStateFlow(emptyList())
    val jobSites: StateFlow<List<JobSite>> = _jobSites.asStateFlow()

    private val _sessions: MutableStateFlow<List<WorkSession>> = MutableStateFlow(emptyList())
    val sessions: StateFlow<List<WorkSession>> = _sessions.asStateFlow()

    init {
        refreshData()
    }

    fun refreshData() {
        viewModelScope.launch {
            _jobSites.value = database.jobSiteDao().getAllJobSites().first()
            _sessions.value = database.workSessionDao().getAllSessions().first()
        }
    }

    fun addSession(session: WorkSession) {
        viewModelScope.launch {
            database.workSessionDao().insertSession(session)
            _sessions.value = database.workSessionDao().getAllSessions().first()
        }
    }

    fun updateSession(session: WorkSession) {
        viewModelScope.launch {
            database.workSessionDao().updateSession(session)
            _sessions.value = database.workSessionDao().getAllSessions().first()
        }
    }

    fun deleteSession(session: WorkSession) {
        viewModelScope.launch {
            database.workSessionDao().deleteSession(session)
            _sessions.value = database.workSessionDao().getAllSessions().first()
        }
    }

    fun addJobSite(name: String, location: String?) {
        viewModelScope.launch {
            database.jobSiteDao().insertOrReplaceJobSite(0, name, location, "#6750A4")
            _jobSites.value = database.jobSiteDao().getAllJobSites().first()
        }
    }

    fun getSessionsInPeriod(start: String, end: String): Flow<List<WorkSession>> {
        return database.workSessionDao().getSessionsInPeriod(start, end)
    }

    fun exportPdf(outputPath: String, startDate: String, endDate: String) {
        viewModelScope.launch {
            val sessions = database.workSessionDao().getSessionsInPeriod(startDate, endDate).first()
            val jobSites = database.jobSiteDao().getAllJobSites().first()
            generatePdf(sessions, jobSites, outputPath, startDate, endDate)
        }
    }

    private fun generatePdf(
        sessions: List<WorkSession>,
        jobSites: List<JobSite>,
        outputPath: String,
        startDate: String,
        endDate: String
    ) {
        val minutes = sessions.sumOf { durationMinutes(it.startTime, it.endTime) - it.breakMinutes }
        val lines = ArrayList<String>()
        lines.add("Hours Tracker Report")
        lines.add("Period: $startDate to $endDate")
        lines.add("Total sessions: ${sessions.size}")
        lines.add("Total tracked hours: $minutes minutes")
        lines.add("")
        sessions.forEach { s ->
            val job = jobSites.find { it.id == s.jobSiteId }
            lines.add("${s.date} | ${s.startTime}-${s.endTime} | ${s.breakMinutes}min | ${job?.name ?: "Unknown"}")
        }

        val pdfBytes = buildPdf(lines)
        val out = File(outputPath)
        out.parentFile?.mkdirs()
        val fos = FileOutputStream(out)
        fos.write(pdfBytes)
        fos.close()
    }

    private fun buildPdf(lines: List<String>): ByteArray {
        // Minimal single-page PDF document with a Helvetica text block.
        val font = "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"
        val page = ("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] " +
            "/Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>")

        val content = StringBuilder()
        content.append("BT\n/F1 12 Tf\n72 740 Td\n")
        lines.forEach { line ->
            val esc = line
                .replace("\\", "\\\\")
                .replace("(", "\\(")
                .replace(")", "\\)")
            content.append("(").append(esc).append(") Tj\n0 -16 Td\n")
        }
        content.append("ET")
        val contentBody = "<< /Length ${content.length} >>\nstream\n${content}\nendstream"

        val objects = arrayOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            page,
            font,
            contentBody
        )

        val out = StringBuilder("%PDF-1.4\n")
        val offsets = ArrayList<Int>()
        var pos = out.length
        for ((i, body) in objects.withIndex()) {
            offsets.add(pos)
            val obj = "${i + 1} 0 obj\n$body\nendobj\n"
            out.append(obj)
            pos = out.length
        }
        val xrefPos = out.length
        val numObjs = objects.size + 1
        val xref = StringBuilder("xref\n0 $numObjs\n")
        xref.append("0000000000 65535 f \n")
        offsets.forEach { o -> xref.append(String.format("%010d 00000 n \n", o)) }
        xref.append("trailer\n<< /Size $numObjs /Root 1 0 R >>\n")
        xref.append("startxref\n$xrefPos\n%%EOF\n")

        out.append(xref)
        return out.toString().toByteArray()
    }

    private fun durationMinutes(start: String, end: String): Int {
        val s = java.time.LocalTime.parse(start)
        val e = java.time.LocalTime.parse(end)
        var diff = e.toSecondOfDay() - s.toSecondOfDay()
        if (diff < 0) diff += 24 * 3600
        return diff / 60
    }
}