package com.example.hourstracker

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.database.Cursor
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.provider.MediaStore
import com.example.hourstracker.model.JobSite
import com.example.hourstracker.model.WorkSession
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MainActivity : Activity() {

    private lateinit var db: HoursDb
    private lateinit var prefs: SharedPreferences
    private var isDark = false
    private var themeMode = "system" // "light" | "dark" | "system"

    private var clockRunning = false
    private var clockPaused = false
    private var startedAt = ""
    // Persisted wall-clock state so the clock keeps accruing even if the
    // process is killed. segmentStartMs = epoch of the current running segment
    // (0 when paused/stopped); accumulatedMs = time banked from earlier segments.
    private var segmentStartMs = 0L
    private var accumulatedMs = 0L
    private var statusMessage = ""
    private var drawerTab = 0 // menu selection: 0 = Projects, 1 = Tasks, 2 = Settings
    private var navScreen = 0 // 0 = Home (clock), 1 = Projects, 2 = Tasks, 3 = Settings

    private var jobSites = mutableListOf<JobSite>()
    private var sessions = mutableListOf<WorkSession>()
    private var drawerOpen = false
    private var projectsExpanded = false
    private var weeklyExpanded = false
    private var overtimeExpanded = true
    private var filteredSiteId: Int? = null // when set, Tasks tab shows only this project's tasks
    private var expandedProjectId: Int? = null // when set, its tasks show inline under the project row
    private var expandedTaskId: Int? = null // when set, the task card reveals its start/stop times

    private val mainHandler = Handler(Looper.getMainLooper())
    private var tickerRunning = false

    // view handles for live clock update (avoid rebuilding whole screen every second)
    private var elapsedView: TextView? = null
    private var stateView: TextView? = null
    private var glyphView: TextView? = null
    private var labelView: TextView? = null
    private var haloButtonView: View? = null
    private var haloGlowView: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = HoursDb(this)
        prefs = getSharedPreferences("hours_tracker", Context.MODE_PRIVATE)
        themeMode = prefs.getString("theme", "system") ?: "system"
        isDark = resolveDark()
        // Restore the running clock from persisted wall-clock state so the timer
        // keeps accruing even across process death / relaunch.
        restoreClockState()
        refreshData()
        buildLayout()
        startTicker()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Mirror the current computed elapsed into the bundle for rotation safety.
        outState.putBoolean("clockRunning", clockRunning)
        outState.putBoolean("clockPaused", clockPaused)
        outState.putString("startedAt", startedAt)
        outState.putLong("elapsedMs", elapsedMs())
    }

    override fun onDestroy() {
        super.onDestroy()
        stopTicker()
        db.close()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // In System (auto) mode, follow the device's light/dark switch live.
        if (themeMode == "system") {
            isDark = resolveDark()
            renderAll()
            if (drawerOpen) openDrawer()
        }
    }

    // Back button / back gesture: close the drawer, then leave a submenu to the main menu.
    override fun onBackPressed() {
        when {
            drawerOpen -> closeDrawer()
            navScreen != 0 -> { navScreen = 0; renderAll() }
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    // ==================== THEME ====================

    private val density get() = resources.displayMetrics.density
    private fun dp(v: Int): Int = (v * density).toInt()

    // height of the system status bar so top-anchored UI clears it
    private val statusBarTop: Int by lazy {
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        if (resId > 0) resources.getDimensionPixelSize(resId) else 0
    }

    private val bgColor get() = if (isDark) 0xFF0B0D12.toInt() else 0xFFF4F6FA.toInt()
    private val surfaceColor get() = if (isDark) 0xFF0B0D12.toInt() else 0xFFF4F6FA.toInt()
    private val surfaceVariantColor get() = if (isDark) 0xFF1C212B.toInt() else 0xFFEDF0F6.toInt()
    private val onSurfaceColor get() = if (isDark) 0xFFECEEF2.toInt() else 0xFF14161C.toInt()
    private val onSurfaceVariantColor get() = if (isDark) 0xFF9BA3B2.toInt() else 0xFF5B6272.toInt()
    private val primaryColor get() = if (isDark) 0xFF8FB6FF.toInt() else 0xFF2A5BD7.toInt()
    private val primaryContainerColor get() = if (isDark) 0xFF1B3576.toInt() else 0xFFDCE6FF.toInt()
    private val onPrimaryContainerColor get() = if (isDark) 0xFFDCE6FF.toInt() else 0xFF0A1C4D.toInt()
    private val errorColor get() = if (isDark) 0xFFF2A69E.toInt() else 0xFFC62828.toInt()

    private fun parseHex(hex: String): Int {
        val h = hex.removePrefix("#").trim()
        return if (h.length == 6) {
            try {
                Color.rgb(h.substring(0, 2).toInt(16), h.substring(2, 4).toInt(16), h.substring(4, 6).toInt(16))
            } catch (e: Exception) { 0xFF6750A4.toInt() }
        } else 0xFF6750A4.toInt()
    }

    // Dialog theme so the native date/time pickers match the app UI accent colors.
    private fun pickerDialogThemeId(): Int =
        if (isDark) R.style.PickerDialogThemeDark else R.style.PickerDialogTheme

    private fun rounded(color: Int, radiusDp: Int): GradientDrawable {
        return GradientDrawable().apply {
            setShape(GradientDrawable.RECTANGLE)
            setColor(color)
            setCornerRadius(dp(radiusDp).toFloat())
        }
    }

    // Modern card surface: rounded fill with a hairline outline + soft elevation.
    private fun card(radiusDp: Int): GradientDrawable {
        val fill = if (isDark) 0xFF161A22.toInt() else 0xFFFFFFFF.toInt()
        val stroke = if (isDark) 0xFF262C38.toInt() else 0xFFE7EAF2.toInt()
        return GradientDrawable().apply {
            setShape(GradientDrawable.RECTANGLE)
            setColor(fill)
            setCornerRadius(dp(radiusDp).toFloat())
            setStroke(dp(1), stroke)
        }
    }

    private fun ovalGradient(start: Int, end: Int): GradientDrawable {
        return GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(start, end)).apply {
            setShape(GradientDrawable.OVAL)
        }
    }

    // ==================== DATA ====================

    private fun refreshData() {
        jobSites = querySites()
        sessions = querySessions()
    }

    private fun querySites(): MutableList<JobSite> {
        val out = mutableListOf<JobSite>()
        db.readableDatabase.rawQuery("SELECT * FROM job_sites ORDER BY name", null).use { c ->
            while (c.moveToNext()) {
                val loc = c.getString(c.getColumnIndexOrThrow("location"))
                val wageIdx = c.getColumnIndex("hourly_wage")
                val wage = if (wageIdx >= 0 && !c.isNull(wageIdx)) formatWage(c.getDouble(wageIdx)) else null
                out.add(JobSite(
                    id = c.getInt(c.getColumnIndexOrThrow("id")),
                    name = c.getString(c.getColumnIndexOrThrow("name")),
                    location = loc,
                    hourlyWage = wage,
                    color = c.getString(c.getColumnIndexOrThrow("color"))
                ))
            }
        }
        return out
    }

    private fun querySessions(): MutableList<WorkSession> {
        val out = mutableListOf<WorkSession>()
        db.readableDatabase.rawQuery("SELECT * FROM work_sessions ORDER BY date DESC, start_time DESC", null).use { c ->
            while (c.moveToNext()) {
                out.add(WorkSession(
                    id = c.getInt(c.getColumnIndexOrThrow("id")),
                    jobSiteId = c.getInt(c.getColumnIndexOrThrow("job_site_id")),
                    date = c.getString(c.getColumnIndexOrThrow("date")),
                    startTime = c.getString(c.getColumnIndexOrThrow("start_time")),
                    endTime = c.getString(c.getColumnIndexOrThrow("end_time")),
                    breakMinutes = c.getInt(c.getColumnIndexOrThrow("break_minutes")),
                    notes = c.getString(c.getColumnIndexOrThrow("notes"))
                ))
            }
        }
        return out
    }

    private fun addSite(name: String, location: String = "", wage: String? = null) {
        val cv = android.content.ContentValues()
        cv.put("name", name)
        cv.put("location", if (location.isBlank()) null else location)
        cv.put("hourly_wage", parseWage(wage))
        cv.put("color", "#6750A4")
        db.writableDatabase.insert("job_sites", null, cv)
        refreshData(); renderAll()
    }

    private fun renameSite(id: Int, name: String, location: String, wage: String? = null) {
        android.content.ContentValues().apply {
            put("name", name)
            put("location", if (location.isBlank()) null else location)
            put("hourly_wage", parseWage(wage))
            db.writableDatabase.update("job_sites", this, "id=?", arrayOf(id.toString()))
        }
        refreshData(); renderAll()
    }

    // Parse a wage string to a Double for the DB; null if blank/invalid.
    private fun parseWage(wage: String?): Double? {
        val w = wage?.trim() ?: return null
        if (w.isEmpty()) return null
        return w.toDoubleOrNull()
    }

    // Format a stored wage back to a display string, trimming trailing .0.
    private fun formatWage(value: Double): String {
        if (value == value.toLong().toDouble() && Math.abs(value) < 1e12) return value.toLong().toString()
        val s = String.format(Locale.US, "%.2f", value)
        return s.trimEnd('0').trimEnd('.')
    }

    // ===== Weekly overtime configuration (global, job-agnostic) =====
    // overtimeThreshold = hours/week before overtime applies (default 40)
    // overtimeRate = multiplier applied to hours beyond the threshold (default 1.5)
    private val overtimeThresholdHours: Double
        get() = prefs.getFloat("ot_threshold_hours", 40f).toDouble()
    private val overtimeRateVal: Double
        get() = prefs.getFloat("ot_rate", 1.5f).toDouble()

    // Compute weekly overtime summary across all jobs for the given week.
    // Returns (totalMin, otMin, basePay, otPay).
    private fun weeklySummary(weekSunday: String): Quad {
        val weekSessions = sessions.filter { sundayOf(it.date) == weekSunday }
        val totalMin = weekSessions.sumOf { workedMinutes(it) }
        val thresholdSec = (overtimeThresholdHours * 60).toInt()
        val regMin = minOf(totalMin, thresholdSec)
        val otMin = (totalMin - regMin).coerceAtLeast(0)
        var basePay = 0.0
        var otPay = 0.0
        // Each job bills its own wage; OT minutes get the multiplier on top.
        jobSites.forEach { site ->
            val siteMin = weekSessions.filter { it.jobSiteId == site.id }.sumOf { workedMinutes(it) }
            if (siteMin > 0) {
                val wage = site.hourlyWage?.trim()?.toDoubleOrNull() ?: return@forEach
                val siteReg = minOf(siteMin, regMin)
                val siteOt = siteMin - siteReg
                basePay += siteReg / 60.0 * wage
                otPay += siteOt / 60.0 * wage * overtimeRateVal
            }
        }
        return Quad(totalMin, otMin, basePay, otPay)
    }

    // Simple 4-value holder (keeps weeklySummary readable).
    private data class Quad(val totalMin: Int, val otMin: Int, val basePay: Double, val otPay: Double)

    private fun deleteSite(id: Int) {
        // Remove this project's exported workbook along with the project.
        jobSites.find { it.id == id }?.let { site ->
            val safeName = site.name.replace("/", "-").replace("\\\\", "-").trim() + ".xlsx"
            deleteDownload("Tasks", safeName)
        }
        db.writableDatabase.delete("job_sites", "id=?", arrayOf(id.toString()))
        refreshData(); renderAll()
    }

    private fun insertSession(session: WorkSession) {
        val cv = android.content.ContentValues()
        cv.put("job_site_id", session.jobSiteId)
        cv.put("date", session.date)
        cv.put("start_time", session.startTime)
        cv.put("end_time", session.endTime)
        cv.put("break_minutes", session.breakMinutes)
        cv.put("notes", session.notes)
        db.writableDatabase.insert("work_sessions", null, cv)
        refreshData()
        // Write only this task's project workbook as soon as the task is saved.
        val exportErr = try { exportSite(session.jobSiteId); null } catch (e: Exception) { e.message }
        statusMessage = if (exportErr == null)
            "Saved ✓ ${isoDateDisplay(session.date)} ${time12(session.startTime)}-${time12(session.endTime)}"
        else "Saved ✓ (export failed: $exportErr)"
        renderAll()
    }

    private fun updateSession(session: WorkSession) {
        android.content.ContentValues().apply {
            put("job_site_id", session.jobSiteId)
            put("date", session.date)
            put("start_time", session.startTime)
            put("end_time", session.endTime)
            put("break_minutes", session.breakMinutes)
            db.writableDatabase.update("work_sessions", this, "id=?", arrayOf(session.id.toString()))
        }
        refreshData()
        // Keep only this task's project workbook in sync after an edit.
        val exportErr = try { exportSite(session.jobSiteId); null } catch (e: Exception) { e.message }
        statusMessage = if (exportErr == null)
            "Updated ✓ ${isoDateDisplay(session.date)}"
        else "Updated ✓ (export failed: $exportErr)"
        renderAll()
    }

    private fun deleteSession(session: WorkSession) {
        db.writableDatabase.delete("work_sessions", "id=?", arrayOf(session.id.toString()))
        refreshData()
        // Refresh (or remove) only this task's project workbook.
        val exportErr = try { exportSite(session.jobSiteId); null } catch (e: Exception) { e.message }
        statusMessage = if (exportErr == null) "Deleted ✓" else "Deleted ✓ (export failed: $exportErr)"
        renderAll()
    }

    // ==================== TIMER ====================
    //
    // The clock is tracked against the WALL CLOCK, not an in-memory counter:
    //   elapsed = accumulatedMs + (now - segmentStartMs)   [while running]
    //   elapsed = accumulatedMs                             [while paused/stopped]
    // accumulatedMs and segmentStartMs are persisted, so even if the OS kills the
    // process, on relaunch the elapsed time is recomputed from real time and the
    // clock keeps accruing seamlessly.

private fun persistClock() {
    prefs.edit()
        .putBoolean("clockRunning", clockRunning)
        .putBoolean("clockPaused", clockPaused)
        .putString("startedAt", startedAt)
        .putLong("segmentStartMs", segmentStartMs)
        .putLong("accumulatedMs", accumulatedMs)
        .apply()
}

private fun restoreClockState() {
    clockRunning = prefs.getBoolean("clockRunning", false)
    clockPaused = prefs.getBoolean("clockPaused", false)
    startedAt = prefs.getString("startedAt", "") ?: ""
    segmentStartMs = prefs.getLong("segmentStartMs", 0L)
    accumulatedMs = prefs.getLong("accumulatedMs", 0L)
}

private fun elapsedMs(): Long {
    if (!clockRunning) return 0L
    if (clockPaused || segmentStartMs == 0L) return accumulatedMs
    return accumulatedMs + (System.currentTimeMillis() - segmentStartMs)
}

private fun startTicker() {
    if (tickerRunning) return
    tickerRunning = true
    mainHandler.post(object : Runnable {
        override fun run() {
            if (clockRunning) updateClockViews()
            mainHandler.postDelayed(this, 1000L)
        }
    })
}

private fun stopTicker() { tickerRunning = false; mainHandler.removeCallbacksAndMessages(null) }

private fun onHaloTap() {
    when {
        !clockRunning -> {
            startedAt = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
            clockRunning = true; clockPaused = false
            accumulatedMs = 0L
            segmentStartMs = System.currentTimeMillis()
        }
        clockPaused -> {
            // resume
            clockPaused = false
            segmentStartMs = System.currentTimeMillis()
        }
        else -> {
            // pause
            accumulatedMs = elapsedMs()
            segmentStartMs = 0L
            clockPaused = true
        }
    }
    persistClock()
    renderAll()
}

private fun stopClock(jobSiteId: Int) {
    clockRunning = false; clockPaused = false
    val start = startedAt
    val end = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
    val today = LocalDate.now().toString()
    insertSession(WorkSession(id = 0, jobSiteId = jobSiteId, date = today, startTime = start, endTime = end, breakMinutes = 0, notes = "clock"))
    startedAt = ""; segmentStartMs = 0L; accumulatedMs = 0L
    persistClock()
    renderAll()
}

    // ==================== LAYOUT ====================

    private fun renderAll() { buildLayout() }

    private fun buildLayout() {
        val topPad = statusBarTop + dp(18)
        val root = FrameLayout(this).apply { setBackgroundColor(bgColor); setPadding(0, topPad, 0, 0) }

        // Edge-to-edge: the status bar is transparent and shows our background,
        // so the host's white icons (battery/wifi/cellular) vanish on the light
        // background. Paint a dark band across the very top strip so they always
        // contrast. Dark mode already uses a dark background, so the band blends
        // in seamlessly there too. (No platform status-bar API needed.)
        val topBand = View(this).apply { setBackgroundColor(0xFF0B0D12.toInt()) }
        val bandLp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, topPad, Gravity.TOP)
        bandLp.topMargin = -topPad // pull up to the very top, behind the content
        root.addView(topBand, bandLp)

        val scroll = ScrollView(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(24))
        }
        scroll.addView(column)
        root.addView(scroll)

        // ---- ☰ button (top-right), available on every screen ----
        val menuBtn = TextView(this).apply {
            id = 3
            text = "☰"; textSize = 30f; setTypeface(null, Typeface.BOLD)
            setTextColor(onSurfaceColor); gravity = Gravity.CENTER
            background = rounded(surfaceVariantColor, 28)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setOnClickListener { openDrawer() }
        }
        root.addView(menuBtn, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.TOP or Gravity.END))

        if (navScreen == 0) {
            // ==================== HOME SCREEN (clock) ====================
            buildHomeScreen(column)
        } else {
            // ==================== SECTION FULL SCREEN ====================
            buildSectionScreen(column)
        }

        // ---- drawer scrim + panel (menu only) ----
        drawerScrim = View(this).apply {
            setBackgroundColor(0x66000000); visibility = View.GONE
            setOnClickListener { closeDrawer() }
        }
        root.addView(drawerScrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val panelView = buildDrawer()
        drawerPanel = panelView
        root.addView(panelView, FrameLayout.LayoutParams(dp(280), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END))
        panelView.visibility = View.GONE

        setContentView(root)
        if (drawerOpen) openDrawer()
    }

    // Home screen: clock halo + stop + add task + status.
    private fun buildHomeScreen(column: LinearLayout) {
        // ---- clock section ----
        val stateLbl = TextView(this).apply {
            id = 1; textSize = 16f; gravity = Gravity.CENTER
            setTextColor(onSurfaceColor)
            setTypeface(null, Typeface.NORMAL)
        }
        stateView = stateLbl
        val lpCenter = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        column.addView(stateLbl, lpCenter)

        val wrap = FrameLayout(this)
        wrap.layoutParams = LinearLayout.LayoutParams(dp(224), dp(224)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(48) }

        val haloBack = GradientDrawable().apply { setShape(GradientDrawable.OVAL); setColor(0x24FF6D00) }
        val halo = View(this).apply { background = haloBack }
        haloGlowView = halo
        wrap.addView(halo, FrameLayout.LayoutParams(dp(224), dp(224), Gravity.CENTER))

        val inner = FrameLayout(this).apply {
            background = ovalGradient(0xFF4CAF50.toInt(), 0xFF2E7D32.toInt())
            isClickable = true
            setOnClickListener { onHaloTap() }
        }
        haloButtonView = inner
        val innerCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        val glyph = TextView(this).apply { textSize = 50f; setTextColor(Color.WHITE); setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER }
        val label = TextView(this).apply { textSize = 20f; setTextColor(Color.WHITE); setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER }
        glyphView = glyph; labelView = label
        innerCol.addView(glyph); innerCol.addView(label)
        inner.addView(innerCol, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        wrap.addView(inner, FrameLayout.LayoutParams(dp(200), dp(200), Gravity.CENTER))
        column.addView(wrap)

        // elapsed (while running)
        if (clockRunning) {
            column.addView(TextView(this).apply {
                id = 2; textSize = 40f; setTypeface(null, Typeface.BOLD)
                setTextColor(if (clockPaused) onSurfaceVariantColor else primaryColor)
                gravity = Gravity.CENTER
            }.also { elapsedView = it })
        }

        // stop button (while running)
        if (clockRunning) {
            val stopBtn = TextView(this).apply {
                text = "■ Stop"; textSize = 20f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
                setTypeface(null, Typeface.BOLD)
                background = rounded(0xFFFF4038.toInt(), 10)
                setOnClickListener {
                    clockPaused = true
                    showStopPicker()
                }
            }
            column.addView(stopBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(60)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        }

        // Add Task button, always visible under the clock/stop controls
        column.addView(drawerButton("＋ Add Task") { showAddSession() },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(20) })

        updateClockViews()

        // ---- status ----
        if (statusMessage.isNotEmpty()) {
            val st = TextView(this).apply { text = statusMessage; textSize = 12f; setTextColor(onSurfaceVariantColor) }
            column.addView(st, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        column.addView(View(this).apply { }, LinearLayout.LayoutParams(1, dp(8)))
    }

    private var drawerScrim: View? = null
    private var drawerPanel: View? = null

    private fun updateClockViews() {
        stateView?.visibility = if (!clockRunning) View.GONE else View.VISIBLE
        // button colors: green idle/stopped, yellow/gold while running (orange when paused)
        val (b1, b2) = when {
            !clockRunning -> 0xFF4CAF50.toInt() to 0xFF2E7D32.toInt()   // green
            clockPaused -> 0xFFFF6D00.toInt() to 0xFFEF4D00.toInt()     // orange (paused)
            else -> 0xFFFFC107.toInt() to 0xFFFF9800.toInt()            // yellow/gold
        }
        haloButtonView?.background = ovalGradient(b1, b2)
        haloGlowView?.background = GradientDrawable().apply { setShape(GradientDrawable.OVAL)
            setColor(if (!clockRunning) 0x242E7D32 else 0x24FF8F00) }
        stateView?.text = when {
            !clockRunning -> ""
            clockPaused -> "Paused since $startedAt"
            else -> "Working since $startedAt"
        }
        glyphView?.text = if (!clockRunning || clockPaused) "▶" else "⏸"
        labelView?.text = if (!clockRunning) "Start" else if (clockPaused) "Resume" else "Pause"
        elapsedView?.let { tv ->
            tv.text = formatElapsedMs(elapsedMs())
            tv.setTextColor(if (clockPaused) onSurfaceVariantColor else primaryColor)
        }
    }

    private fun dotView(color: Int, radius: Int): View = View(this).apply {
        background = rounded(color, radius)
    }.also { it.layoutParams = LinearLayout.LayoutParams(dp(12), dp(12)) }

    private fun pill(textVal: String, container: Int, content: Int): TextView = TextView(this).apply {
        text = textVal; textSize = 12f; setTextColor(content)
        background = rounded(container, 50)
        setPadding(dp(12), dp(5), dp(12), dp(5))
    }

    private fun sessionCard(session: WorkSession): View {
        val site = jobSites.find { it.id == session.jobSiteId }
        val projectColor = parseHex(site?.color ?: "#6750A4")
        val expanded = expandedTaskId == session.id
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), if (expanded) dp(10) else dp(6))
            background = card(20)
            setMargins(0, dp(8), 0, 0)
            // Tap: expand/collapse start/stop times.
            setOnClickListener {
                expandedTaskId = if (expandedTaskId == session.id) null else session.id
                renderAll()
            }
            // Long-press: edit or delete.
            setOnLongClickListener {
                val actions = arrayOf("Edit", "Delete", "Cancel")
                AlertDialog.Builder(this@MainActivity, pickerDialogThemeId())
                    .setTitle("${isoDateDisplay(session.date)}")
                    .setItems(actions) { _, w ->
                        when (actions[w]) {
                            "Edit" -> showEditSession(session)
                            "Delete" -> AlertDialog.Builder(this@MainActivity, pickerDialogThemeId())
                                .setTitle("Delete task?")
                                .setMessage("${isoDateDisplay(session.date)} ${time12(session.startTime)}-${time12(session.endTime)}")
                                .setPositiveButton("Delete") { _, _ -> deleteSession(session) }
                                .setNegativeButton("Cancel", null)
                                .show()
                            else -> {}
                        }
                    }
                    .show()
                true
            }
        }

        // row 1: date/project + total time + earnings
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        info.addView(TextView(this).apply { text = isoDateDisplay(session.date); textSize = 14f; setTypeface(null, Typeface.BOLD); setTextColor(onSurfaceColor) })
        info.addView(TextView(this).apply { text = site?.name ?: "Unknown project"; textSize = 12f; setTextColor(onSurfaceVariantColor) })
        row1.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row1.addView(pill(formatMinutesShort(workedMinutes(session)), tint(projectColor), onSurfaceColor))
        // earnings for this task = wage * worked hours (if the project has a wage)
        val wageVal = site?.hourlyWage?.trim()?.toDoubleOrNull()
        if (wageVal != null) {
            val earned = wageVal * workedMinutes(session) / 60.0
            row1.addView(pill("\$${String.format(Locale.US, "%.2f", earned)}", primaryContainerColor, onPrimaryContainerColor).also {
                (it.layoutParams as? LinearLayout.LayoutParams)?.setMargins(dp(6), 0, 0, 0)
            })
        }
        card.addView(row1)

        // When expanded: show the start/stop times (and break) on separate lines.
        if (expanded) {
            fun detailRow(label: String, value: String): View {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(6), 0, 0)
                }
                row.addView(TextView(this).apply { text = label; textSize = 12f; setTextColor(onSurfaceVariantColor) },
                    LinearLayout.LayoutParams(dp(70), ViewGroup.LayoutParams.WRAP_CONTENT))
                row.addView(TextView(this).apply { text = value; textSize = 14f; setTextColor(onSurfaceColor) })
                return row
            }
            card.addView(detailRow("Start", time12(session.startTime)))
            card.addView(detailRow("Stop", time12(session.endTime)))
            if (session.breakMinutes > 0) card.addView(detailRow("Break", "${session.breakMinutes} min"))
        }
        return card
    }

    // alpha-blend the project color over the card surface for the worked-hours badge
    private fun tint(projectColor: Int): Int {
        val a = 0x29
        val r = (projectColor shr 16 and 0xFF) * a / 255
        val g = (projectColor shr 8 and 0xFF) * a / 255
        val b = (projectColor and 0xFF) * a / 255
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun Int.orColor(fallback: Int): Int {
        return fallback
    }

    private fun View.setMargins(l: Int, t: Int, r: Int, b: Int) {
        (layoutParams as? ViewGroup.MarginLayoutParams)?.setMargins(dp(l), dp(t), dp(r), dp(b))
    }

    // ==================== DRAWER ====================

    private fun openDrawer() { drawerScrim?.visibility = View.VISIBLE; drawerPanel?.visibility = View.VISIBLE; drawerOpen = true }
    private fun closeDrawer() { drawerScrim?.visibility = View.GONE; drawerPanel?.visibility = View.GONE; drawerOpen = false }

    // Inline list of a single project's tasks, shown under its row in the drawer.
    private fun buildProjectTasksInline(siteId: Int): View {
        val site = jobSites.find { it.id == siteId }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val siteSessions = sessions.filter { it.jobSiteId == siteId }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = rounded(surfaceVariantColor, 14)
        }
        val totalMinutes = siteSessions.sumOf { workedMinutes(it) }
        val hi = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        hi.addView(TextView(this).apply { text = site?.name ?: "Project"; textSize = 14f; setTypeface(null, Typeface.BOLD); setTextColor(onSurfaceColor) })
        hi.addView(TextView(this).apply { text = "${siteSessions.size} tasks"; textSize = 12f; setTextColor(onSurfaceVariantColor) })
        header.addView(hi, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(pill("${formatMinutesShort(totalMinutes)}", primaryContainerColor, onPrimaryContainerColor))
        col.addView(header)
        if (siteSessions.isEmpty()) {
            col.addView(TextView(this).apply {
                text = "No tasks for this project yet"; textSize = 13f; setTextColor(onSurfaceVariantColor)
                setPadding(0, dp(8), 0, dp(8))
            })
        } else {
            siteSessions.forEach { s -> col.addView(sessionCard(s)) }
        }
        return col
    }

    // Weekly summary card: combined hours + pay across all jobs, with job-agnostic OT.
    // Collapsible — tap the header to expand/collapse the detail rows.
    private fun buildWeeklySummaryCard(): View {
        val weekSunday = sundayOf(LocalDate.now().toString())
        val s = weeklySummary(weekSunday)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(6), dp(14), dp(14))
            background = rounded(primaryContainerColor, 16)
            setMargins(0, 0, 0, dp(8))
        }
        val hasOt = s.otMin > 0
        val totalPay = s.basePay + s.otPay
        // Tappable header row (no chevron/arrow).
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setMinimumHeight(dp(40))
            setOnClickListener { weeklyExpanded = !weeklyExpanded; renderAll(); if (drawerOpen) openDrawer() }
            addView(TextView(this@MainActivity).apply {
                text = "This week"; textSize = 14f; setTypeface(null, Typeface.BOLD); setTextColor(onPrimaryContainerColor)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        })
        if (weeklyExpanded) {
            fun row(label: String, value: String) {
                col.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(6), 0, 0)
                }.also { r ->
                    r.addView(TextView(this).apply { text = label; textSize = 13f; setTextColor(onPrimaryContainerColor) },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    r.addView(TextView(this).apply { text = value; textSize = 13f; setTypeface(null, Typeface.BOLD); setTextColor(onPrimaryContainerColor) })
                })
            }
            row("Hours", formatMinutesShort(s.totalMin))
            if (hasOt) row("Overtime", "${formatMinutesShort(s.otMin)} @${formatWage(overtimeRateVal)}x")
            row(if (hasOt) "Overtime pay" else "Total pay", "\$${String.format(Locale.US, "%.2f", if (hasOt) s.otPay else totalPay)}")
            if (hasOt) row("Total pay", "\$${String.format(Locale.US, "%.2f", totalPay)}")
        }
        return col
    }

    // Tasks tab content: optional project filter header + total header + session cards.
    private fun buildTasksTab(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // If a project filter is active, show a back header naming the project.
        val filterId = filteredSiteId
        val filteredSessions = if (filterId != null) sessions.filter { it.jobSiteId == filterId } else sessions
        val filterSite = filterId?.let { id -> jobSites.find { it.id == id } }
        if (filterSite != null) {
            col.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = rounded(surfaceVariantColor, 14)
                setOnClickListener { filteredSiteId = null; renderAll(); if (drawerOpen) openDrawer() }
            }.also { back ->
                back.addView(TextView(this).apply {
                    text = "← All Tasks"; textSize = 14f; setTypeface(null, Typeface.BOLD)
                    setTextColor(primaryColor); setPadding(0, dp(2), dp(10), dp(2))
                })
                back.addView(dotView(parseHex(filterSite.color), 12))
                back.addView(TextView(this).apply {
                    text = " ${filterSite.name}"; textSize = 16f; setTypeface(null, Typeface.BOLD); setTextColor(onSurfaceColor)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            })
        }

        if (filteredSessions.isEmpty()) {
            col.addView(TextView(this).apply {
                text = if (filterSite != null) "No tasks for this project yet" else "No tasks recorded yet"
                textSize = 14f; setTextColor(onSurfaceVariantColor)
                gravity = Gravity.CENTER; setPadding(0, dp(20), 0, dp(20))
            })
        } else {
            val totalMinutes = filteredSessions.sumOf { workedMinutes(it) }
            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = rounded(surfaceVariantColor, 14)
            }
            header.addView(TextView(this).apply {
                text = "Total time"; textSize = 14f; setTypeface(null, Typeface.BOLD); setTextColor(onSurfaceColor)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            header.addView(pill(formatMinutesShort(totalMinutes), primaryContainerColor, onPrimaryContainerColor))
            col.addView(header)

            filteredSessions.forEach { s -> col.addView(sessionCard(s)) }
        }
        return col
    }

    private fun buildSettingsTab(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply { text = "Settings"; textSize = 22f; setTypeface(null, Typeface.BOLD); setTextColor(onSurfaceColor) })
        col.addView(TextView(this).apply { text = "App preferences"; textSize = 13f; setTextColor(onSurfaceVariantColor); setPadding(0, dp(2), 0, dp(12)) })

        // Overtime submenu (expandable).
        col.addView(drawerButton("Overtime") {
            overtimeExpanded = !overtimeExpanded; renderAll(); if (drawerOpen) openDrawer()
        })
        if (overtimeExpanded) {
            val th = EditText(this).apply { setText(formatWage(overtimeThresholdHours)); setTextColor(onSurfaceColor); inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL }
            val rt = EditText(this).apply { setText(formatWage(overtimeRateVal)); setTextColor(onSurfaceColor); inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL }
            // Values apply immediately as the user edits (no Save button).
            th.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    prefs.edit().putFloat("ot_threshold_hours", th.text.toString().trim().toDoubleOrNull()?.toFloat() ?: 40f).apply()
                }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
            rt.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    prefs.edit().putFloat("ot_rate", rt.text.toString().trim().toDoubleOrNull()?.toFloat() ?: 1.5f).apply()
                }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
            col.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(4), 0, 0)
            }.also { box ->
                box.addView(TextView(this).apply { text = "Overtime starts after (hrs/week)"; textSize = 12f; setTextColor(onSurfaceVariantColor); setPadding(0, dp(2), 0, dp(2)) })
                box.addView(th)
                box.addView(View(this).apply {}, LinearLayout.LayoutParams(1, dp(10)))
                box.addView(TextView(this).apply { text = "Overtime rate (e.g. 1.5)"; textSize = 12f; setTextColor(onSurfaceVariantColor); setPadding(0, dp(2), 0, dp(2)) })
                box.addView(rt)
            })
        }

        // Keep theme toggle available here too (was previously only under Projects).
        col.addView(drawerButton(themeLabel()) { toggleDark() })
        return col
    }

    private fun buildDrawer(): View {
        val frame = FrameLayout(this).apply { setBackgroundColor(if (isDark) 0xFF12161E.toInt() else 0xFFFFFFFF.toInt()) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(24), dp(20), dp(24)) }
        frame.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // ---- flat hamburger menu: Projects / Tasks / Settings ----
        // Selecting a menu closes the drawer and opens that section full screen.
        fun menuButton(idx: Int, name: String) {
            col.addView(TextView(this).apply {
                text = name; textSize = 20f; setTypeface(null, Typeface.BOLD)
                setTextColor(onSurfaceColor)
                gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(16), dp(14), dp(16))
                setOnClickListener {
                    drawerTab = idx; navScreen = idx + 1
                    filteredSiteId = null
                    closeDrawer(); renderAll()
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        menuButton(0, "Projects")
        menuButton(1, "Tasks")
        menuButton(2, "Settings")

        // Export lives in the main menu (opens its dialog directly).
        col.addView(TextView(this).apply {
            text = "Export"; textSize = 20f; setTypeface(null, Typeface.BOLD)
            setTextColor(onSurfaceColor)
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(16), dp(14), dp(16))
            setOnClickListener { closeDrawer(); showExportRange() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Spacer pushes the credit line to the bottom of the (full-height) menu.
        col.addView(View(this).apply {}, LinearLayout.LayoutParams(1, 0).apply { weight = 1f })

        val footer = TextView(this).apply {
            text = "vibe coded by pooh"; textSize = 11f; setTextColor(onSurfaceVariantColor)
            setPadding(0, dp(8), 0, 0)
        }
        col.addView(footer)
        return frame
    }

    // Full-screen section page: back header + the selected section's content.
    private fun buildSectionScreen(column: LinearLayout) {
        // Back + title header.
        column.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(16))
            setOnClickListener { navScreen = 0; renderAll() }
        }.also { head ->
            head.addView(TextView(this).apply {
                text = "Home"; textSize = 16f; setTypeface(null, Typeface.BOLD); setTextColor(primaryColor)
            })
        })
        when (drawerTab) {
            0 -> column.addView(buildProjectsTab())
            1 -> column.addView(buildTasksTab())
            2 -> column.addView(buildSettingsTab())
        }
    }

    // Projects tab content: weekly summary + expandable project list + export entry.
    private fun buildProjectsTab(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // This week summary (overtime is job-agnostic and shown per week).
        col.addView(buildWeeklySummaryCard())

        // Tapping Projects expands the project list inline; Add Project always sits at its top.
        col.addView(drawerButton("Projects") {
            projectsExpanded = !projectsExpanded; renderAll(); if (drawerOpen) openDrawer()
        })
        if (projectsExpanded) {
            col.addView(drawerButton("＋ Add Project") { closeDrawer(); showAddProject() })
            if (jobSites.isEmpty()) {
                col.addView(TextView(this).apply { text = "No projects yet. Add one."; textSize = 14f; setTextColor(onSurfaceVariantColor); setPadding(0, dp(6), 0, dp(6)) })
            }
            jobSites.sortedBy { it.name }.forEach { site ->
                col.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    background = card(14)
                    isClickable = true
                    // Tap: expand/collapse this project's tasks inline. Long-press: project actions.
                    setOnClickListener {
                        expandedProjectId = if (expandedProjectId == site.id) null else site.id
                        renderAll(); if (drawerOpen) openDrawer()
                    }
                    setOnLongClickListener {
                        val actions = arrayOf("Edit", "Delete", "Cancel")
                        AlertDialog.Builder(this@MainActivity, pickerDialogThemeId())
                            .setTitle(site.name)
                            .setItems(actions) { _, w ->
                                when (actions[w]) {
                                    "Edit" -> showEditProject(site)
                                    "Delete" -> AlertDialog.Builder(this@MainActivity, pickerDialogThemeId())
                                        .setTitle("Delete ${site.name}?")
                                        .setMessage("Tasks will stay; the project is removed.")
                                        .setPositiveButton("Delete") { _, _ -> deleteSite(site.id) }
                                        .setNegativeButton("Cancel", null)
                                        .show()
                                    else -> {}
                                }
                            }
                            .show()
                        true
                    }
                }.also { row ->
                    val siteSessions = sessions.filter { it.jobSiteId == site.id }
                    val totalMin = siteSessions.sumOf { workedMinutes(it) }
                    val wageVal = site.hourlyWage?.trim()?.toDoubleOrNull()
                    val earnings = if (wageVal != null) wageVal * totalMin / 60.0 else null
                    val ci = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                    ci.addView(TextView(this).apply { text = site.name; textSize = 14f; setTypeface(null, Typeface.BOLD); setTextColor(onSurfaceColor) })
                    val subParts = mutableListOf<String>()
                    if (!site.location.isNullOrBlank()) subParts.add(site.location)
                    if (!site.hourlyWage.isNullOrBlank()) subParts.add("\$${site.hourlyWage}/hr")
                    if (subParts.isNotEmpty()) ci.addView(TextView(this).apply { text = subParts.joinToString(" · "); textSize = 12f; setTextColor(onSurfaceVariantColor) })
                    val statParts = mutableListOf<String>()
                    statParts.add("${formatMinutesShort(totalMin)}")
                    if (earnings != null) statParts.add("\$${String.format(Locale.US, "%.2f", earnings)} earned")
                    ci.addView(TextView(this).apply { text = statParts.joinToString(" · "); textSize = 12f; setTypeface(null, Typeface.BOLD); setTextColor(primaryColor) })
                    row.addView(ci, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                })
                if (expandedProjectId == site.id) {
                    col.addView(buildProjectTasksInline(site.id))
                }
            }
        }
        return col
    }

    private fun drawerButton(textVal: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = textVal; textSize = 15f; setTypeface(null, Typeface.BOLD)
        setTextColor(primaryColor); gravity = Gravity.CENTER
        background = card(14)
        setPadding(0, dp(14), 0, dp(14))
        setMargins(0, dp(12), 0, 0)
        setOnClickListener { onClick() }
    }

    // Current effective dark state from the selected mode (System follows the device).
    private fun resolveDark(): Boolean = when (themeMode) {
        "dark" -> true
        "light" -> false
        else -> isSystemDark()
    }

    private fun isSystemDark(): Boolean {
        val mode = resources.configuration.uiMode
        val mask = android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return (mode and mask) == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    private fun themeLabel(): String = when (themeMode) {
        "dark" -> "Dark"
        "light" -> "Light"
        else -> "System (auto)"
    }

    private fun toggleDark() {
        val labels = arrayOf("Light", "Dark", "System (auto)")
        val modes = arrayOf("light", "dark", "system")
        var selected = when (themeMode) { "dark" -> 1; "light" -> 0; else -> 2 }
        AlertDialog.Builder(this@MainActivity, pickerDialogThemeId())
            .setTitle("Theme")
            .setSingleChoiceItems(labels, selected) { _, w -> selected = w }
            .setPositiveButton("OK") { _, _ ->
                themeMode = modes[selected]
                prefs.edit().putString("theme", themeMode).apply()
                isDark = resolveDark()
                renderAll()
                if (drawerOpen) openDrawer()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ==================== DIALOGS ====================

    private fun showAddProject() {
        val name = EditText(this).apply {
            hint = "Project name"; textSize = 18f
            setTextColor(onSurfaceColor); setHintTextColor(onSurfaceVariantColor)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val wage = EditText(this).apply {
            hint = "Hourly wage ($)"; textSize = 18f
            setTextColor(onSurfaceColor); setHintTextColor(onSurfaceVariantColor)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(28), dp(16), dp(28), dp(8))
        }
        name.layoutParams = LinearLayout.LayoutParams(dp(320), dp(56))
        wage.layoutParams = LinearLayout.LayoutParams(dp(320), dp(56))
        wage.setMargins(0, dp(10), 0, 0)
        wrap.addView(name)
        wrap.addView(wage)
        AlertDialog.Builder(this, pickerDialogThemeId())
            .setTitle("Add Project")
            .setView(wrap)
            .setPositiveButton("Add") { _, _ ->
                if (name.text.toString().isNotBlank()) addSite(name.text.toString().trim(), "", wage.text.toString().trim())
                else statusMessage = "Project name can't be empty"
            }
            .setNegativeButton("Cancel", null)
            .show()
        name.requestFocus()
    }

    // Edit an existing project: name, location, and hourly wage.
    private fun showEditProject(site: JobSite) {
        val name = EditText(this).apply { setText(site.name); setTextColor(onSurfaceColor); inputType = InputType.TYPE_CLASS_TEXT }
        val loc = EditText(this).apply { setText(site.location ?: ""); setTextColor(onSurfaceColor); inputType = InputType.TYPE_CLASS_TEXT }
        val wage = EditText(this).apply { setText(site.hourlyWage ?: ""); setTextColor(onSurfaceColor); inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL }
        val nameLbl = TextView(this).apply { text = "Name"; textSize = 12f; setTextColor(onSurfaceVariantColor) }
        val locLbl = TextView(this).apply { text = "Location"; textSize = 12f; setTextColor(onSurfaceVariantColor) }
        val wageLbl = TextView(this).apply { text = "Hourly wage ($)"; textSize = 12f; setTextColor(onSurfaceVariantColor) }
        AlertDialog.Builder(this, pickerDialogThemeId())
            .setTitle("Edit Project")
            .setView(fieldColumn(nameLbl, name, locLbl, loc, wageLbl, wage))
            .setPositiveButton("Save") { _, _ ->
                if (name.text.toString().isNotBlank()) renameSite(site.id, name.text.toString().trim(), loc.text.toString(), wage.text.toString().trim())
                else statusMessage = "Project name can't be empty"
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showStopPicker() {
        val names = jobSites.map { it.name }.toTypedArray()
        AlertDialog.Builder(this, pickerDialogThemeId())
            .setTitle("Which job were you working on?")
            .setItems(if (names.isEmpty()) arrayOf("No projects yet") else names) { _, which ->
                if (names.isNotEmpty() && which in jobSites.indices) stopClock(jobSites[which].id)
            }
            .setNegativeButton(if (names.isEmpty()) "Close" else "Cancel") { _, _ -> if (clockPaused) clockPaused = false; renderAll() }
            .show()
    }

    private fun showAddSession() {
        val now = LocalDateTime.now()
        val today = now.toLocalDate().toString()
        showSessionDialog(
            title = "Add Task",
            initialDate = isoDateDisplay(today),
            initialStart = "",
            initialEnd = "",
            initialBreak = "0",
            initialSiteId = jobSites.firstOrNull()?.id ?: 1,
            confirmLabel = "Add"
        ) { d, s, e, b, siteId ->
            insertSession(WorkSession(id = 0, jobSiteId = siteId, date = parseDateToIso(d),
                startTime = s, endTime = e, breakMinutes = b, notes = null))
        }
    }

    private fun showEditSession(session: WorkSession) {
        showSessionDialog(
            title = "Edit Task",
            initialDate = isoDateDisplay(session.date),
            initialStart = session.startTime,
            initialEnd = session.endTime,
            initialBreak = session.breakMinutes.toString(),
            initialSiteId = session.jobSiteId,
            confirmLabel = "Save"
        ) { d, s, e, b, siteId ->
            updateSession(WorkSession(id = session.id, jobSiteId = siteId, date = parseDateToIso(d),
                startTime = s, endTime = e, breakMinutes = b, notes = session.notes))
        }
    }

    /** Shared session add/edit form: date (calendar picker), start, end, break, project picker. */
    private fun showSessionDialog(title: String, initialDate: String, initialStart: String,
                                  initialEnd: String, initialBreak: String, initialSiteId: Int,
                                  confirmLabel: String, onSave: (String, String, String, Int, Int) -> Unit) {
        // initial date parts (MM/dd/yyyy display) for the calendar picker
        val initP = Regex("""(\d{1,2})/(\d{1,2})/(\d{4})""").matchEntire(initialDate.trim())
        var dateYear = initP?.groupValues?.get(3)?.toIntOrNull() ?: LocalDate.now().year
        var dateMonth = (initP?.groupValues?.get(1)?.toIntOrNull() ?: 1) - 1 // 0-based
        var dateDay = initP?.groupValues?.get(2)?.toIntOrNull() ?: 1

        val date = EditText(this).apply {
            setText(initialDate); setTextColor(onSurfaceColor)
            isFocusable = false; isClickable = true
            setOnClickListener {
                DatePickerDialog(this@MainActivity, pickerDialogThemeId(), { _, y, m, d ->
                    dateYear = y; dateMonth = m; dateDay = d
                    setText("${String.format(Locale.US, "%02d", m + 1)}/${String.format(Locale.US, "%02d", d)}/$y")
                }, dateYear, dateMonth, dateDay).show()
            }
        }
        // initial time parts (HH:MM) for the clock pickers
        fun parseHh(mm: String): Int = mm.trim().split(":").getOrNull(0)?.toIntOrNull() ?: 9
        fun parseMin(mm: String): Int = mm.trim().split(":").getOrNull(1)?.toIntOrNull() ?: 0
        var startH = parseHh(initialStart); var startM = parseMin(initialStart)
        var endH = parseHh(initialEnd); var endM = parseMin(initialEnd)

        val start = EditText(this).apply {
            setText(time12(initialStart)); setTextColor(onSurfaceColor)
            isFocusable = false; isClickable = true
            setOnClickListener {
                TimePickerDialog(this@MainActivity, pickerDialogThemeId(), { _, h, m ->
                    startH = h; startM = m
                    setText(time12("$h:$m"))
                }, startH, startM, false).show()
            }
        }
        val end = EditText(this).apply {
            setText(time12(initialEnd)); setTextColor(onSurfaceColor)
            isFocusable = false; isClickable = true
            setOnClickListener {
                TimePickerDialog(this@MainActivity, pickerDialogThemeId(), { _, h, m ->
                    endH = h; endM = m
                    setText(time12("$h:$m"))
                }, endH, endM, false).show()
            }
        }
        val brk = EditText(this).apply { setText(initialBreak); setTextColor(onSurfaceColor); inputType = InputType.TYPE_CLASS_NUMBER }

        // project picker row
        val projNames = jobSites.map { it.name }.toTypedArray()
        var selectedIdx = if (initialSiteId > 0) jobSites.indexOfFirst { it.id == initialSiteId } else 0
        if (selectedIdx < 0) selectedIdx = 0
        val projLbl = TextView(this).apply { text = "Project: ${jobSites.getOrNull(selectedIdx)?.name ?: "?"}"; textSize = 14f; setTextColor(primaryColor); setPadding(0, dp(6), 0, 0) }
        projLbl.setOnClickListener {
            AlertDialog.Builder(this@MainActivity, pickerDialogThemeId())
                .setTitle("Select Project")
                .setSingleChoiceItems(if (projNames.isEmpty()) arrayOf("No projects") else projNames, selectedIdx) { _, w -> selectedIdx = w }
                .setPositiveButton("OK") { _, _ -> projLbl.text = "Project: ${jobSites.getOrNull(selectedIdx)?.name ?: "?"}" }
                .show()
        }

        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), dp(4)) }
        fun addField(labelTxt: String, field: View) {
            form.addView(TextView(this).apply {
                text = labelTxt; textSize = 12f; setTextColor(onSurfaceVariantColor)
                setPadding(0, dp(6), 0, dp(2))
            })
            form.addView(field)
        }
        addField("Date", date)
        addField("Start", start)
        addField("End", end)
        addField("Break", brk)
        form.addView(projLbl)

        AlertDialog.Builder(this@MainActivity, pickerDialogThemeId())
            .setTitle(title)
            .setView(form)
            .setPositiveButton(confirmLabel) { _, _ ->
                val rid = jobSites.getOrNull(selectedIdx)?.id ?: 1
                onSave(date.text.toString(), time24(start.text.toString()), time24(end.text.toString()),
                    brk.text.toString().toIntOrNull() ?: 0, rid)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun fieldColumn(vararg fields: View): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), dp(4)) }
        fields.forEachIndexed { i, f ->
            col.addView(f)
            if (i < fields.size - 1) col.addView(View(this).apply {}, LinearLayout.LayoutParams(1, dp(10)))
        }
        return col
    }

    // ==================== HELPERS ====================

    private fun workedMinutes(s: WorkSession): Int {
        val sm = LocalTime.parse(s.startTime).toSecondOfDay()
        val em = LocalTime.parse(s.endTime).toSecondOfDay()
        var diff = em - sm
        if (diff < 0) diff += 24 * 3600
        return diff / 60 - s.breakMinutes
    }

    private fun formatMinutesShort(total: Int): String {
        val h = total / 60; val m = total % 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }

    // 24h "14:30" -> "2:30 PM"; storage stays 24h for LocalTime.parse
    private fun time12(hm24: String): String {
        val p = hm24.trim().split(":")
        if (p.size < 2) return hm24
        val h = p.getOrNull(0)?.toIntOrNull() ?: 0
        val m = p.getOrNull(1)?.toIntOrNull() ?: 0
        val am = h < 12
        var hh = h % 12; if (hh == 0) hh = 12
        return "$hh:${String.format(Locale.US, "%02d", m)} ${if (am) "AM" else "PM"}"
    }

    // "2:30 PM" (or pass-through "14:30") -> 24h "14:30" for storage/parsing
    private fun time24(s: String): String {
        val t = s.trim().uppercase()
        val isPm = t.endsWith("PM"); val isAm = t.endsWith("AM")
        var core = t
        if (isAm || isPm) core = t.substring(0, t.length - 2).trim()
        val p = core.split(":")
        if (p.size < 2) return s
        var h = p.getOrNull(0)?.toIntOrNull() ?: 0
        val m = p.getOrNull(1)?.toIntOrNull() ?: 0
        if (isPm && h < 12) h += 12
        if (isAm && h == 12) h = 0
        return String.format(Locale.US, "%02d:%02d", h, m)
    }

    // Display an ISO date (yyyy-MM-dd) as MM/dd/yyyy; falls back to the input
    // if it isn't a parseable ISO date.
    private fun isoDateDisplay(iso: String): String = try {
        val d = LocalDate.parse(iso)
        "${String.format(Locale.US, "%02d", d.monthValue)}/${String.format(Locale.US, "%02d", d.dayOfMonth)}/${d.year}"
    } catch (e: Exception) { iso }

    // Convert a user-typed date (MM/dd/yyyy or yyyy-MM-dd) back to ISO for storage.
    private fun parseDateToIso(input: String): String {
        val m = Regex("""(\d{1,2})/(\d{1,2})/(\d{4})""").matchEntire(input.trim())
        if (m != null) {
            return try {
                LocalDate.of(m.groupValues[3].toInt(), m.groupValues[1].toInt(), m.groupValues[2].toInt()).toString()
            } catch (e: Exception) { input }
        }
        return if (input.trim().matches(Regex("""\d{4}-\d{2}-\d{2}"""))) input.trim() else input
    }

    private fun formatElapsedMs(totalMs: Long): String {
        val s = totalMs / 1000
        val h = s / 3600; val m = (s % 3600) / 60; val ss = s % 60
        return if (h > 0) "${h}h ${m}m ${ss}s" else if (m > 0) "${m}m ${ss}s" else "${ss}s"
    }

    // ==================== XLSX EXPORT ====================

    /**
     * Writes bytes into Downloads/HoursTracker/<subdir>/<filename>. Uses the
     * MediaStore (Android 10+) so the OS creates and indexes the nested folder
     * under Downloads reliably under scoped storage; falls back to plain File
     * IO on older versions. Returns a human-friendly display path.
     */
    private fun writeDownload(subdir: String, filename: String, bytes: ByteArray): Pair<String, Uri?> {
        val relFolder = "Download/HoursTracker/${if (subdir.isBlank()) "" else "$subdir/"}".trimEnd('/') + "/"
        val dirName = if (subdir.isBlank()) "HoursTracker" else "$subdir"
        if (Build.VERSION.SDK_INT >= 29) {
            // Reuse the existing entry when present so repeated writes overwrite the
            // same file instead of MediaStore creating "Name (1).xlsx" copies.
            val existing = contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH}=?",
                arrayOf(filename, relFolder), null
            )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
            val uri: Uri
            if (existing != null) {
                uri = android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, existing)
                contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                    ?: error("Could not open output stream")
            } else {
                val cv = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, filename)
                    put(MediaStore.Downloads.RELATIVE_PATH, relFolder)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                    ?: error("Could not create file entry")
                try {
                    contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                        ?: error("Could not open output stream")
                    cv.clear()
                    cv.put(MediaStore.Downloads.IS_PENDING, 0)
                    contentResolver.update(uri, cv, null, null)
                } catch (e: Exception) {
                    contentResolver.delete(uri, null, null)
                    throw e
                }
            }
            val pathPart = (if (dirName.isBlank()) "" else "$dirName/") + filename
            return "Downloads/$pathPart" to uri
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "HoursTracker")
            val sub = if (subdir.isBlank()) dir else File(dir, subdir)
            sub.mkdirs()
            File(sub, filename).writeBytes(bytes)
            return "Downloads/${if (dirName.isBlank()) "" else "$dirName/"}${filename}" to Uri.fromFile(File(sub, filename))
        }
    }

    /** Removes a previously exported file from Downloads/HoursTracker/<subdir>/<filename>. */
    private fun deleteDownload(subdir: String, filename: String) {
        try {
            val relFolder = "Download/HoursTracker/${if (subdir.isBlank()) "" else "$subdir/"}"
            if (Build.VERSION.SDK_INT >= 29) {
                val sel = "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH}=?"
                contentResolver.delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI, sel, arrayOf(filename, relFolder))
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "HoursTracker")
                val sub = if (subdir.isBlank()) dir else File(dir, subdir)
                File(sub, filename).delete()
            }
        } catch (e: Exception) {
            // Non-fatal: the file may not exist or the OS may block deletion.
        }
    }

    /** Shares a generated file (e.g. exported PDF) via the system share sheet. */
    private fun shareFile(filename: String, uri: Uri?) {
        if (uri == null) {
            statusMessage = "Sharing failed: file not available"
            renderAll()
            return
        }
        try {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, filename)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "Share PDF"))
        } catch (e: Exception) {
            statusMessage = "Share failed: ${e.message}"
            renderAll()
        }
    }

    /** Writes (or removes) only this project's exported workbook — not every project's. */
    private fun exportSite(siteId: Int) {
        val site = jobSites.find { it.id == siteId } ?: return
        val siteSessions = sessions.filter { it.jobSiteId == siteId }.sortedWith(compareBy({ it.date }, { it.startTime }))
        val safeName = site.name.replace("/", "-").replace("\\\\", "-").trim() + ".xlsx"
        if (siteSessions.isEmpty()) {
            // No tasks left for this project — remove its stale export.
            deleteDownload("Tasks", safeName)
        } else {
            // Clear any "Name (1).xlsx" copies left behind before writing the canonical file.
            deleteDownloadCopies("Tasks", safeName)
            writeDownload("Tasks", safeName, buildXlsxSheets(listOf("Task" to buildRows(siteSessions))))
        }
    }

    /** Deletes MediaStore entries like "Name (1).xlsx", "Name (2).xlsx" created by earlier duplicate inserts. */
    private fun deleteDownloadCopies(subdir: String, canonicalName: String) {
        if (Build.VERSION.SDK_INT < 29) return
        try {
            val relFolder = "Download/HoursTracker/${if (subdir.isBlank()) "" else "$subdir/"}"
            val base = canonicalName.removeSuffix(".xlsx")
            val pattern = "$base (%).xlsx"
            val sel = "${MediaStore.Downloads.DISPLAY_NAME} LIKE ? AND ${MediaStore.Downloads.RELATIVE_PATH}=?"
            val ids = mutableListOf<Long>()
            contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Downloads._ID), sel, arrayOf(pattern, relFolder), null
            )?.use { c -> while (c.moveToNext()) ids.add(c.getLong(0)) }
            ids.forEach { id ->
                contentResolver.delete(
                    android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id), null, null)
            }
        } catch (e: Exception) {
            // Non-fatal cleanup.
        }
    }

    private fun buildRows(rows: List<WorkSession>): List<List<String>> {
            val out = mutableListOf(listOf("Date", "Start", "End", "Break (min)", "Worked (min)", "Notes"))
            rows.forEach { s ->
                val worked = durationMinutes(s.startTime, s.endTime) - s.breakMinutes
                out.add(listOf(isoDateDisplay(s.date), time12(s.startTime), time12(s.endTime), s.breakMinutes.toString(), worked.toString(), s.notes ?: ""))
            }
            return out
        }

        private fun durationMinutes(start: String, end: String): Int {
            val sm = LocalTime.parse(start).toSecondOfDay()
            val em = LocalTime.parse(end).toSecondOfDay()
            var diff = em - sm
            if (diff < 0) diff += 24 * 3600
            return diff / 60
        }

        private fun hhMm(totalMin: Int): String {
            val h = totalMin / 60
            val m = totalMin % 60
            return "${h}:${String.format(Locale.US, "%02d", m)}"
        }

        // ==================== DATE-RANGE EXPORT ====================

        private fun showExportRange() {
            // Each preset resolves its [from, to] range and exports it; the
            // picker-based options open a dialog first. Lambdas are typed
            // () -> Unit, so the range must be consumed here explicitly.
            val opts = listOf(
                "This week" to { rangeForPreset("thisweek").let { exportRange(it.first, it.second) } },
                "Last week" to { rangeForPreset("lastweek").let { exportRange(it.first, it.second) } },
                "2 weeks from date…" to { showTwoWeekFromDatePicker() },
                "All time" to { rangeForPreset("all").let { exportRange(it.first, it.second) } },
                "Custom range…" to { showCustomRangePicker() }
            )
            AlertDialog.Builder(this, pickerDialogThemeId())
                .setTitle("Export")
                .setItems(opts.map { it.first }.toTypedArray()) { _, which ->
                    opts[which].second()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // Inclusive [from, to] as ISO dates for the given display preset.
        private fun rangeForPreset(key: String): Pair<String, String> {
            val today = LocalDate.now()
            return when (key) {
                "thisweek" -> {
                    val sunday = today.with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
                    sunday.toString() to sunday.plusDays(6).toString()
                }
                "lastweek" -> {
                    val sunday = today.with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
                    sunday.minusWeeks(1).toString() to sunday.minusDays(1).toString()
                }
                "thismonth" -> today.withDayOfMonth(1).toString() to today.withDayOfMonth(today.lengthOfMonth()).toString()
                "lastmonth" -> {
                    val first = today.withDayOfMonth(1).minusMonths(1)
                    first.toString() to first.withDayOfMonth(first.lengthOfMonth()).toString()
                }
                "30days" -> today.minusDays(29).toString() to today.toString()
                else -> {
                    val all = sessions.map { it.date }.sorted()
                    (if (all.isEmpty()) today.toString() else all.first()) to
                        (if (all.isEmpty()) today.toString() else all.last())
                }
            }
        }

        private fun showCustomRangePicker() {
            // Chain two DatePickerDialogs: pick start, then pick end, then export.
            val today = LocalDate.now()
            val pickEnd = { start: LocalDate ->
                DatePickerDialog(this, pickerDialogThemeId(), { _, y, mo, d ->
                    val end = LocalDate.of(y, mo + 1, d)
                    if (end.isBefore(start)) {
                        statusMessage = "End date can't be before start date"
                        renderAll()
                    } else {
                        exportRange(start.toString(), end.toString())
                    }
                }, today.year, today.monthValue - 1, today.dayOfMonth).show()
            }
            DatePickerDialog(this, pickerDialogThemeId(), { _, y, mo, d ->
                pickEnd(LocalDate.of(y, mo + 1, d))
            }, today.year, today.monthValue - 1, today.dayOfMonth).show()
        }

        // Export 2 weeks (14 days) starting on a calendar-picked date.
        private fun showTwoWeekFromDatePicker() {
            val today = LocalDate.now()
            DatePickerDialog(this, pickerDialogThemeId(), { _, y, mo, d ->
                showTwoWeekRange(LocalDate.of(y, mo + 1, d))
            }, today.year, today.monthValue - 1, today.dayOfMonth).show()
        }

        private fun showTwoWeekRange(start: LocalDate) {
            exportRange(start.toString(), start.plusDays(13).toString())
        }

        /**
         * Builds the date-range xlsx + matching PDF and writes both to
         * Downloads/HoursTracker/. Summary sheet = per-project totals with an
         * hh:mm grand total; By Week sheet = each project's weekly hh:mm totals
         * (weeks start Sunday) plus a per-week grand total row.
         */
        private fun exportRange(from: String, to: String) {
            try {
                val inRange = sessions.filter { it.date >= from && it.date <= to }
                if (inRange.isEmpty()) { statusMessage = "No tasks in range"; renderAll(); return }
                val siteName = { id: Int -> jobSites.find { it.id == id }?.name ?: "Unknown" }

                // Summary rows: one per project + grand total.
                val bySite = inRange.groupBy { it.jobSiteId }
                    .map { (id, rows) -> Triple(siteName(id), rows.sumOf { workedMinutes(it) }, id) }
                    .sortedBy { it.first }
                val grandTotal = bySite.sumOf { it.second }
                val summaryRows = mutableListOf<List<String>>(listOf("Project", "Hours"))
                bySite.forEach { summaryRows.add(listOf(it.first, hhMm(it.second))) }
                summaryRows.add(listOf("TOTAL", hhMm(grandTotal)))

                // By-week rows: project x week-start(Sunday) -> hours, per-week grand total row.
                val weekRows = mutableListOf<List<String>>(listOf("Project", "Week of", "Hours"))
                val byWeek = inRange.groupBy { sundayOf(it.date) }.toSortedMap()
                val weeksByProject = mutableMapOf<Int, MutableMap<String, Int>>()
                inRange.forEach { s ->
                    val w = sundayOf(s.date)
                    weeksByProject.getOrPut(s.jobSiteId) { mutableMapOf() }[w] =
                        (weeksByProject[s.jobSiteId]?.get(w) ?: 0) + workedMinutes(s)
                }
                weeksByProject.toList().sortedBy { siteName(it.first) }.forEach { (pid, weeks) ->
                    weeks.toSortedMap().forEach { (w, mins) ->
                        weekRows.add(listOf(siteName(pid), isoDateDisplay(w), hhMm(mins)))
                    }
                    val total = weeks.values.sum()
                    weekRows.add(listOf(siteName(pid), "— Total —", hhMm(total)))
                }
                byWeek.forEach { (w, rows) ->
                    val total = rows.sumOf { workedMinutes(it) }
                    weekRows.add(listOf("★ Week total", isoDateDisplay(w), hhMm(total)))
                }

                val label = "${from}_to_${to}"
                val (pdfPath, pdfUri) = writeDownload("Export", "Summary_$label.pdf", buildPdf(from, to, summaryRows, weekRows))
                statusMessage = "Exported $from → $to (pdf) ✓"
                val filename = "Summary_$label.pdf"
                AlertDialog.Builder(this, pickerDialogThemeId())
                    .setTitle("Export complete ✓")
                    .setMessage("Saved:\n• $pdfPath")
                    .setPositiveButton("OK", null)
                    .setNeutralButton("Share", { _, _ -> shareFile(filename, pdfUri) })
                    .show()
            } catch (e: Exception) {
                statusMessage = "Export FAILED: ${e.message}"
                AlertDialog.Builder(this, pickerDialogThemeId())
                    .setTitle("Export failed")
                    .setMessage(e.message ?: "Unknown error")
                    .setPositiveButton("OK", null)
                    .show()
            }
            renderAll()
        }

        // ISO date string for the Sunday of the week containing the given ISO date.
        private fun sundayOf(isoDate: String): String =
                LocalDate.parse(isoDate).with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY)).toString()

        private fun buildXlsxSheets(sheets: List<Pair<String, List<List<String>>>>): ByteArray {
            val bos = ByteArrayOutputStream()
            ZipOutputStream(bos).use { zos ->
                zos.putNextEntry(ZipEntry("[Content_Types].xml"))
                val ct = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                    "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                    "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
                    "<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
                sheets.indices.forEach { i ->
                    ct.append("<Override PartName=\"/xl/worksheets/sheet${i + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>")
                }
                ct.append("<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/></Types>")
                zos.write(ct.toString().toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                zos.putNextEntry(ZipEntry("_rels/.rels"))
                zos.write(("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                    "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                    "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>" +
                    "</Relationships>").toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                zos.putNextEntry(ZipEntry("xl/workbook.xml"))
                val wb = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                    "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>")
                sheets.indices.forEach { i ->
                    wb.append("<sheet name=\"").append(xmlEscape(sheets[i].first)).append("\" sheetId=\"${i + 1}\" r:id=\"rId${i + 1}\"/>")
                }
                wb.append("</sheets></workbook>")
                zos.write(wb.toString().toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                zos.putNextEntry(ZipEntry("xl/_rels/workbook.xml.rels"))
                val wr = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                    "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
                sheets.indices.forEach { i ->
                    wr.append("<Relationship Id=\"rId${i + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet${i + 1}.xml\"/>")
                }
                wr.append("</Relationships>")
                zos.write(wr.toString().toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                sheets.forEachIndexed { si, (_, rows) ->
                    zos.putNextEntry(ZipEntry("xl/worksheets/sheet${si + 1}.xml"))
                    val sb = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                        "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>")
                    rows.forEachIndexed { i, row ->
                        val r = i + 1
                        sb.append("<row r=\"$r\">")
                        row.forEachIndexed { ci, cell ->
                            val col = ('A'.code + ci).toChar()
                            sb.append("<c r=\"$col$r\" t=\"inlineStr\"><is><t>").append(xmlEscape(cell)).append("</t></is></c>")
                        }
                        sb.append("</row>")
                    }
                    sb.append("</sheetData></worksheet>")
                    zos.write(sb.toString().toByteArray(Charsets.UTF_8))
                    zos.closeEntry()
                }
            }
            return bos.toByteArray()
        }

        /**
         * Renders the summary + weekly tables into a multi-page PDF via the native
         * android.graphics.pdf.PdfDocument API (no external dependencies).
         */
        private fun buildPdf(from: String, to: String, summary: List<List<String>>, byWeek: List<List<String>>): ByteArray {
            val doc = PdfDocument()
            val pageInfo = PdfDocument.PageInfo.Builder(612, 792, 1).create()
            val bos = ByteArrayOutputStream()
            try {
                var pw = PdfPageWriter(doc, pageInfo)

                // --- Page: Summary ---
                pw = pw.newPage()
                pw.heading("Hours Tracker — Summary")
                pw.subhead("Range: ${isoDateDisplay(from)}  →  ${isoDateDisplay(to)}")
                pw.space()
                pw.table(summary, columns = intArrayOf(420, 100))
                pw.closePage()

                // --- Page: By Week ---
                pw = pw.newPage()
                pw.subhead("Range: ${isoDateDisplay(from)}  →  ${isoDateDisplay(to)}")
                pw.space()
                pw.table(byWeek, columns = intArrayOf(200, 260, 90))
                pw.closePage()

                doc.writeTo(bos)
            } finally {
                doc.close()
            }
            return bos.toByteArray()
        }

        /** Helper to lay out content on PdfDocument pages with simple pagination. */
        private inner class PdfPageWriter(private val doc: PdfDocument, private val pageInfo: PdfDocument.PageInfo) {
            private var canvas: Canvas? = null
            private var page: PdfDocument.Page? = null
            private var y = 0f
            private val title = Paint().apply { color = Color.BLACK; textSize = 20f; isFakeBoldText = true }
            private val sub = Paint().apply { color = Color.DKGRAY; textSize = 12f }
            private val head = Paint().apply { color = Color.BLACK; textSize = 11f; isFakeBoldText = true }
            private val body = Paint().apply { color = Color.BLACK; textSize = 11f }
            private val band = Paint().apply { color = 0xFFE9E9EF.toInt(); style = Paint.Style.FILL }
            private val rule = Paint().apply { color = 0xFFB0B0B8.toInt(); style = Paint.Style.STROKE; strokeWidth = 1f }
            private val rowH = 22f
            private val leftMargin = 48f
            private val rightMargin = 48f

            private fun ensureSpace(need: Float) {
                if (canvas != null && y + need > pageInfo.pageHeight - 40f) newPage()
            }

            /** Finish the current page (if any) and start a fresh one. */
            fun newPage(): PdfPageWriter {
                page?.let { doc.finishPage(it) }
                page = doc.startPage(pageInfo)
                canvas = page!!.canvas
                y = 40f
                return this
            }

            fun heading(t: String) { canvas?.drawText(t, leftMargin, y, title); y += 26f }
            fun subhead(t: String) { canvas?.drawText(t, leftMargin, y, sub); y += 18f }
            fun space() { y += 10f }

            fun table(rows: List<List<String>>, columns: IntArray) {
                var totalW = 0f
                for (c in columns) totalW += c.toFloat()
                val scale = (pageInfo.pageWidth.toFloat() - leftMargin - rightMargin) / totalW
                val widths = FloatArray(columns.size)
                for (ci in widths.indices) widths[ci] = columns[ci].toFloat() * scale
                val x0 = leftMargin
                rows.forEachIndexed { idx, row ->
                    ensureSpace(rowH)
                    // zebra banding
                    if (idx > 0 && idx % 2 == 0) {
                        canvas?.drawRect(x0, y - rowH + 4, x0 + widths.sum(), y + 4, band)
                    }
                    var colX = x0
                    row.forEachIndexed { ci, cell ->
                        val p = if (idx == 0 || ci == row.size - 1) head else body
                        // right-align the numeric last column
                        if (ci == row.size - 1 && row.size > 1) {
                            val tw = p.measureText(cell)
                            canvas?.drawText(cell, colX + widths[ci] - tw - 6, y, p)
                        } else {
                            canvas?.drawText(cell, colX + 6, y, p)
                        }
                        colX += widths[ci]
                    }
                    canvas?.drawLine(x0, y + 4, x0 + widths.sum(), y + 4, rule)
                    y += rowH
                }
            }

            /** Finish the current page so it can be written out. */
            fun closePage() {
                page?.let { doc.finishPage(it) }
                page = null
                canvas = null
            }
        }

    private fun xmlEscape(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}