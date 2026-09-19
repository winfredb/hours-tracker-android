package com.example.hourstracker.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.util.Log
import com.example.hourstracker.database.HoursTrackerDatabase
import com.example.hourstracker.model.JobSite
import com.example.hourstracker.model.WorkSession
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@HiltViewModel
class HoursViewModel @Inject constructor(
    private val database: HoursTrackerDatabase,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    private val _jobSites: MutableStateFlow<List<JobSite>> = MutableStateFlow(emptyList())
    val jobSites: StateFlow<List<JobSite>> = _jobSites.asStateFlow()

    private val _selectedJobSiteId: MutableStateFlow<Int?> = MutableStateFlow(null)
    val selectedJobSiteId: StateFlow<Int?> = _selectedJobSiteId.asStateFlow()

    private val _sessions: MutableStateFlow<List<WorkSession>> = MutableStateFlow(emptyList())
    val sessions: StateFlow<List<WorkSession>> = _sessions.asStateFlow()

    private val _clockRunning: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val clockRunning: StateFlow<Boolean> = _clockRunning.asStateFlow()

    private val _clockPaused: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val clockPaused: StateFlow<Boolean> = _clockPaused.asStateFlow()

    private val _startedAt: MutableStateFlow<String> = MutableStateFlow("")
    val startedAt: StateFlow<String> = _startedAt.asStateFlow()

    private val _elapsedSeconds: MutableStateFlow<Long> = MutableStateFlow(0L)
    val elapsedSeconds: StateFlow<Long> = _elapsedSeconds.asStateFlow()

    private val _statusMessage: MutableStateFlow<String> = MutableStateFlow("")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    init {
        refreshData()
        Log.d("HoursTracker", "ViewModel initialized")
    }

    /** Records the current time and starts the work clock for the selected project. */
    fun startClock() {
        if (_clockRunning.value) return
        val selectedId = _selectedJobSiteId.value
        if (selectedId == null) {
            _statusMessage.value = "Pick a project before starting"
            return
        }
        val now = java.time.LocalTime.now()
        _startedAt.value = now.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
        _clockRunning.value = true
        _clockPaused.value = false
        _elapsedSeconds.value = 0L
        viewModelScope.launch {
            var elapsed = 0L
            while (_clockRunning.value && elapsed < 24 * 3600L) {
                kotlinx.coroutines.delay(1000L)
                if (!_clockPaused.value) {
                    elapsed++
                    _elapsedSeconds.value = elapsed
                }
            }
        }
    }

    /** Selects which project the clock records against. */
    fun selectJobSite(id: Int) {
        _selectedJobSiteId.value = id
        val name = _jobSites.value.find { it.id == id }?.name
        if (!_clockRunning.value) _statusMessage.value = "Tracking: ${name ?: ""}"
    }

    /** Pauses the running clock, keeping the accumulated time so far. */
    fun pauseClock() {
        if (!_clockRunning.value || _clockPaused.value) return
        _clockPaused.value = true
    }

    /** Resumes a paused clock. */
    fun resumeClock() {
        if (!_clockRunning.value || !_clockPaused.value) return
        _clockPaused.value = false
    }

    /** Stops the clock and saves a WorkSession for the elapsed time to the given project. */
    fun stopClock(jobSiteId: Int) {
        if (!_clockRunning.value) return
        val start = _startedAt.value
        val end = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
        val today = java.time.LocalDate.now().toString()
        addSession(
            WorkSession(
                id = 0,
                jobSiteId = jobSiteId,
                date = today,
                startTime = start,
                endTime = end,
                breakMinutes = 0,
                notes = "clock"
            )
        )
        _clockRunning.value = false
        _clockPaused.value = false
        _startedAt.value = ""
        _elapsedSeconds.value = 0L
    }

    fun refreshData() {
        viewModelScope.launch {
            try {
                val sites = database.jobSiteDao().getAllJobSites().first()
                _jobSites.value = sites
                if (_selectedJobSiteId.value == null && sites.isNotEmpty()) {
                    _selectedJobSiteId.value = sites[0].id
                }
                _sessions.value = database.workSessionDao().getAllSessions().first()
                Log.d("HoursTracker", "refreshData loaded ${_sessions.value.size} sessions")
                _statusMessage.value = "Loaded ${_sessions.value.size} sessions ✓"
            } catch (e: Throwable) {
                Log.e("HoursTracker", "refreshData failed", e)
                _statusMessage.value = "Refresh failed: ${e.message}"
            }
        }
    }

    /** Insert a session, write/refresh its project's Excel file, and report to the UI. */
    fun addSession(session: WorkSession) {
        Log.d("HoursTracker", "addSession called: ${session.date} ${session.startTime}-${session.endTime}")
        viewModelScope.launch {
            try {
                database.workSessionDao().insertSession(session)
                _sessions.value = database.workSessionDao().getAllSessions().first()
                // Rebuild this session's project workbook so it includes the new row.
                val files = refreshAllProjectFiles()
                Log.d("HoursTracker", "Saved OK + refreshed ${files.size} project file(s)")
                _statusMessage.value = "Saved ✓ ${session.date} ${session.startTime}-${session.endTime}"
            } catch (e: Throwable) {
                Log.e("HoursTracker", "insertSession FAILED", e)
                _statusMessage.value = "Save FAILED: ${e.message}"
            }
        }
    }

    fun updateSession(session: WorkSession) {
        viewModelScope.launch {
            try {
                database.workSessionDao().updateSession(session)
                _sessions.value = database.workSessionDao().getAllSessions().first()
                refreshAllProjectFiles()
                _statusMessage.value = "Updated ✓ ${session.date}"
            } catch (e: Throwable) {
                _statusMessage.value = "Update FAILED: ${e.message}"
            }
        }
    }

    fun deleteSession(session: WorkSession) {
        viewModelScope.launch {
            try {
                database.workSessionDao().deleteSession(session)
                _sessions.value = database.workSessionDao().getAllSessions().first()
                refreshAllProjectFiles()
                _statusMessage.value = "Deleted ✓"
            } catch (e: Throwable) {
                _statusMessage.value = "Delete FAILED: ${e.message}"
            }
        }
    }

    fun addJobSite(name: String, location: String?) {
        if (name.isBlank()) {
            _statusMessage.value = "Project name can't be empty"
            return
        }
        viewModelScope.launch {
            try {
                val newId = database.jobSiteDao()
                    .insertJobSite(JobSite(name = name.trim(), location = location))
                _jobSites.value = database.jobSiteDao().getAllJobSites().first()
                _selectedJobSiteId.value = newId.toInt()
                _statusMessage.value = "Project added ✓"
            } catch (e: Throwable) {
                _statusMessage.value = "Add project FAILED: ${e.message}"
            }
        }
    }

    /** Renames / re-locates an existing project. */
    fun renameJobSite(id: Int, name: String, location: String?) {
        if (name.isBlank()) {
            _statusMessage.value = "Project name can't be empty"
            return
        }
        val site = _jobSites.value.find { it.id == id } ?: return
        viewModelScope.launch {
            try {
                database.jobSiteDao().updateJobSite(id, name.trim(), location?.takeIf { it.isNotBlank() }, site.color)
                _jobSites.value = database.jobSiteDao().getAllJobSites().first()
                _statusMessage.value = "Project renamed ✓"
            } catch (e: Throwable) {
                _statusMessage.value = "Rename FAILED: ${e.message}"
            }
        }
    }

    /** Deletes a project and unselects it. Sessions referencing it are kept (FK is nullable-safe in export). */
    fun deleteJobSite(id: Int) {
        viewModelScope.launch {
            try {
                database.jobSiteDao().deleteJobSite(id)
                val sites = database.jobSiteDao().getAllJobSites().first()
                _jobSites.value = sites
                if (_selectedJobSiteId.value == id) {
                    _selectedJobSiteId.value = sites.firstOrNull()?.id
                }
                _statusMessage.value = "Project deleted ✓"
            } catch (e: Throwable) {
                _statusMessage.value = "Delete FAILED: ${e.message}"
            }
        }
    }

    fun getSessionsInPeriod(start: String, end: String): Flow<List<WorkSession>> {
        return database.workSessionDao().getSessionsInPeriod(start, end)
    }

    /**
     * Writes one Excel workbook per project into public Downloads:
     *   <downloads>/HoursTracker/<ProjectName>.xlsx
     * Rebuilt in full whenever sessions change, so each file always reflects
     * every recorded session for that project.
     */

    /**
     * Rebuild every project's workbook from its sessions.
     * Returns the list of files written.
     */
    private suspend fun refreshAllProjectFiles(): List<File> {
        val jobSites = database.jobSiteDao().getAllJobSites().first()
        val written = ArrayList<File>()
        jobSites.forEach { site ->
            val sessions = database.workSessionDao().getSessionsForJobSite(site.id).first()
            val out = writeProjectXlsx(site, sessions)
            written.add(out)
            Log.d("HoursTracker", "refreshed ${out.getAbsolutePath()} (${sessions.size} rows)")
        }
        return written
    }

    private fun writeProjectXlsx(site: JobSite, sessions: List<WorkSession>): File {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val dir = File(downloadsDir, "HoursTracker")
        dir.mkdirs()
        val safeName = site.name.replace("/", "-").replace("\\", "-").trim() + ".xlsx"
        val out = File(dir, safeName)
        val bytes = buildXlsx(buildProjectRows(site, sessions))
        val fos = FileOutputStream(out)
        try {
            fos.write(bytes)
        } finally {
            fos.close()
        }
        return out
    }

    private fun buildProjectRows(site: JobSite, sessions: List<WorkSession>): List<List<String>> {
        val rows = ArrayList<List<String>>()
        rows.add(listOf("Date", "Start", "End", "Break (min)", "Worked (min)", "Notes"))
        sessions.forEach { s ->
            val worked = durationMinutes(s.startTime, s.endTime) - s.breakMinutes
            rows.add(
                listOf(
                    s.date,
                    s.startTime,
                    s.endTime,
                    s.breakMinutes.toString(),
                    worked.toString(),
                    s.notes ?: ""
                )
            )
        }
        return rows
    }

    private fun buildXlsx(rows: List<List<String>>): ByteArray {
        // Generate a minimal but valid OOXML .xlsx (a ZIP of standard XML parts).
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            zos.writeEntry(
                "[Content_Types].xml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
                    "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                    "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
                    "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                    "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
                    "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" +
                    "</Types>"
            )
            zos.writeEntry(
                "_rels/.rels",
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
                    "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                    "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>" +
                    "</Relationships>"
            )
            zos.writeEntry(
                "xl/workbook.xml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
                    "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" " +
                    "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">" +
                    "<sheets><sheet name=\"Session\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>"
            )
            zos.writeEntry(
                "xl/_rels/workbook.xml.rels",
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
                    "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                    "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>" +
                    "</Relationships>"
            )
            // Build sheet rows as inline strings.
            val sheet = StringBuilder()
            sheet.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
            sheet.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>")
            rows.forEachIndexed { i, row ->
                val r = i + 1
                sheet.append("<row r=\"").append(r).append("\">")
                row.forEachIndexed { c, cell ->
                    val col = ('A'.code + c).toChar()
                    sheet.append("<c r=\"").append(col).append(r)
                        .append("\" t=\"inlineStr\"><is><t>")
                        .append(xmlEscape(cell)).append("</t></is></c>")
                }
                sheet.append("</row>")
            }
            sheet.append("</sheetData></worksheet>")
            zos.writeEntry("xl/worksheets/sheet1.xml", sheet.toString())
        }
        return bos.toByteArray()
    }

    private fun ZipOutputStream.writeEntry(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun xmlEscape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun durationMinutes(start: String, end: String): Int {
        val s = java.time.LocalTime.parse(start)
        val e = java.time.LocalTime.parse(end)
        var diff = e.toSecondOfDay() - s.toSecondOfDay()
        if (diff < 0) diff += 24 * 3600
        return diff / 60
    }
}