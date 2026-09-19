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

    /** Records the current time and starts the work clock. */
    fun startClock() {
        if (_clockRunning.value) return
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

    /** Stops the clock and saves a WorkSession for the elapsed time. */
    fun stopClock(breakMinutes: Int) {
        if (!_clockRunning.value) return
        val start = _startedAt.value
        val end = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
        val today = java.time.LocalDate.now().toString()
        val jobSiteId = _jobSites.value.firstOrNull()?.id ?: 1
        addSession(
            WorkSession(
                id = 0,
                jobSiteId = jobSiteId,
                date = today,
                startTime = start,
                endTime = end,
                breakMinutes = breakMinutes,
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
                _jobSites.value = database.jobSiteDao().getAllJobSites().first()
                _sessions.value = database.workSessionDao().getAllSessions().first()
                Log.d("HoursTracker", "refreshData loaded ${_sessions.value.size} sessions")
                _statusMessage.value = "Loaded ${_sessions.value.size} sessions ✓"
            } catch (e: Throwable) {
                Log.e("HoursTracker", "refreshData failed", e)
                _statusMessage.value = "Refresh failed: ${e.message}"
            }
        }
    }

    /** Insert a session, auto-export its PDF, and report to the UI. */
    fun addSession(session: WorkSession) {
        Log.d("HoursTracker", "addSession called: ${session.date} ${session.startTime}-${session.endTime}")
        viewModelScope.launch {
            try {
                database.workSessionDao().insertSession(session)
                _sessions.value = database.workSessionDao().getAllSessions().first()
                // Auto-write this session's PDF into its own folder.
                val jobSites = database.jobSiteDao().getAllJobSites().first()
                val files = exportSessionPdfs(listOf(session), jobSites)
                Log.d("HoursTracker", "Saved OK + exported ${files.size} session file(s)")
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
                _statusMessage.value = "Deleted ✓"
            } catch (e: Throwable) {
                _statusMessage.value = "Delete FAILED: ${e.message}"
            }
        }
    }

    fun addJobSite(name: String, location: String?) {
        viewModelScope.launch {
            try {
                database.jobSiteDao().insertOrReplaceJobSite(0, name, location, "#6750A4")
                _jobSites.value = database.jobSiteDao().getAllJobSites().first()
                _statusMessage.value = "Job site added ✓"
            } catch (e: Throwable) {
                _statusMessage.value = "Add job site FAILED: ${e.message}"
            }
        }
    }

    fun getSessionsInPeriod(start: String, end: String): Flow<List<WorkSession>> {
        return database.workSessionDao().getSessionsInPeriod(start, end)
    }

    fun exportPdf(outputPath: String, startDate: String, endDate: String) {
        viewModelScope.launch {
            try {
                val sessions = database.workSessionDao().getSessionsInPeriod(startDate, endDate).first()
                val jobSites = database.jobSiteDao().getAllJobSites().first()
                val written = exportSessionPdfs(sessions, jobSites)
                _statusMessage.value = "Exported ${written.size} session file(s) ✓"
            } catch (e: Throwable) {
                Log.e("HoursTracker", "exportPdf FAILED", e)
                _statusMessage.value = "Export FAILED: ${e.message}"
            }
        }
    }

    /** Opens the native share sheet for the most recent session in the range. */
    fun sharePdf(outputPath: String, startDate: String, endDate: String) {
        viewModelScope.launch {
            try {
                val sessions = database.workSessionDao().getSessionsInPeriod(startDate, endDate).first()
                val jobSites = database.jobSiteDao().getAllJobSites().first()
                val files = exportSessionPdfs(sessions, jobSites)
                val file = files.lastOrNull()
                if (file != null) {
                    val intent = Intent(Intent.ACTION_SEND)
                        .setDataAndType(Uri.fromFile(file), "application/pdf")
                    appContext.startActivity(intent)
                } else {
                    _statusMessage.value = "No sessions to share"
                }
            } catch (e: Throwable) {
                Log.e("HoursTracker", "sharePdf FAILED", e)
                _statusMessage.value = "Share FAILED: ${e.message}"
            }
        }
    }

    /**
     * Writes one Excel file per session, each into its own folder:
     *   <externalDir>/HoursTracker/<date>/<startTime>-<endTime>/session.xlsx
     * Returns the list of files written (order preserved).
     */
    private fun exportSessionPdfs(
        sessions: List<WorkSession>,
        jobSites: List<JobSite>
    ): List<File> {
        // Write into the public Downloads folder (visible to a file manager),
        // in a subfolder so the app's files are easy to find.
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val written = ArrayList<File>()
        sessions.forEach { s ->
            val bytes = buildSingleSessionXlsBytes(s, jobSites)
            // Build a per-session folder; sanitize so it's a valid path.
            val folderTime = s.startTime.replace(":", "-")
            val safeName = s.date + "_" + folderTime
            val dir = File(downloadsDir, "HoursTracker" + File.separator + safeName)
            dir.mkdirs()
            val out = File(dir, "session.xlsx")
            val fos = FileOutputStream(out)
            fos.write(bytes)
            fos.close()
            written.add(out)
            Log.d("HoursTracker", "wrote ${out.getAbsolutePath()}")
        }
        return written
    }

    private fun buildSingleSessionXlsBytes(
        s: WorkSession,
        jobSites: List<JobSite>
    ): ByteArray {
        val job = jobSites.find { it.id == s.jobSiteId }
        val minutes = durationMinutes(s.startTime, s.endTime) - s.breakMinutes
        // Two-column label/value worksheet.
        val data = ArrayList<Pair<String, String>>()
        data.add("Date" to s.date)
        data.add("Job site" to (job?.name ?: "Unknown"))
        data.add("Start" to s.startTime)
        data.add("End" to s.endTime)
        data.add("Break (min)" to s.breakMinutes.toString())
        data.add("Worked (min)" to minutes.toString())
        if (!s.notes.isNullOrBlank()) data.add("Notes" to s.notes)
        return buildXlsx(data)
    }

    private fun buildXlsx(data: List<Pair<String, String>>): ByteArray {
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
            data.forEachIndexed { i, (label, value) ->
                val r = i + 1
                sheet.append("<row r=\"").append(r).append("\">")
                sheet.append("<c r=\"A").append(r).append("\" t=\"inlineStr\"><is><t>")
                    .append(xmlEscape(label)).append("</t></is></c>")
                sheet.append("<c r=\"B").append(r).append("\" t=\"inlineStr\"><is><t>")
                    .append(xmlEscape(value)).append("</t></is></c>")
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