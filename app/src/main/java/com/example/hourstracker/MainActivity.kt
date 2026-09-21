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
    private var navScreen = 0 // 0 = Home, 1 = Projects, 2 = Tasks, 3 = Settings, 4 = This week tasks, 5 = Pay period tasks

    private var jobSites = mutableListOf<JobSite>()
    private var sessions = mutableListOf<WorkSession>()
    private var drawerOpen = false


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
        // Notifications are hidden on Android 13+ unless the user grants
        // POST_NOTIFICATIONS — request it up front so the timer status can show.
        requestNotificationPermission()
        // Restore the running clock from persisted wall-clock state so the timer
        // keeps accruing even across process death / relaunch.
        restoreClockState()
        refreshData()
        buildLayout()
        startTicker()
        // Re-surface the running/paused notification after process death.
        syncTimerNotification()
        // The home-screen widget's Stop button opens the app with CMD_STOP: pause
        // the running clock and show the usual "which job" picker to save the run.
        if (getIntent()?.action == "com.example.hourstracker.CMD_STOP" && clockRunning) {
            clockPaused = true
            persistClock()
            mainHandler.post { try { showStopPicker() } catch (t: Throwable) { } }
        }
    }

    private val notificationPermissionCode = 901
    // Android 13+ (API 33, targetSdk 36): POST_NOTIFICATIONS is required before
    // any notification shows. Ask for it on launch; the timer status notification
    // simply stays hidden if the user declines (the app still works).
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED) return
        requestPermissions(
            arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
            notificationPermissionCode
        )
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

    // ---- Material Design 3 (Material You) ----
    // On Android 12+ the palette is derived from the device wallpaper through the
    // platform's system_* tonal colours (no androidx needed); older devices fall
    // back to a baseline M3 teal scheme. M3 uses tonal surfaces rather than shadows,
    // 12dp cards, pill buttons and 28dp dialogs.
    private fun sysColor(resId: Int): Int = try { getColor(resId) } catch (e: Exception) { 0 }
    private val useDynamic get() = Build.VERSION.SDK_INT >= 31

    /** Resolve the M3 tonal role: dark tone / light tone from the system palette,
     *  or the baseline fallback when dynamic colour is unavailable. */
    private fun dyn(darkRes: Int, lightRes: Int, fallback: Int): Int {
        if (!useDynamic) return fallback
        val c = sysColor(if (isDark) darkRes else lightRes)
        return if (c != 0) c else fallback
    }

    private val bgColor get() = dyn(android.R.color.system_neutral1_900, android.R.color.system_neutral1_50,
        if (isDark) 0xFF111412.toInt() else 0xFFF8FAF6.toInt())
    private val surfaceColor get() = bgColor
    private val surfaceVariantColor get() = dyn(android.R.color.system_neutral2_800, android.R.color.system_neutral2_100,
        if (isDark) 0xFF3F4943.toInt() else 0xFFDEE4DD.toInt())
    private val onSurfaceColor get() = dyn(android.R.color.system_neutral1_50, android.R.color.system_neutral1_900,
        if (isDark) 0xFFE1E3DE.toInt() else 0xFF191C1A.toInt())
    private val onSurfaceVariantColor get() = dyn(android.R.color.system_neutral2_200, android.R.color.system_neutral2_700,
        if (isDark) 0xFFBFC9C1.toInt() else 0xFF3F4943.toInt())
    private val primaryColor get() = dyn(android.R.color.system_accent1_200, android.R.color.system_accent1_600,
        if (isDark) 0xFF4DB6AC.toInt() else 0xFF00695C.toInt())
    private val primaryContainerColor get() = dyn(android.R.color.system_accent1_900, android.R.color.system_accent1_100,
        if (isDark) 0xFF00504A.toInt() else 0xFFB2DFDB.toInt())
    private val onPrimaryContainerColor get() = dyn(android.R.color.system_accent1_100, android.R.color.system_accent1_900,
        if (isDark) 0xFFB2DFDB.toInt() else 0xFF00251A.toInt())
    private val errorColor get() = dyn(android.R.color.system_error_200, android.R.color.system_error_600,
        if (isDark) 0xFFF2B8B5.toInt() else 0xFFB3261E.toInt())
    /** M3 surface-container tone — used for card fills and the dark status strip. */
    private val surfaceContainerColor get() = dyn(android.R.color.system_neutral1_800, android.R.color.system_neutral1_100,
        if (isDark) 0xFF1B1F1D.toInt() else 0xFFF1F4F0.toInt())
    /** M3 outline — used for outlined cards and hairline dividers. */
    private val outlineColor get() = dyn(android.R.color.system_neutral2_600, android.R.color.system_neutral2_500,
        if (isDark) 0xFF8C958E.toInt() else 0xFF6F7972.toInt())
    // M3 top app bars are surface-toned. The status-bar strip keeps a solid tone so
    // the system icons stay legible: a light container in light mode (dark icons),
    // a dark surface in dark mode (light icons).
    private val appBarColor get() = if (isDark) surfaceContainerColor else primaryContainerColor

    private fun parseHex(hex: String): Int {
        val h = hex.removePrefix("#").trim()
        return if (h.length == 6) {
            try {
                Color.rgb(h.substring(0, 2).toInt(16), h.substring(2, 4).toInt(16), h.substring(4, 6).toInt(16))
            } catch (e: Exception) { 0xFF00796B.toInt() }
        } else 0xFF00796B.toInt()
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

    // M3 outlined card: 12dp corners, surface-container fill and a 1dp outline.
    // M3 conveys depth with tonal surfaces instead of drop shadows.
    private fun card(): GradientDrawable {
        val fill = surfaceContainerColor
        return GradientDrawable().apply {
            setShape(GradientDrawable.RECTANGLE)
            setColor(fill)
            setCornerRadius(dp(12).toFloat())
            setStroke(dp(1), outlineColor)
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
                    employer = c.getColumnIndex("employer").let { idx ->
                        if (idx >= 0 && !c.isNull(idx)) c.getString(idx) else null
                    },
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

    private fun addSite(name: String, location: String = "", employer: String = "", wage: String? = null) {
        val cv = android.content.ContentValues()
        cv.put("name", name)
        cv.put("location", if (location.isBlank()) null else location)
        cv.put("employer", if (employer.isBlank()) null else employer)
        cv.put("hourly_wage", parseWage(wage))
        cv.put("color", "#00796B")
        db.writableDatabase.insert("job_sites", null, cv)
        refreshData(); renderAll()
    }

    private fun renameSite(id: Int, name: String, location: String, employer: String = "", wage: String? = null) {
        android.content.ContentValues().apply {
            put("name", name)
            put("location", if (location.isBlank()) null else location)
            put("employer", if (employer.isBlank()) null else employer)
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

    // ===== Pay period =====
    // A user-picked [start, end] date range (ISO, inclusive) set in Settings →
    // Pay period. Defaults to the current week + the following week (14 days).
    private val payPeriodStart: String
        get() = prefs.getString("pay_start", null)
            ?: sundayOf(LocalDate.now().toString())
    private val payPeriodEnd: String
        get() = prefs.getString("pay_end", null)
            ?: LocalDate.parse(sundayOf(LocalDate.now().toString())).plusDays(13).toString()


    // Compute overtime + pay across all jobs for any inclusive [from, to] ISO range.
    // Overtime is applied per calendar week (weeks start Sunday) using the global
    // weekly threshold; each job's hours are priced at that job's own wage.
    private fun rangeSummary(from: String, to: String): Quad {
        val inRange = sessions.filter { it.date >= from && it.date <= to }
        val totalMin = inRange.sumOf { workedMinutes(it) }
        val thresholdSec = (overtimeThresholdHours * 60).toInt()
        var otMin = 0
        var basePay = 0.0
        var otPay = 0.0
        // Each calendar week gets its own threshold, so a multi-week pay period
        // earns overtime the same way it would week by week.
        inRange.groupBy { sundayOf(it.date) }.forEach { (_, weekSessions) ->
            val weekMin = weekSessions.sumOf { workedMinutes(it) }
            val weekOt = (weekMin - minOf(weekMin, thresholdSec)).coerceAtLeast(0)
            otMin += weekOt
            if (weekMin > 0) {
                jobSites.forEach siteLoop@{ site ->
                    val siteMin = weekSessions.filter { it.jobSiteId == site.id }.sumOf { workedMinutes(it) }
                    if (siteMin > 0) {
                        val wage = site.hourlyWage?.trim()?.toDoubleOrNull() ?: return@siteLoop
                        // The week's overtime is job-agnostic, so split it across the jobs
                        // in proportion to their hours, then price each job's share at its
                        // own wage. (Deriving it per job from min(siteMin, threshold) billed
                        // nothing whenever no single job passed the threshold.)
                        val siteOt = if (siteMin >= weekMin) weekOt
                        else Math.round(weekOt.toDouble() * siteMin / weekMin).toInt()
                        val siteReg = siteMin - siteOt
                        basePay += siteReg / 60.0 * wage
                        otPay += siteOt / 60.0 * wage * overtimeRateVal
                    }
                }
            }
        }
        return Quad(totalMin, otMin, basePay, otPay)
    }

    // Simple 4-value holder (keeps rangeSummary readable).
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
    syncTimerNotification()
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
    stopTimerNotification()
    renderAll()
}

// ============ Timer status notification (foreground service) ============
// Keeps an ongoing notification in the shade (running / paused + live elapsed)
// so the user can see the clock state without opening the app. The service
// reads the same persisted state, so it also survives process death.
private fun syncTimerNotification() {
    if (!clockRunning) { stopTimerNotification(); return }
    val i = Intent(this, TimerService::class.java).setAction("sync")
    try {
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
    } catch (e: Exception) {
        // runtime may not support FGS (e.g. Mirror runtime) — degrade silently
    }
}

private fun stopTimerNotification() {
    try { stopService(Intent(this, TimerService::class.java)) } catch (e: Exception) { }
}

    // ==================== LAYOUT ====================

    private fun renderAll() {
        buildLayout()
        // Keep the home-screen widget (and the timer notification) in sync with
        // whatever just changed. updateAll is cheap (only touches existing widgets).
        if (Build.VERSION.SDK_INT >= 26) {
            try { Widget3x1Provider.updateAll(this) } catch (e: Exception) { }
        }
    }

    private fun buildLayout() {
        val topPad = statusBarTop + dp(18)
        val root = FrameLayout(this).apply { setBackgroundColor(bgColor); setPadding(0, topPad, 0, 0) }

        // Edge-to-edge: the status bar is transparent and shows our background, so
        // paint the very top strip in the M3 app-bar tone. That gives the status bar
        // a solid, always-contrasting backdrop and reads as a top app bar without
        // needing any platform status-bar API.
        val topBand = View(this).apply { setBackgroundColor(appBarColor) }
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
        // Three plain bars (no filled button chrome, no glyph that can mis-render).
        val menuBtn = FrameLayout(this).apply {
            id = 3
            setOnClickListener { openDrawer() }
        }
        val mstack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
        }
        fun mbar(margin: Int) {
            mstack.addView(View(this).apply { background = rounded(onSurfaceColor, 2) },
                LinearLayout.LayoutParams(dp(22), dp(3)).apply { topMargin = dp(margin) })
        }
        mbar(0); mbar(4); mbar(4)
        menuBtn.addView(mstack, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        root.addView(menuBtn, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP or Gravity.END))

        if (navScreen == 0) {
            // ==================== HOME SCREEN (clock) ====================
            buildHomeScreen(column)
        } else if (navScreen >= 4) {
            // ==================== RANGE DETAIL (week / pay period) ====================
            buildRangeScreen(column)
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
        wrap.layoutParams = LinearLayout.LayoutParams(dp(176), dp(176)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(18) }

        val haloBack = GradientDrawable().apply { setShape(GradientDrawable.OVAL); setColor(0x24FF6D00) }
        val halo = View(this).apply { background = haloBack }
        haloGlowView = halo
        wrap.addView(halo, FrameLayout.LayoutParams(dp(176), dp(176), Gravity.CENTER))

        val inner = FrameLayout(this).apply {
            background = ovalGradient(0xFF4CAF50.toInt(), 0xFF2E7D32.toInt())
            isClickable = true
            setOnClickListener { onHaloTap() }
        }
        haloButtonView = inner
        val innerCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        val glyph = TextView(this).apply { textSize = 40f; setTextColor(Color.WHITE); setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER }
        val label = TextView(this).apply { textSize = 18f; setTextColor(Color.WHITE); setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER }
        glyphView = glyph; labelView = label
        innerCol.addView(glyph); innerCol.addView(label)
        inner.addView(innerCol, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        wrap.addView(inner, FrameLayout.LayoutParams(dp(158), dp(158), Gravity.CENTER))
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
                text = "Stop"; textSize = 18f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
                setTypeface(null, Typeface.BOLD)
                background = rounded(0xFFFF4038.toInt(), 4)
                setOnClickListener {
                    clockPaused = true
                    showStopPicker()
                }
            }
            column.addView(stopBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(54)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        }

        // Add Task button, always visible under the clock/stop controls
        column.addView(drawerButton("＋ Add Task") { showAddSession() },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })

        updateClockViews()

        // ---- status ----
        if (statusMessage.isNotEmpty()) {
            val st = TextView(this).apply { text = statusMessage; textSize = 12f; setTextColor(onSurfaceVariantColor) }
            column.addView(st, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        column.addView(View(this).apply { }, LinearLayout.LayoutParams(1, dp(8)))

        // ---- summaries on the main screen ----
        // Explicit LayoutParams: a freshly built view has no layoutParams yet, so
        // setMargins() inside the builder would silently no-op.
        column.addView(buildWeeklySummaryCard(), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12); bottomMargin = dp(24)
        })
        column.addView(buildPayPeriodCard(), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
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
        background = rounded(container, 8)
        setPadding(dp(12), dp(5), dp(12), dp(5))
    }

    private fun sessionCard(session: WorkSession): View {
        val site = jobSites.find { it.id == session.jobSiteId }
        val projectColor = parseHex(site?.color ?: "#00796B")
        val expanded = expandedTaskId == session.id
        val wageVal = site?.hourlyWage?.trim()?.toDoubleOrNull()
        val earned = if (wageVal != null) wageVal * workedMinutes(session) / 60.0 else null

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = card()
            setPadding(0, 0, dp(4), 0)
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
        // Left color rail in the project's own color, spans the full card height.
        card.addView(View(this).apply { background = rounded(projectColor, 4) },
            LinearLayout.LayoutParams(dp(5), ViewGroup.LayoutParams.MATCH_PARENT))

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(8), dp(12))
        }
        // line 1: date + big bold hours
        body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }.also { line ->
            line.addView(TextView(this).apply {
                text = isoDateDisplay(session.date); textSize = 14f
                setTypeface(null, Typeface.BOLD); setTextColor(onSurfaceColor)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            line.addView(TextView(this).apply {
                text = formatMinutesShort(workedMinutes(session)); textSize = 16f
                setTypeface(null, Typeface.BOLD); setTextColor(primaryColor)
            })
        })
        // line 2: project name + subtle earnings footer
        body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, 0)
        }.also { line ->
            line.addView(TextView(this).apply {
                text = site?.name ?: "Unknown project"; textSize = 12f; setTextColor(onSurfaceVariantColor)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (earned != null) line.addView(TextView(this).apply {
                text = "$${String.format(Locale.US, "%.2f", earned)}"
                textSize = 12f; setTextColor(onSurfaceVariantColor)
            })
        })

        // When expanded: show start/stop times (and break) on separate lines.
        if (expanded) {
            fun detailRow(label: String, value: String): View {
                val row = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(8), 0, 0)
                }
                row.addView(TextView(this@MainActivity).apply {
                    text = label; textSize = 12f; setTextColor(onSurfaceVariantColor)
                }, LinearLayout.LayoutParams(dp(70), ViewGroup.LayoutParams.WRAP_CONTENT))
                row.addView(TextView(this@MainActivity).apply {
                    text = value; textSize = 13f; setTextColor(onSurfaceColor)
                })
                return row
            }
            body.addView(detailRow("Start", time12(session.startTime)))
            body.addView(detailRow("Stop", time12(session.endTime)))
            if (session.breakMinutes > 0) body.addView(detailRow("Break", "${session.breakMinutes} min"))
        }

        card.addView(body, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        // Top margin between cards (set explicitly — flat-built view has no params yet).
        card.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        }
        return card
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
            background = rounded(surfaceVariantColor, 12)
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

    // Summary card for a date range: combined hours + pay across all jobs. A
        // left accent rail + one big hero hours figure keeps the home screen light;
        // pay sits underneath. The card body is neutral (same surface as task cards)
        // so the only colour is the accent rail — it can't clash with the start button,
        // which shifts green → gold → orange as the clock runs.
        private fun buildSummaryCard(title: String, from: String, to: String, onOpen: (() -> Unit)? = null): View {
            val s = rangeSummary(from, to)
            val totalPay = s.basePay + s.otPay

            val card: LinearLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = card()
                setPadding(0, 0, dp(4), 0)
                setMinimumHeight(dp(62))
                isClickable = onOpen != null
                if (onOpen != null) setOnClickListener { onOpen() }
            }
            // Left accent rail, spans the full card height in the project's M3 primary.
            card.addView(View(this).apply { background = rounded(primaryColor, 4) },
                LinearLayout.LayoutParams(dp(5), ViewGroup.LayoutParams.MATCH_PARENT))

            // title + range
            val lbl = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), 0, dp(8), 0)
            }
            lbl.addView(TextView(this).apply {
                text = title; textSize = 15f; setTypeface(null, Typeface.BOLD); setTextColor(onSurfaceColor)
            })
            lbl.addView(TextView(this).apply {
                text = "${isoDateDisplay(from)} – ${isoDateDisplay(to)}"
                textSize = 11f; setTextColor(onSurfaceVariantColor)
            })
            card.addView(lbl, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            // hero hours + pay
            val valCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.END
            }
            valCol.addView(TextView(this).apply {
                text = formatMinutesShort(s.totalMin); textSize = 20f
                setTypeface(null, Typeface.BOLD); setTextColor(onSurfaceColor); gravity = Gravity.END
            })
            valCol.addView(TextView(this).apply {
                text = "$${String.format(Locale.US, "%.2f", totalPay)}"
                textSize = 12f; setTextColor(onSurfaceVariantColor); gravity = Gravity.END
            })
            card.addView(valCol, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            return card
        }

    // Home-screen card: this week (Sunday–Saturday).
    private fun buildWeeklySummaryCard(): View {
        val sunday = sundayOf(LocalDate.now().toString())
        val saturday = LocalDate.parse(sunday).plusDays(6).toString()
        return buildSummaryCard("This week", sunday, saturday) { navScreen = 4; renderAll() }
    }

    // Home-screen card: the pay period range picked in Settings → Pay period.
    private fun buildPayPeriodCard(): View =
        buildSummaryCard("This pay period", payPeriodStart, payPeriodEnd) { navScreen = 5; renderAll() }

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
                background = rounded(surfaceVariantColor, 12)
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
                background = rounded(surfaceVariantColor, 12)
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

    // ==================== SETTINGS ====================
    // Every setting lives in a group card: a small caption above a rounded card
    // whose rows share the same padding, label style and hairline dividers.

    private val dividerColor: Int
        get() = outlineColor

    private fun buildSettingsTab(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // ---- Overtime: numeric fields apply immediately (no Save button) ----
        val th = EditText(this).apply {
            setText(formatWage(overtimeThresholdHours)); setTextColor(onSurfaceColor); gravity = Gravity.END
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val rt = EditText(this).apply {
            setText(formatWage(overtimeRateVal)); setTextColor(onSurfaceColor); gravity = Gravity.END
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
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
        col.addView(settingsGroup("Overtime", listOf(
            settingsRowInput("Starts after (hrs/week)", th),
            settingsRowInput("Rate (e.g. 1.5)", rt)
        )))

        // ---- Pay period: the range used by "This pay period" on the main screen ----
        col.addView(settingsGroup("Pay period", listOf(
            settingsRowValue("Period start", isoDateDisplay(payPeriodStart)) { showPayDatePicker(true) },
            settingsRowValue("Period end", isoDateDisplay(payPeriodEnd)) { showPayDatePicker(false) }
        )))

        // ---- Appearance ----
        col.addView(settingsGroup("Appearance", listOf(
            settingsRowValue("Theme", themeLabel()) { toggleDark() }
        )))
        return col
    }

    /** A settings group: caption + card whose rows are split by hairline dividers. */
    private fun settingsGroup(caption: String, rows: List<View>): View {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        wrap.addView(TextView(this).apply {
            text = caption; textSize = 12f; setTypeface(null, Typeface.BOLD)
            setTextColor(onSurfaceVariantColor); setPadding(dp(4), dp(16), 0, dp(6))
        })
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = card()
        }
        rows.forEachIndexed { i, r ->
            if (i > 0) box.addView(View(this).apply { setBackgroundColor(dividerColor) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
            box.addView(r, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        wrap.addView(box, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return wrap
    }

    /** Row with a label on the left and a tappable value on the right. */
    private fun settingsRowValue(label: String, value: String, onClick: () -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            isClickable = true
            setOnClickListener { onClick() }
        }.also { row ->
            row.addView(TextView(this).apply { text = label; textSize = 14f; setTextColor(onSurfaceColor) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(this).apply {
                text = value; textSize = 14f; setTypeface(null, Typeface.BOLD); setTextColor(primaryColor)
            })
        }

    /** Row with a label on the left and an editable field filling the rest. */
    private fun settingsRowInput(label: String, field: EditText): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(6), dp(14), dp(6))
        }.also { row ->
            row.addView(TextView(this).apply { text = label; textSize = 14f; setTextColor(onSurfaceColor) })
            row.addView(field, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

    // Pick the pay period start/end date. The range is kept valid (start <= end)
    // by pushing the other bound when the two would cross.
    private fun showPayDatePicker(isStart: Boolean) {
        val cur = try {
            LocalDate.parse(if (isStart) payPeriodStart else payPeriodEnd)
        } catch (e: Exception) { LocalDate.now() }
        DatePickerDialog(this, pickerDialogThemeId(), { _, y, mo, d ->
            val ed = prefs.edit()
            if (isStart) ed.putString("pay_start", LocalDate.of(y, mo + 1, d).toString())
            else ed.putString("pay_end", LocalDate.of(y, mo + 1, d).toString())
            ed.apply()
            if (payPeriodStart > payPeriodEnd) {
                val fix = prefs.edit()
                if (isStart) fix.putString("pay_end", payPeriodStart) else fix.putString("pay_start", payPeriodEnd)
                fix.apply()
            }
            statusMessage = "Pay period: ${isoDateDisplay(payPeriodStart)} - ${isoDateDisplay(payPeriodEnd)}"
            renderAll()
        }, cur.year, cur.monthValue - 1, cur.dayOfMonth).show()
    }

    private fun buildDrawer(): View {
        // Panel background = the app background, so the menu cards stand out against it.
        val frame = FrameLayout(this).apply { setBackgroundColor(bgColor) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(24), dp(20), dp(24)) }
        frame.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // ---- hamburger menu: one card per item (Projects / Tasks / Settings / Export) ----
        // Each item mirrors the home cards: a left accent rail, a bold title and a
        // quiet subtitle. Selecting one closes the drawer and opens that section.
        fun menuCard(name: String, sub: String, onClick: () -> Unit) {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, dp(4), 0)
                background = card()
                setMinimumHeight(dp(64))
                isClickable = true
                setOnClickListener { onClick() }
            }
            item.addView(View(this).apply { background = rounded(primaryColor, 4) },
                LinearLayout.LayoutParams(dp(5), ViewGroup.LayoutParams.MATCH_PARENT))
            val txt = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(12), dp(8), dp(12))
            }
            txt.addView(TextView(this).apply {
                text = name; textSize = 20f; setTypeface(null, Typeface.BOLD); setTextColor(onSurfaceColor)
            })
            txt.addView(TextView(this).apply {
                text = sub; textSize = 12f; setTextColor(onSurfaceVariantColor)
            })
            item.addView(txt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            col.addView(item, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                // Explicit LayoutParams: setMargins() no-ops on a view that hasn't been
                // attached with layout params yet.
                bottomMargin = dp(12)
            })
        }
        menuCard("Projects", "Job sites & rates") { drawerTab = 0; navScreen = 1; filteredSiteId = null; closeDrawer(); renderAll() }
        menuCard("Tasks", "Recorded sessions") { drawerTab = 1; navScreen = 2; filteredSiteId = null; closeDrawer(); renderAll() }
        menuCard("Settings", "Overtime, pay period, theme") { drawerTab = 2; navScreen = 3; filteredSiteId = null; closeDrawer(); renderAll() }
        menuCard("Export", "Download workbooks (.xlsx)") { closeDrawer(); showExportRange() }

        // Spacer pushes the credit line to the bottom of the (full-height) menu.
        col.addView(View(this).apply {}, LinearLayout.LayoutParams(1, 0).apply { weight = 1f })

        val footer = TextView(this).apply {
            text = "vibe coded by pooh"; textSize = 11f; setTextColor(onSurfaceVariantColor)
            setPadding(0, dp(8), 0, 0)
        }
        col.addView(footer)
        return frame
    }

    // Full-screen list of every task recorded in a date range. Opened by tapping
    // the "This week" (navScreen 4) or "This pay period" (navScreen 5) cards.
    private fun buildRangeScreen(column: LinearLayout) {
        val isWeek = navScreen == 4
        val from: String
        val to: String
        if (isWeek) {
            val sunday = sundayOf(LocalDate.now().toString())
            from = sunday
            to = LocalDate.parse(sunday).plusDays(6).toString()
        } else {
            from = payPeriodStart
            to = payPeriodEnd
        }
        val title = if (isWeek) "This week" else "This pay period"

        column.addView(TextView(this).apply {
            text = title; textSize = 22f; setTypeface(null, Typeface.BOLD); setTextColor(onSurfaceColor)
        })
        column.addView(TextView(this).apply {
            text = "${isoDateDisplay(from)} – ${isoDateDisplay(to)}"; textSize = 13f
            setTextColor(onSurfaceVariantColor); setPadding(0, dp(2), 0, dp(6))
        })
        column.addView(View(this).apply {}, LinearLayout.LayoutParams(1, dp(22)))

        // Summary for the range (not tappable here — this IS the detail view).
        column.addView(buildSummaryCard(title, from, to), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(18)
        })

        val inRange = sessions.filter { it.date >= from && it.date <= to }
            .sortedWith(compareByDescending<WorkSession> { it.date }.thenByDescending { it.startTime })
        if (inRange.isEmpty()) {
            column.addView(TextView(this).apply {
                text = "No tasks in this range"; textSize = 14f; setTextColor(onSurfaceVariantColor)
                gravity = Gravity.CENTER; setPadding(0, dp(20), 0, dp(20))
            })
        } else {
            column.addView(TextView(this).apply {
                text = "${inRange.size} task" + (if (inRange.size == 1) "" else "s")
                textSize = 13f; setTypeface(null, Typeface.BOLD); setTextColor(onSurfaceVariantColor)
                setPadding(dp(4), 0, 0, dp(4))
            })
            inRange.forEach { s ->
                column.addView(sessionCard(s), LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(10)
                })
            }
        }
    }

    // Full-screen section page: section heading + section content.
    // (No "Home" label — the back gesture/button returns to the main screen.)
    private fun buildSectionScreen(column: LinearLayout) {
        // Section heading. The spacer below keeps the first row clear of the ☰ button.
        column.addView(TextView(this).apply {
            text = listOf("Projects", "Tasks", "Settings").getOrElse(drawerTab) { "" }
            textSize = 22f; setTypeface(null, Typeface.BOLD); setTextColor(onSurfaceColor)
        })
        column.addView(View(this).apply {}, LinearLayout.LayoutParams(1, dp(22)))

        when (drawerTab) {
            0 -> column.addView(buildProjectsTab())
            1 -> column.addView(buildTasksTab())
            2 -> column.addView(buildSettingsTab())
        }
    }

    // Projects section content: Add Project button + the project list (opens directly).
    private fun buildProjectsTab(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // The Projects section opens straight into the list — no extra tap on a
        // "Projects" toggle. Add Project always sits at the top.
        col.addView(drawerButton("＋ Add Project") { closeDrawer(); showAddProject() })
        if (jobSites.isEmpty()) {
            col.addView(TextView(this).apply { text = "No projects yet. Add one."; textSize = 14f; setTextColor(onSurfaceVariantColor); setPadding(0, dp(6), 0, dp(6)) })
        }
        jobSites.sortedBy { it.name }.forEach { site ->
            col.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = card()
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
                if (!site.employer.isNullOrBlank()) subParts.add(site.employer)
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
        return col
    }

    // M3 filled button: full pill shape, primary fill, on-primary label in sentence
    // case at the M3 label-large size (14sp), 40dp minimum height.
    private fun drawerButton(textVal: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = textVal; textSize = 14f; setTypeface(null, Typeface.BOLD)
        letterSpacing = 0.01f
        setTextColor(0xFFFFFFFF.toInt()); gravity = Gravity.CENTER
        background = rounded(primaryColor, 20)
        setMinimumHeight(dp(44))
        setPadding(0, dp(12), 0, dp(12))
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
        val employer = EditText(this).apply {
            hint = "Employer / client"; textSize = 18f
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
        employer.layoutParams = LinearLayout.LayoutParams(dp(320), dp(56))
        employer.setMargins(0, dp(10), 0, 0)
        wage.layoutParams = LinearLayout.LayoutParams(dp(320), dp(56))
        wage.setMargins(0, dp(10), 0, 0)
        wrap.addView(name)
        wrap.addView(employer)
        wrap.addView(wage)
        AlertDialog.Builder(this, pickerDialogThemeId())
            .setTitle("Add Project")
            .setView(wrap)
            .setPositiveButton("Add") { _, _ ->
                if (name.text.toString().isNotBlank())
                    addSite(name.text.toString().trim(), "", employer.text.toString().trim(), wage.text.toString().trim())
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
        val employer = EditText(this).apply { setText(site.employer ?: ""); setTextColor(onSurfaceColor); inputType = InputType.TYPE_CLASS_TEXT }
        val wage = EditText(this).apply { setText(site.hourlyWage ?: ""); setTextColor(onSurfaceColor); inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL }
        val nameLbl = TextView(this).apply { text = "Name"; textSize = 12f; setTextColor(onSurfaceVariantColor) }
        val locLbl = TextView(this).apply { text = "Location"; textSize = 12f; setTextColor(onSurfaceVariantColor) }
        val employerLbl = TextView(this).apply { text = "Employer / client"; textSize = 12f; setTextColor(onSurfaceVariantColor) }
        val wageLbl = TextView(this).apply { text = "Hourly wage ($)"; textSize = 12f; setTextColor(onSurfaceVariantColor) }
        AlertDialog.Builder(this, pickerDialogThemeId())
            .setTitle("Edit Project")
            .setView(fieldColumn(nameLbl, name, locLbl, loc, employerLbl, employer, wageLbl, wage))
            .setPositiveButton("Save") { _, _ ->
                if (name.text.toString().isNotBlank())
                    renameSite(site.id, name.text.toString().trim(), loc.text.toString(), employer.text.toString().trim(), wage.text.toString().trim())
                else statusMessage = "Project name can't be empty"
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // Stop-timer job picker: a roomy dialog with one card per project (name, rate,
    // hours booked so far) instead of a cramped list of plain rows.
    private fun showStopPicker() {
        if (jobSites.isEmpty()) {
            AlertDialog.Builder(this, pickerDialogThemeId())
                .setTitle("Which job were you working on?")
                .setMessage("No projects yet. Add one first.")
                .setNegativeButton("Close") { _, _ -> if (clockPaused) clockPaused = false; renderAll() }
                .show()
            return
        }
        lateinit var dlg: AlertDialog
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        jobSites.sortedBy { it.name }.forEach { site ->
            col.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = card()
                setPadding(dp(20), dp(20), dp(20), dp(20))
                isClickable = true
                setOnClickListener { dlg.dismiss(); stopClock(site.id) }
            }.also { row ->
                val siteSessions = sessions.filter { it.jobSiteId == site.id }
                val totalMin = siteSessions.sumOf { workedMinutes(it) }
                val wageVal = site.hourlyWage?.trim()?.toDoubleOrNull()

                val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                info.addView(TextView(this).apply {
                    text = site.name; textSize = 17f; setTypeface(null, Typeface.BOLD); setTextColor(onSurfaceColor)
                })
                val sub = mutableListOf<String>()
                if (!site.employer.isNullOrBlank()) sub.add(site.employer)
                if (!site.location.isNullOrBlank()) sub.add(site.location)
                if (!site.hourlyWage.isNullOrBlank()) sub.add("\$${site.hourlyWage}/hr")
                info.addView(TextView(this).apply {
                    text = if (sub.isEmpty()) "Tap to book this session" else sub.joinToString(" · ")
                    textSize = 13f; setTextColor(onSurfaceVariantColor)
                })
                row.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

                val totals = mutableListOf<String>()
                totals.add(formatMinutesShort(totalMin))
                if (wageVal != null) totals.add("\$${String.format(Locale.US, "%.2f", wageVal * totalMin / 60.0)}")
                row.addView(TextView(this).apply {
                    text = totals.joinToString("\n"); textSize = 14f; gravity = Gravity.END
                    setTypeface(null, Typeface.BOLD); setTextColor(primaryColor)
                })
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(12)
            })
        }

        dlg = AlertDialog.Builder(this, pickerDialogThemeId())
            .setTitle("Which job were you working on?")
            .setView(col)
            .setNegativeButton("Cancel") { _, _ -> if (clockPaused) clockPaused = false; renderAll() }
            .create()
        dlg.show()
        // Roomy picker: wider than a default alert and tall enough for the cards.
        dlg.window?.setLayout(dp(360), ViewGroup.LayoutParams.WRAP_CONTENT)
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