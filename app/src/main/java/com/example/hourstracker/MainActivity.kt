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
import android.widget.ImageView
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
    // The job the user is currently working on. Persisted; stays until the user
    // changes it. Stopping the timer books the session to this job directly.
    private var activeJobId = -1
    // Persisted wall-clock state so the clock keeps accruing even if the
    // process is killed. segmentStartMs = epoch of the current running segment
    // (0 when paused/stopped); accumulatedMs = time banked from earlier segments.
    // pausedAccumMs/pauseStartedMs carry the paused spans, which get booked as the
    // task's break instead of counting as work. All of it is owned by Clock.
    private var segmentStartMs = 0L
    private var accumulatedMs = 0L
    private var pausedAccumMs = 0L
    private var pauseStartedMs = 0L

    // Signature of the clock/job/session state the CURRENT layout was built from.
    // The home-screen widget writes prefs directly (it never opens the app), so
    // onResume() compares this to the freshly-restored state to decide whether a
    // full re-render is needed or a plain tick will do.
    private var builtStateSig = ""
    private var statusMessage = ""
    private var drawerTab = 0 // menu selection: 0 = Projects, 1 = Tasks, 2 = Settings
    private var exportOpen = false // whether the drawer's Export group is expanded
    private var payOpen = false // whether the Settings pay-period preset menu is expanded
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
    private var labelView: TextView? = null
    private var stopBtnView: View? = null
    private var activeJobView: TextView? = null
    // Report card handles so updateClockViews() can repaint live totals while the
    // timer runs (week / pay period hours + pay).
    private var weekTotalsView: TextView? = null
    private var weekPayView: TextView? = null
    private var periodTotalsView: TextView? = null
    private var periodPayView: TextView? = null

    // Built-in project name for clocking drive time (see ensureDrivingProject).
    private val DRIVING_NAME = "Driving"

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
        repairJunkSessions()
        refreshData()
        buildLayout()
        startTicker()
        // Re-surface the running/paused notification after process death.
        syncTimerNotification()
        // The home-screen widget's Stop button opens the app with CMD_STOP: stop
        // the running clock, booking the session to the active job (no picker).
        if (getIntent()?.action == "com.example.hourstracker.CMD_STOP" && clockRunning) {
            if (activeSite() == null) {
                // Pause while the picker is up so the wait isn't silently worked.
                Clock.pause(prefs)
                restoreClockState()
                showJobPicker("Which job are you working on?") { s -> stopClock(s.id) }
            } else {
                stopClock(activeJobId)
            }
        }
        // The widget's Start button when no active job is set: open the app so the
        // user can choose the job; then start the clock.
        if (getIntent()?.action == "com.example.hourstracker.CMD_START" && !clockRunning) {
            val site = activeSite()
            if (site == null) {
                showJobPicker("Which job are you working on?") { s ->
                    activeJobId = s.id
                    persistClock()
                    startClockFromIdle()
                }
            } else {
                startClockFromIdle()
            }
        }
        // The widget's Start/Pause toggle: route through the app (the widget's
        // own broadcast intent never delivered on this host). Same logic as the
        // halo button — start/pause/resume the running clock.
        if (getIntent()?.action == "com.example.hourstracker.CMD_TOGGLE") {
            mainHandler.post { try { onHaloTap() } catch (t: Throwable) { } }
        }
    }

    // A widget resume (CMD_TOGGLE) with FLAG_ACTIVITY_CLEAR_TOP lands here when the
    // activity already exists — re-dispatch so the "Enter break time" dialog still
    // appears instead of being swallowed.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent?.action == "com.example.hourstracker.CMD_TOGGLE") {
            mainHandler.post { try { onHaloTap() } catch (t: Throwable) { } }
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

    // Back walks the stack: section → menu → home.
    //  - Menu open (regardless of what's under it) → back = Home.
    //  - In a section (Projects/Tasks/Settings) → back = reopen the menu.
    //  - In a range-submenu (This week / pay period) → back = Home.
    //  - Otherwise → platform handles it.
    override fun onBackPressed() {
        when {
            drawerOpen -> { closeDrawer(); navScreen = 0; renderAll() }
            navScreen == 1 || navScreen == 2 || navScreen == 3 -> openDrawer()
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

    private val bgColor get() = if (isDark) 0xFF0B0F17.toInt() else 0xFFF8FAFC.toInt()
    private val surfaceColor get() = bgColor
    private val surfaceVariantColor get() = if (isDark) 0xFF1E293B.toInt() else 0xFFF1F5F9.toInt()
    private val onSurfaceColor get() = if (isDark) 0xFFF8FAFC.toInt() else 0xFF0F172A.toInt()
    private val onSurfaceVariantColor get() = if (isDark) 0xFF94A3B8.toInt() else 0xFF64748B.toInt()
    private val primaryColor get() = if (isDark) 0xFF10B981.toInt() else 0xFF059669.toInt()
    private val primaryContainerColor get() = if (isDark) 0xFF064E3B.toInt() else 0xFFD1FAE5.toInt()
    private val onPrimaryContainerColor get() = if (isDark) 0xFF6EE7B7.toInt() else 0xFF065F46.toInt()
    private val errorColor get() = if (isDark) 0xFFF87171.toInt() else 0xFFDC2626.toInt()
    /** Surface-container tone — used for card fills and the status strip. */
    private val surfaceContainerColor get() = if (isDark) 0xFF131A26.toInt() else 0xFFFFFFFF.toInt()
    /** Outline — used for card borders and hairline dividers. */
    private val outlineColor get() = if (isDark) 0xFF1E293B.toInt() else 0xFFE2E8F0.toInt()
    // Top band backdrop keeps clean contrast in both modes.
    private val appBarColor get() = if (isDark) surfaceContainerColor else surfaceVariantColor

    private fun parseHex(hex: String): Int {
        val h = hex.removePrefix("#").trim()
        return if (h.length == 6) {
            try {
                Color.rgb(h.substring(0, 2).toInt(16), h.substring(2, 4).toInt(16), h.substring(4, 6).toInt(16))
            } catch (e: Exception) { 0xFF059669.toInt() }
        } else 0xFF059669.toInt()
    }

    // Dialog theme so the native date/time pickers match the app UI accent colors.
    private fun pickerDialogThemeId(): Int =
        if (isDark) R.style.PickerDialogThemeDark else R.style.PickerDialogTheme

    // Every picker menu (job, project actions, session form, theme, export range)
    // is sized to match the Add Project dialog so they all feel the same. The
    // Add Project fields are 320dp wide inside 28dp side padding, so content is
    // 376dp — that's the anchor all other pickers copy. Lazy because dp() needs
    // resources, which isn't ready at construction time.
    private val PICKER_DIALOG_WIDTH: Int by lazy { dp(376) }

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

    // ==================== DATA ====================

    override fun onResume() {
        super.onResume()
        // The home-screen widget writes timer state straight to SharedPreferences
        // and never opens the app. Re-read it every time we return to the
        // foreground so widget-driven Start/Pause/Stop shows up immediately
        // instead of only after a cold start (clearing the app from recents).
        val wasSig = builtStateSig
        restoreClockState()
        refreshData()
        if (stateSig() != wasSig) {
            renderAll()
            if (drawerOpen) openDrawer()
        } else {
            updateClockViews()
            syncTimerNotification()
        }
    }

    // State that affects how the screen is built: clock run/pause, the active
    // job, and how many sessions exist (a widget Stop adds one).
    private fun stateSig(): String = "$clockRunning|$clockPaused|$activeJobId|${sessions.size}"

    private fun refreshData() {
        ensureDrivingProject()
        jobSites = querySites()
        sessions = querySessions()
    }

    // The built-in "Driving" project: always present so it appears in the job
    // picker (clock driving time like any other project, eligible for overtime).
    // Its wage is set from the drawer (Driving → hourly rate); leave it blank/0 to
    // track driving hours without charging. Re-created from nothing if removed.
    private fun ensureDrivingProject() {
        val drv = db.writableDatabase
        val exists = try {
            drv.rawQuery("SELECT id FROM job_sites WHERE name=? LIMIT 1", arrayOf(DRIVING_NAME)).use { c -> c.moveToFirst() }
        } catch (t: Throwable) { false }
        if (!exists) {
            try {
                val cv = android.content.ContentValues()
                cv.put("name", DRIVING_NAME)
                cv.put("location", "Commute")
                cv.put("color", "#0284C7")
                drv.insert("job_sites", null, cv)
            } catch (t: Throwable) { }
        }
    }

    // One-time repair for rows older builds could write with a blank start/end
    // (they crashed every screen that totalled them). Two columns must hold
    // exactly "HH:MM"; anything else is unreadable noise, so drop it.
    private fun repairJunkSessions() {
        try {
            db.writableDatabase.delete(
                "work_sessions",
                "start_time NOT LIKE '__:__' OR end_time NOT LIKE '__:__' OR date NOT LIKE '____-__-__'",
                null
            )
        } catch (e: Exception) {
            // Non-fatal: worst case the tolerant readers above keep the app usable.
        }
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
                    color = c.getString(c.getColumnIndexOrThrow("color")),
                    driveMinutes = c.getColumnIndex("drive_minutes").let { idx ->
                        if (idx >= 0 && !c.isNull(idx)) c.getInt(idx) else 0
                    }
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

    private fun addSite(name: String, location: String = "", employer: String = "", wage: String? = null, driveMinutes: Int = 0) {
        val cv = android.content.ContentValues()
        cv.put("name", name)
        cv.put("location", if (location.isBlank()) null else location)
        cv.put("employer", if (employer.isBlank()) null else employer)
        cv.put("hourly_wage", parseWage(wage))
        cv.put("color", "#059669")
        cv.put("drive_minutes", driveMinutes)
        db.writableDatabase.insert("job_sites", null, cv)
        refreshData(); renderAll()
    }

    private fun renameSite(id: Int, name: String, location: String, employer: String = "", wage: String? = null, driveMinutes: Int = 0) {
        android.content.ContentValues().apply {
            put("name", name)
            put("location", if (location.isBlank()) null else location)
            put("employer", if (employer.isBlank()) null else employer)
            put("hourly_wage", parseWage(wage))
            put("drive_minutes", driveMinutes)
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
    // A picker-preset (this week / this month / last month / bi-weekly / custom)
    // set in Settings → Pay period, resolved to a concrete [start, end] ISO range
    // (inclusive) for the "This pay period" card on the main screen. Bi-weekly
    // (the default) is the current week + the following week (14 days).
    private val payPreset: String
        get() {
            prefs.getString("pay_preset", null)?.let { return it }
            // First run / upgrade: honour an existing hand-picked range instead
            // of silently switching it to the bi-weekly default.
            val s = prefs.getString("pay_start", null)
            val e = prefs.getString("pay_end", null)
            return if (s != null && e != null) "custom" else "biweekly"
        }
    private val payPresetLabel: String
        get() = when (payPreset) {
            "thisweek" -> "This week"
            "thismonth" -> "This month"
            "lastmonth" -> "Last month"
            "custom" -> "Custom"
            else -> "Bi-weekly"
        }
    // Anchor date for the recurring bi-weekly cycle (defaults to this week's Sunday).
    private val biweeklyStart: String
        get() = prefs.getString("biweekly_start", null)
            ?: sundayOf(LocalDate.now().toString())
    // Header label for the picker row: bi-weekly shows its anchor date.
    private val payHeaderLabel: String
        get() = if (payPreset == "biweekly")
            "Bi-weekly · starts ${isoDateDisplay(biweeklyStart)}"
        else payPresetLabel
    private fun resolvedPayRange(): Pair<String, String> {
        if (payPreset == "custom") {
            val s = prefs.getString("pay_start", null)
            val e = prefs.getString("pay_end", null)
            if (s != null && e != null) return s to e
        }
        val today = LocalDate.now()
        val sun = sundayOf(today.toString())
        val sunD = LocalDate.parse(sun)
        return when (payPreset) {
            "thisweek" -> sun.toString() to sunD.plusDays(6).toString()
            "lastweek" -> sunD.minusWeeks(1).toString() to sunD.minusDays(1).toString()
            "thismonth" -> today.withDayOfMonth(1).toString() to today.withDayOfMonth(today.lengthOfMonth()).toString()
            "lastmonth" -> {
                val first = today.withDayOfMonth(1).minusMonths(1)
                first.toString() to first.withDayOfMonth(first.lengthOfMonth()).toString()
            }
            else -> {
                // Bi-weekly: a recurring 14-day cycle anchored on a user-picked
                // date. The current period is whichever aligned window (anchor +
                // 14k) contains today. Defaults to this week's Sunday.
                val anchor = try { LocalDate.parse(biweeklyStart) } catch (e: Exception) { LocalDate.now() }
                val days = java.time.temporal.ChronoUnit.DAYS.between(anchor, today)
                val start = anchor.plusDays(Math.floorDiv(days, 14L) * 14)
                start.toString() to start.plusDays(13).toString()
            }
        }
    }
    private val payPeriodStart: String
        get() = resolvedPayRange().first
    private val payPeriodEnd: String
        get() = resolvedPayRange().second


    // Compute overtime + pay across all jobs for any inclusive [from, to] ISO range.
    // Overtime is applied per calendar week (weeks start Sunday) using the global
    // weekly threshold; each job's hours are priced at that job's own wage.
    private fun rangeSummary(from: String, to: String, extra: List<WorkSession> = emptyList()): Quad {
        val saved = sessions.filter { it.date >= from && it.date <= to }
        val inRange = saved + extra.filter { it.date >= from && it.date <= to }

        // Auto drive time: credited once per day using the longest drive among the
        // projects worked that day (the commute), attributed to that project. Skipped
        // when the ONLY project worked that day is the built-in "Driving" catch-all —
        // there the commute was clocked manually, so auto-adding it would double-count.
        val drive = autoDriveByDay(inRange)
        var totalMin = inRange.sumOf { workedMinutes(it) } + drive.values.sumOf { it.second }

        val thresholdSec = (overtimeThresholdHours * 60).toInt()
        var otMin = 0
        var basePay = 0.0
        var otPay = 0.0
        // Each calendar week gets its own threshold, so a multi-week pay period
        // earns overtime the same way it would week by week. Auto-drive minutes are
        // folded into a week's total (so they can push it past the threshold and the
        // drive's own OT share is priced at the owning project's wage).
        inRange.groupBy { sundayOf(it.date) }.forEach { (_, weekSessions) ->
            val weekDates = weekSessions.map { it.date }.toSet()
            val siteWork = HashMap<Int, Int>()
            weekSessions.forEach { s ->
                siteWork[s.jobSiteId] = (siteWork[s.jobSiteId] ?: 0) + workedMinutes(s)
            }
            // Attribute this week's drive minutes to their owning projects/dates.
            val siteDrive = HashMap<Int, Int>()
            var weekDrive = 0
            drive.forEach { (date, pair) ->
                if (date in weekDates) {
                    siteDrive[pair.first] = (siteDrive[pair.first] ?: 0) + pair.second
                    weekDrive += pair.second
                }
            }
            val weekMin = siteWork.values.sum() + weekDrive
            val weekOt = (weekMin - minOf(weekMin, thresholdSec)).coerceAtLeast(0)
            otMin += weekOt
            if (weekMin > 0) {
                // Include any site that either worked or earned auto-drive this week.
                (siteWork.keys + siteDrive.keys).forEach { siteId ->
                    val siteMin = (siteWork[siteId] ?: 0) + (siteDrive[siteId] ?: 0)
                    if (siteMin > 0) {
                        val site = jobSites.find { it.id == siteId } ?: return@forEach
                        val wage = site.hourlyWage?.trim()?.toDoubleOrNull() ?: return@forEach
                        // The week's overtime is job-agnostic, so split it across the
                        // projects (work + their drive) in proportion to their minutes,
                        // then price each project's share at its own wage.
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

    /**
     * Maps each worked date to the auto-drive credit for that day: the longest drive
     * among the projects worked that day (the {siteId, minutes} pair). Not credited
     * when the ONLY project that day is the built-in "Driving" catch-all — there the
     * commute was clocked manually, so auto-adding it would double-count.
     */
    private fun autoDriveByDay(inRange: List<WorkSession>): Map<String, Pair<Int, Int>> {
        val drivingId = jobSites.find { it.name == DRIVING_NAME }?.id
        val result = HashMap<String, Pair<Int, Int>>()
        inRange.groupBy { it.date }.forEach { (date, daySessions) ->
            val workedIds = daySessions.map { it.jobSiteId }.distinct()
            val onlyDriving = drivingId != null && workedIds.size == 1 && workedIds.single() == drivingId
            if (onlyDriving) return@forEach
            val top = workedIds
                .mapNotNull { id -> jobSites.find { it.id == id } }
                .filter { (it.driveMinutes ?: 0) > 0 }
                .maxByOrNull { it.driveMinutes ?: 0 }
            if (top != null) result[date] = top.id to (top.driveMinutes ?: 0)
        }
        return result
    }

    // Simple 4-value holder (keeps rangeSummary readable).
    private data class Quad(val totalMin: Int, val otMin: Int, val basePay: Double, val otPay: Double)

    /**
     * Range totals including the currently-running timer's in-progress minutes.
     * The running session isn't in the DB until it's stopped, so it's materialised
     * as a synthetic session and folded through the same per-week overtime math as
     * saved sessions — so the live portion earns OT too (once the week breaches
     * the threshold) and is priced at the active job's wage. Used to repaint the
     * home report cards as the clock ticks.
     */
    private fun liveRangeSummary(from: String, to: String): Pair<Int, Double> {
        val live = buildList {
            if (clockRunning && activeJobId >= 0) {
                val today = LocalDate.now().toString()
                if (today >= from && today <= to) {
                    val liveMin = (elapsedMs() / 60_000).toInt()
                    if (liveMin > 0) add(makeLiveSession(activeJobId, today, liveMin))
                }
            }
        }
        val q = rangeSummary(from, to, live)
        return q.totalMin to (q.basePay + q.otPay)
    }

    // A bare-bones session whose workedMinutes() (00:00 → liveMin minutes, no
    // break) equals the timer's current elapsed minutes, for the active job today.
    private fun makeLiveSession(jobId: Int, date: String, minutes: Int): WorkSession {
        val endSec = (minutes * 60) % (24 * 3600)
        return WorkSession(
            jobSiteId = jobId,
            date = date,
            startTime = "00:00",
            endTime = String.format(Locale.US, "%02d:%02d", endSec / 3600, (endSec % 3600) / 60),
            breakMinutes = 0
        )
    }

    /** Tasks belonging to a project. Used by the delete confirmation and cleanup. */
    private fun sessionsFor(id: Int): List<WorkSession> = sessions.filter { it.jobSiteId == id }

    /** Says what actually happens to this project's tasks, count included. */
    private fun deleteSiteMessage(id: Int): String {
        val n = sessionsFor(id).size
        val tasks = "$n task${if (n == 1) "" else "s"}"
        return if (n == 0)
            "The project is removed. It has no tasks."
        else
            "The project is removed and its $tasks are deleted with it. This can't be undone."
    }

    // Deleting a project takes its tasks with it. Previously the rows stayed
    // behind with a job_site_id pointing at nothing: they still counted toward
    // the week/pay-period totals, exported as "Unknown" and could not be cleaned
    // up from the UI at all.
    private fun deleteSite(id: Int) {
        val site = jobSites.find { it.id == id }
        val removed = sessionsFor(id).size
        db.writableDatabase.delete("work_sessions", "job_site_id=?", arrayOf(id.toString()))
        db.writableDatabase.delete("job_sites", "id=?", arrayOf(id.toString()))
        // Don't leave the clock pointed at a project that no longer exists.
        if (activeJobId == id) {
            activeJobId = -1
            persistClock()
        }
        val label = site?.name ?: "project"
        statusMessage = if (removed > 0)
            "Deleted \"$label\" and $removed task${if (removed == 1) "" else "s"}"
        else
            "Deleted \"$label\""
        refreshData(); renderAll()
    }

    private fun insertSession(session: WorkSession) {
        if (!isBookable(session)) return
        val cv = android.content.ContentValues()
        cv.put("job_site_id", session.jobSiteId)
        cv.put("date", session.date)
        cv.put("start_time", session.startTime)
        cv.put("end_time", session.endTime)
        cv.put("break_minutes", clampedBreak(session))
        cv.put("notes", session.notes)
        val rowId = db.writableDatabase.insert("work_sessions", null, cv)
        if (rowId == -1L) {
            // Don't claim success on a failed insert, and don't touch the export.
            statusMessage = "Could not save that task"
            renderAll()
            return
        }
        refreshData()
        // No file is written here. Saving a task used to rewrite the project's
        // .xlsx on the main thread; the database is the record of truth and
        // backups (.json) are on demand, so a save is now purely a DB write.
        statusMessage = "Saved ✓ ${isoDateDisplay(session.date)} ${time12(session.startTime)}-${time12(session.endTime)}"
        renderAll()
    }

    private fun updateSession(session: WorkSession) {
        if (!isBookable(session)) return
        val cv = android.content.ContentValues().apply {
            put("job_site_id", session.jobSiteId)
            put("date", session.date)
            put("start_time", session.startTime)
            put("end_time", session.endTime)
            put("break_minutes", clampedBreak(session))
            put("notes", session.notes)
        }
        val rows = db.writableDatabase.update("work_sessions", cv, "id=?", arrayOf(session.id.toString()))
        if (rows < 1) {
            // The task is gone (deleted elsewhere) — say so instead of reporting success.
            statusMessage = "That task no longer exists"
            refreshData(); renderAll()
            return
        }
        refreshData()
        statusMessage = "Updated ✓ ${isoDateDisplay(session.date)}"
        renderAll()
    }

    private fun deleteSession(session: WorkSession) {
        db.writableDatabase.delete("work_sessions", "id=?", arrayOf(session.id.toString()))
        refreshData()
        statusMessage = "Deleted ✓"
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
        Clock.write(prefs, Clock.State(
            running = clockRunning,
            paused = clockPaused,
            startedAt = startedAt,
            segmentStartMs = segmentStartMs,
            accumulatedMs = accumulatedMs,
            pausedAccumMs = pausedAccumMs,
            pauseStartedMs = pauseStartedMs,
            activeJobId = activeJobId
        ))
    }

private fun restoreClockState() {
        val s = Clock.read(prefs)
        clockRunning = s.running
        clockPaused = s.paused
        startedAt = s.startedAt
        segmentStartMs = s.segmentStartMs
        accumulatedMs = s.accumulatedMs
        pausedAccumMs = s.pausedAccumMs
        pauseStartedMs = s.pauseStartedMs
        activeJobId = s.activeJobId
    }

private fun elapsedMs(): Long = Clock.elapsedMs(prefs)

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
                // Pick the active job first (stays until the user changes it), then start.
                val site = activeSite()
                if (site == null) {
                    showJobPicker("Which job are you working on?") { s ->
                        activeJobId = s.id
                        persistClock()
                        startClockFromIdle()
                    }
                    return
                }
                startClockFromIdle()
            }
            clockPaused -> { resumeWithEditablePause(); return }
            else -> Clock.pause(prefs)
        }
        if (clockRunning) {
            // Clock.pause/resume wrote the posted values; mirror them back into the
            // fields the ticker renders from.
            restoreClockState()
        }
        syncTimerNotification()
        renderAll()
    }

// Start the clock from idle, then persist/notify/render. Used both by the
// direct halo tap and the picker callback (which returns early from onHaloTap).
private fun startClockFromIdle() {
        Clock.start(prefs, activeJobId)
        restoreClockState()
        syncTimerNotification()
        renderAll()
    }

// Stop the timer and book the session to the active job (no job picker).
private fun stopToActiveJob() {
    val site = activeSite()
    if (site == null) { showJobPicker("Which job are you working on?") { s -> stopClock(s.id) }; return }
    stopClock(site.id)
}

// Resume from a pause, but first let the user edit how many minutes of this
// pause count as break. Paused time is excluded from worked time and later
// booked as the task's break, so an accidental long pause can be corrected by
// overwriting the span's length before it's banked.
private fun resumeWithEditablePause() {
    val livePauseMs = if (pauseStartedMs != 0L) System.currentTimeMillis() - pauseStartedMs else 0L
    val currentMin = (livePauseMs / 60_000L).toInt()
    val inp = EditText(this).apply {
        inputType = android.text.InputType.TYPE_CLASS_NUMBER
        setText("$currentMin")
        setSelectAllOnFocus(true)
    }
    val wrap = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(6), dp(20), 0)
    }
    wrap.addView(TextView(this).apply {
        text = "Paused ${formatElapsedMs(livePauseMs)} so far. Enter the break time in minutes, then resume."
        textSize = 13f; setTextColor(onSurfaceVariantColor)
    })
    wrap.addView(inp, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(14)
    })
    AlertDialog.Builder(this, pickerDialogThemeId())
        .setTitle("Enter break time")
        .setView(wrap)
        .setNegativeButton("Cancel", null)
        .setPositiveButton("Resume") { _, _ ->
            val edited = (inp.text.toString().trim().toIntOrNull() ?: currentMin).coerceAtLeast(0)
            Clock.resume(prefs, edited)
            restoreClockState()
            syncTimerNotification()
            renderAll()
        }
        .show()
}

// Change the active job (the one you're working on); persists until changed.
private fun setActiveJob(site: JobSite) {
    activeJobId = site.id
    persistClock()
    renderAll()
}

private fun stopClock(jobSiteId: Int) {
        // Clock.stop hands back the reserved session: start, end and the paused
        // minutes, which are booked as the task's break. Paused time used to be
        // dropped entirely, so stopping after a pause claimed every paused minute
        // as work even though the clock never counted it.
        val booked = Clock.stop(prefs)
        restoreClockState()
        val today = LocalDate.now().toString()
        insertSession(WorkSession(
            id = 0,
            jobSiteId = jobSiteId,
            date = today,
            startTime = booked.startedAt,
            endTime = booked.endTime,
            breakMinutes = booked.breakMinutes,
            notes = "clock"
        ))
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
        // Remember what this layout was built from so onResume() can detect
        // widget-driven changes and rebuild only when needed.
        builtStateSig = stateSig()
    }

    // Home screen (Direction C: Modern Tonal Stack): header + hero card + summary cards.
    private fun buildHomeScreen(column: LinearLayout) {
        val today = LocalDate.now()
        val dayName = today.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }
        val monthShort = today.month.name.lowercase().take(3).replaceFirstChar { it.uppercase() }

        // ---- Header ----
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "$dayName · $monthShort ${today.dayOfMonth}".uppercase()
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            letterSpacing = 0.05f
            setTextColor(primaryColor)
        })
        header.addView(TextView(this).apply {
            text = "Hours Tracker"
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
            setTextColor(onSurfaceColor)
            setPadding(0, dp(2), 0, 0)
        })
        column.addView(header, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(16) })

        // ---- Hero Card (Active Session & Controls) ----
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = card()
            setPadding(dp(20), dp(18), dp(20), dp(18))
        }

        // Status badge pill
        val badge = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(primaryContainerColor, 8)
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }
        val dot = dotView(primaryColor, 6)
        badge.addView(dot)
        val stateLbl = TextView(this).apply {
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            letterSpacing = 0.04f
            setTextColor(onPrimaryContainerColor)
            setPadding(dp(6), 0, 0, 0)
        }
        stateView = stateLbl
        badge.addView(stateLbl)
        hero.addView(badge, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(10) })

        // Active project / job title
        val jobLbl = TextView(this).apply {
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(onSurfaceColor)
            setOnClickListener { showJobPicker("Which job are you working on?") { s -> setActiveJob(s) } }
        }
        activeJobView = jobLbl
        hero.addView(jobLbl, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(2) })

        // Big elapsed clock display
        val elapsed = TextView(this).apply {
            textSize = 46f
            setTypeface(null, Typeface.BOLD)
            setTextColor(onSurfaceColor)
            letterSpacing = -0.02f
        }
        elapsedView = elapsed
        hero.addView(elapsed, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(14) })

        // Action controls inside hero card: [Pause / Start / Resume] [Stop] [＋ Add Task]
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val pauseBtn = TextView(this).apply {
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = rounded(primaryColor, 23)
            isClickable = true
            setOnClickListener { onHaloTap() }
        }
        labelView = pauseBtn
        actions.addView(pauseBtn, LinearLayout.LayoutParams(0, dp(46), 1f))

        val stopBg = if (isDark) 0x33EF4444.toInt() else 0xFFFEE2E2.toInt()
        val stopBtn = TextView(this).apply {
            text = "■ Stop"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(errorColor)
            gravity = Gravity.CENTER
            background = rounded(stopBg, 23)
            isClickable = true
            setOnClickListener {
                clockPaused = true
                stopToActiveJob()
            }
        }
        stopBtnView = stopBtn
        actions.addView(stopBtn, LinearLayout.LayoutParams(0, dp(46), 1f).apply { leftMargin = dp(10) })

        val addBtn = TextView(this).apply {
            text = "＋"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(onSurfaceColor)
            gravity = Gravity.CENTER
            background = rounded(surfaceVariantColor, 23)
            isClickable = true
            setOnClickListener { showAddSession() }
        }
        actions.addView(addBtn, LinearLayout.LayoutParams(dp(46), dp(46)).apply { leftMargin = dp(10) })

        hero.addView(actions, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        column.addView(hero, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(16) })

        updateClockViews()

        // ---- status message ----
        if (statusMessage.isNotEmpty()) {
            val st = TextView(this).apply { text = statusMessage; textSize = 12f; setTextColor(onSurfaceVariantColor) }
            column.addView(st, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) })
        }

        // ---- section title ----
        column.addView(TextView(this).apply {
            text = "REPORTS"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            letterSpacing = 0.05f
            setTextColor(onSurfaceVariantColor)
            setPadding(dp(2), 0, 0, dp(8))
        })

        // ---- summaries on the main screen ----
        column.addView(buildWeeklySummaryCard(), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(10)
        })
        column.addView(buildPayPeriodCard(), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(24)
        })
    }

    private var drawerScrim: View? = null
    private var drawerPanel: View? = null

    private fun updateClockViews() {
        stateView?.text = when {
            !clockRunning -> "TIMER IDLE"
            clockPaused -> "TIMER PAUSED"
            else -> "TIMER RUNNING"
        }
        activeJobView?.text = activeSite()?.name ?: "Select job"
        labelView?.text = when {
            !clockRunning -> "▶ Start"
            clockPaused -> "▶ Resume"
            else -> "⏸ Pause"
        }
        labelView?.background = when {
            !clockRunning -> rounded(primaryColor, 23)
            clockPaused -> rounded(if (isDark) 0xFFF59E0B.toInt() else 0xFFD97706.toInt(), 23)
            else -> rounded(primaryColor, 23)
        }
        labelView?.let { (it as? TextView)?.setTextColor(if (isDark) 0xFF064E3B.toInt() else Color.WHITE) }
        stopBtnView?.visibility = if (clockRunning) View.VISIBLE else View.GONE
        elapsedView?.let { tv ->
            tv.text = formatElapsedMs(elapsedMs())
            tv.setTextColor(if (clockPaused) onSurfaceVariantColor else onSurfaceColor)
        }
        // Repaint the home report totals to include the live in-progress time.
        if (navScreen == 0) updateReportViews()
    }

    // Refresh "This week" and "This pay period" card figures with live minutes.
    private fun updateReportViews() {
        if (weekTotalsView != null || weekPayView != null) {
            val sunday = sundayOf(LocalDate.now().toString())
            val (tm, p) = liveRangeSummary(sunday, LocalDate.parse(sunday).plusDays(6).toString())
            weekTotalsView?.text = formatMinutesShort(tm)
            weekPayView?.text = "$${String.format(Locale.US, "%.2f", p)}"
        }
        if (periodTotalsView != null || periodPayView != null) {
            val (tm, p) = liveRangeSummary(payPeriodStart, payPeriodEnd)
            periodTotalsView?.text = formatMinutesShort(tm)
            periodPayView?.text = "$${String.format(Locale.US, "%.2f", p)}"
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
                val taskMenuDlg = AlertDialog.Builder(this@MainActivity, pickerDialogThemeId())
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
                    .create()
                taskMenuDlg.window?.setLayout(PICKER_DIALOG_WIDTH, ViewGroup.LayoutParams.WRAP_CONTENT)
                taskMenuDlg.show()
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
            if (clampedBreak(session) > 0) body.addView(detailRow("Break", "${clampedBreak(session)} min"))
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
        private fun buildSummaryCard(title: String, from: String, to: String, onOpen: (() -> Unit)? = null, kind: Int = 0): View {
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
                setTypeface(null, Typeface.BOLD); setTextColor(primaryColor); gravity = Gravity.END
                if (kind == 1) weekTotalsView = this
                else if (kind == 2) periodTotalsView = this
            })
            valCol.addView(TextView(this).apply {
                text = "$${String.format(Locale.US, "%.2f", totalPay)}"
                textSize = 12f; setTextColor(onSurfaceVariantColor); gravity = Gravity.END
                if (kind == 1) weekPayView = this
                else if (kind == 2) periodPayView = this
            })
            card.addView(valCol, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            return card
        }

    // Home-screen card: this week (Sunday–Saturday).
    private fun buildWeeklySummaryCard(): View {
        val sunday = sundayOf(LocalDate.now().toString())
        val saturday = LocalDate.parse(sunday).plusDays(6).toString()
        return buildSummaryCard("This week", sunday, saturday, { navScreen = 4; renderAll() }, kind = 1)
    }

    // Home-screen card: the pay period range picked in Settings → Pay period.
    private fun buildPayPeriodCard(): View =
        buildSummaryCard("This pay period", payPeriodStart, payPeriodEnd, { navScreen = 5; renderAll() }, kind = 2)

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

        // ---- Pay period: inline preset menu (expands in place, no popup) ----
        col.addView(buildPayPeriodGroup())

        // ---- Appearance ----
        col.addView(settingsGroup("Appearance", listOf(
            settingsRowValue("Theme", themeLabel()) { toggleDark() }
        )))

        // ---- Storage ---- 
        col.addView(settingsGroup("Storage", listOf(
            settingsRowValue("Folder", ExportFile.rootLabel(this, prefs)) {
                infoDialog(
                    "Storage",
                    "Exports and backups save to:\n${ExportFile.rootLabel(this, prefs)}\n\n" +
                        "That's a normal folder under Download, visible in any file app " +
                        "and needing no special permission. Copy backups off this phone " +
                        "(share, Drive, email) so they survive losing the device."
                )
            }
        )))

        // ---- Backup / restore ----
        col.addView(settingsGroup("Backup & restore", listOf(
            settingsRowValue(
                "Automatic backup",
                if (prefs.getBoolean(AutoBackup.KEY_ENABLED, false)) "On" else "Off"
            ) { toggleAutoBackup() },
            settingsRowValue("Back up at", AutoBackup.displayTime(prefs)) { pickAutoBackupTime() },
            settingsRowValue("Back up now", lastBackupLabel()) { startBackup() },
            settingsRowValue("Restore from file", "Choose file") { confirmRestore() }
        )))
        col.addView(TextView(this).apply {
            text = "Backups save to:\n${ExportFile.rootLabel(this@MainActivity, prefs)}/Backup\nChecks, PDFs, and backups all live in this folder. Copy backups off this phone (share, Drive, email) so they survive losing the device."
            textSize = 11f; setTextColor(onSurfaceVariantColor); setPadding(dp(4), dp(8), dp(4), dp(8))
        })
        return col
    }

    /** Flip the daily backup. Always writes to the app's Backup folder. */
    private fun toggleAutoBackup() {
        val on = !prefs.getBoolean(AutoBackup.KEY_ENABLED, false)
        prefs.edit().putBoolean(AutoBackup.KEY_ENABLED, on).apply()
        AutoBackup.schedule(this, prefs)
        statusMessage = if (on) "Automatic backup on — daily at ${AutoBackup.displayTime(prefs)}"
        else "Automatic backup off"
        renderAll()
    }

    private fun pickAutoBackupTime() {
        val t = prefs.getString(AutoBackup.KEY_TIME, AutoBackup.DEFAULT_TIME) ?: AutoBackup.DEFAULT_TIME
        val p = t.split(":").mapNotNull { it.toIntOrNull() }
        TimePickerDialog(this, pickerDialogThemeId(), { _, h, m ->
            prefs.edit().putString(AutoBackup.KEY_TIME, String.format(Locale.US, "%02d:%02d", h, m)).apply()
            // Re-arm so the new time applies even mid-day.
            if (prefs.getBoolean(AutoBackup.KEY_ENABLED, false)) AutoBackup.schedule(this, prefs)
            statusMessage = "Daily backup set for ${AutoBackup.displayTime(prefs)}"
            renderAll()
        }, p.getOrElse(0) { 21 }, p.getOrElse(1) { 0 }, false).show()
    }

    /** "never" until the first backup, then the date the last one was written. */
    private fun lastBackupLabel(): String =
        Backup.prettyStamp(prefs.getString("last_backup_at", null))

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

    /**
     * Pay period group: a single row that expands an inline preset menu in place
     * (no popup, no full-page rebuild so scroll position is preserved). Picking a
     * preset updates the header text and collapses the menu. "Custom range…" is
     * the one path that still opens a date-picker popup for free-form dates.
     */
    private fun buildPayPeriodGroup(): View {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        wrap.addView(TextView(this).apply {
            text = "Pay period"; textSize = 12f; setTypeface(null, Typeface.BOLD)
            setTextColor(onSurfaceVariantColor); setPadding(dp(4), dp(16), 0, dp(6))
        })
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = card()
        }

        val labelTxt = TextView(this).apply {
            text = "Pay period"; textSize = 14f; setTextColor(onSurfaceColor)
        }
        val rangeTxt = TextView(this).apply {
            text = payHeaderLabel; textSize = 14f; setTypeface(null, Typeface.BOLD)
            setTextColor(primaryColor)
        }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(labelTxt)
        left.addView(rangeTxt)
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            isClickable = true
        }
        header.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(header)

        val menuBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun divider(): View = View(this).apply { setBackgroundColor(dividerColor) }

        // (re)build the preset options into menuBox; called each time it opens.
        fun buildMenu() {
            menuBox.removeAllViews()
            val opts = listOf(
                "This week" to "thisweek",
                "This month" to "thismonth",
                "Last month" to "lastmonth",
                "Bi-weekly (2 wks)" to "biweekly",
                "Custom range…" to "custom"
            )
            opts.forEachIndexed { i, (label, key) ->
                if (i > 0) menuBox.addView(divider(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
                val opt = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(14), dp(14), dp(14), dp(14))
                    isClickable = true
                    setOnClickListener {
                        // Custom still needs a free-form date, so keep the picker popup.
                        if (key == "custom") { showPayDatePicker(true); return@setOnClickListener }
                        val ed = prefs.edit()
                        ed.putString("pay_preset", key)
                        ed.remove("pay_start"); ed.remove("pay_end")
                        ed.apply()
                        rangeTxt.text = payHeaderLabel
                        box.removeView(menuBox); payOpen = false
                        statusMessage = "Pay period: $payHeaderLabel (${isoDateDisplay(payPeriodStart)} - ${isoDateDisplay(payPeriodEnd)})"
                    }
                }
                val active = payPreset == key
                opt.addView(TextView(this).apply {
                    text = label; textSize = 14f
                    setTextColor(if (active) onPrimaryContainerColor else onSurfaceColor)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                if (active) opt.addView(TextView(this).apply {
                    text = "✓"; textSize = 14f; setTypeface(null, Typeface.BOLD); setTextColor(primaryColor)
                })
                menuBox.addView(opt, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                // Picker sub-row sits directly under the Bi-weekly option (not after
                // Custom). Only shown while bi-weekly is the active preset.
                if (key == "biweekly" && payPreset == "biweekly") {
                    menuBox.addView(divider(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
                    val srow = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(20), dp(12), dp(14), dp(12))
                        isClickable = true
                        setOnClickListener {
                            val cur = try { LocalDate.parse(biweeklyStart) } catch (e: Exception) { LocalDate.now() }
                            DatePickerDialog(this@MainActivity, pickerDialogThemeId(), { _, y, mo, d ->
                                prefs.edit().putString("biweekly_start", LocalDate.of(y, mo + 1, d).toString()).apply()
                                rangeTxt.text = payHeaderLabel
                                box.removeView(menuBox); payOpen = false
                                statusMessage = "Bi-weekly starts ${isoDateDisplay(biweeklyStart)} (${isoDateDisplay(payPeriodStart)} - ${isoDateDisplay(payPeriodEnd)})"
                            }, cur.year, cur.monthValue - 1, cur.dayOfMonth).show()
                        }
                    }
                    srow.addView(TextView(this).apply {
                        text = "Starts on"; textSize = 14f
                        setTextColor(onSurfaceVariantColor)
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    srow.addView(TextView(this).apply {
                        text = isoDateDisplay(biweeklyStart); textSize = 14f
                        setTypeface(null, Typeface.BOLD); setTextColor(primaryColor)
                    })
                    menuBox.addView(srow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                }
            }
        }

        header.setOnClickListener {
            if (payOpen) {
                box.removeView(menuBox); payOpen = false
            } else {
                buildMenu()
                box.addView(menuBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                payOpen = true
            }
        }

        wrap.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return wrap
    }

    // Pick the pay period start/end date. The range is kept valid (start <= end)
    // by pushing the other bound when the two would cross.
    private fun showPayDatePicker(isStart: Boolean) {
        val cur = try {
            LocalDate.parse(if (isStart) payPeriodStart else payPeriodEnd)
        } catch (e: Exception) { LocalDate.now() }
        DatePickerDialog(this, pickerDialogThemeId(), { _, y, mo, d ->
            val ed = prefs.edit()
            ed.putString("pay_preset", "custom")
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
        // Panel surface = the elevated card tone, so the menu pills read as
        // M3-style tonal items sitting on a flat drawer.
        val frame = FrameLayout(this).apply { setBackgroundColor(surfaceContainerColor) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(22), dp(14), dp(20)) }
        frame.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // ---- header: logo chip + app title ----
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(TextView(this).apply {
            text = "H"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(0xFFFFFFFF.toInt())
            background = rounded(primaryColor, 11)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(dp(36), dp(36)))
        head.addView(TextView(this).apply {
            text = "Hours Tracker"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(onSurfaceColor)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(12)
        })
        col.addView(head, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(6)
        })

        // ---- section label ----
        fun sectionLabel(label: String) {
            col.addView(TextView(this).apply {
                text = label.uppercase()
                textSize = 10f
                setTypeface(null, Typeface.BOLD)
                setTextColor(onSurfaceColor)
                alpha = 0.45f
                letterSpacing = 0.12f
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(14); bottomMargin = dp(4)
            })
        }

        // ---- menu pill: icon chip + title + subtitle; active tab = tonal fill ----
        // Active state mirrors the M3 nav-drawer "selected" container: a filled tonal pill,
        // solid primary icon chip, and on-primary-container text. Inactive items stay subtle.
        fun menuItem(name: String, sub: String, iconGlyph: String, active: Boolean, iconRes: Int? = null, onClick: () -> Unit) {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(8), dp(12), dp(8))
                background = if (active) rounded(primaryContainerColor, 16) else null
                isClickable = true
                setOnClickListener { onClick() }
            }
            item.addView(if (iconRes != null) {
                ImageView(this).apply {
                    setImageResource(iconRes)
                    val tint = if (active) 0xFFFFFFFF.toInt() else onSurfaceVariantColor
                    setColorFilter(tint)
                    setPadding(dp(9), dp(9), dp(9), dp(9))
                    background = if (active) rounded(primaryColor, 10) else rounded(surfaceVariantColor, 10)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
            } else TextView(this).apply {
                text = iconGlyph
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                if (active) {
                    background = rounded(primaryColor, 10)
                    setTextColor(0xFFFFFFFF.toInt())
                } else {
                    background = rounded(surfaceVariantColor, 10)
                    setTextColor(onSurfaceVariantColor)
                }
            }, LinearLayout.LayoutParams(dp(36), dp(36)))
            val txt = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
            }
            txt.addView(TextView(this).apply {
                text = name; textSize = 14f; setTypeface(null, Typeface.BOLD)
                setTextColor(if (active) onPrimaryContainerColor else onSurfaceColor)
            })
            txt.addView(TextView(this).apply {
                text = sub; textSize = 11f
                setTextColor(if (active) onPrimaryContainerColor else onSurfaceVariantColor)
                alpha = if (active) 0.85f else 1f
            })
            item.addView(txt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(14)
            })
            col.addView(item, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(6)
            })
        }

        // ---- indented sub-item for expandable drawer groups (Export) ----
        fun subMenuItem(parent: LinearLayout, name: String, sub: String, onClick: () -> Unit) {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(8), dp(12), dp(8))
                isClickable = true
                setOnClickListener { onClick() }
            }
            item.addView(View(this).apply {}, LinearLayout.LayoutParams(dp(8), dp(1)))
            val txt = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
            txt.addView(TextView(this).apply {
                text = name; textSize = 14f; setTypeface(null, Typeface.BOLD)
                setTextColor(onSurfaceColor)
            })
            txt.addView(TextView(this).apply {
                text = sub; textSize = 11f; setTextColor(onSurfaceVariantColor)
            })
            item.addView(txt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(10)
            })
            parent.addView(item, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(6)
            })
        }

        sectionLabel("Work")
        menuItem("Projects", "Job sites & rates", "◆", drawerTab == 0) { drawerTab = 0; navScreen = 1; filteredSiteId = null; closeDrawer(); renderAll() }
        menuItem("Tasks", "Recorded sessions", "◷", drawerTab == 1) { drawerTab = 1; navScreen = 2; filteredSiteId = null; closeDrawer(); renderAll() }
        sectionLabel("System")
        menuItem("Settings", "Overtime, pay period", "⚙", drawerTab == 2) { drawerTab = 2; navScreen = 3; filteredSiteId = null; closeDrawer(); renderAll() }
        menuItem("Driving", "Set hourly rate", "🚗", false, R.drawable.ic_driving) { closeDrawer(); showDrivingSettings() }

        // Export = expandable group: parent pill toggles Export PDF / Share PDF.
        val exItem = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(12), dp(8))
            background = if (exportOpen) rounded(primaryContainerColor, 16) else null
            isClickable = true
            setOnClickListener { exportOpen = !exportOpen; renderAll() }
        }
        exItem.addView(TextView(this).apply {
            text = "⇩"; textSize = 14f; setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER
            if (exportOpen) {
                background = rounded(primaryColor, 10); setTextColor(0xFFFFFFFF.toInt())
            } else {
                background = rounded(surfaceVariantColor, 10); setTextColor(onSurfaceVariantColor)
            }
        }, LinearLayout.LayoutParams(dp(36), dp(36)))
        val exTxt = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        exTxt.addView(TextView(this).apply {
            text = "Export"; textSize = 14f; setTypeface(null, Typeface.BOLD)
            setTextColor(if (exportOpen) onPrimaryContainerColor else onSurfaceColor)
        })
        exTxt.addView(TextView(this).apply {
            text = "Download or share a PDF"; textSize = 11f
            setTextColor(if (exportOpen) onPrimaryContainerColor else onSurfaceVariantColor)
            alpha = if (exportOpen) 0.85f else 1f
        })
        exItem.addView(exTxt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp(14)
        })
        col.addView(exItem, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(2)
        })

        if (exportOpen) {
            val subCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), 0, 0, 0)
            }
            subMenuItem(subCol, "Export PDF", "Save a summary to Downloads") { exportOpen = false; closeDrawer(); showExportRangeDialog(false) }
            subMenuItem(subCol, "Share PDF", "Send via the share sheet") { exportOpen = false; closeDrawer(); showExportRangeDialog(true) }
            col.addView(subCol, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(6)
            })
        }

        // Spacer pushes the credit line to the bottom of the (full-height) menu.
        col.addView(View(this).apply {}, LinearLayout.LayoutParams(1, 0).apply { weight = 1f })

        val footer = TextView(this).apply {
            text = "vibe coded by pooh"; textSize = 11f; setTextColor(onSurfaceVariantColor)
            setPadding(dp(6), dp(8), 0, 0)
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
                    val projMenuDlg = AlertDialog.Builder(this@MainActivity, pickerDialogThemeId())
                        .setTitle(site.name)
                        .setItems(actions) { _, w ->
                            when (actions[w]) {
                                "Edit" -> showEditProject(site)
                                "Delete" -> AlertDialog.Builder(this@MainActivity, pickerDialogThemeId())
                                    .setTitle("Delete ${site.name}?")
                                    .setMessage(deleteSiteMessage(site.id))
                                    .setPositiveButton("Delete") { _, _ -> deleteSite(site.id) }
                                    .setNegativeButton("Cancel", null)
                                    .show()
                                else -> {}
                            }
                        }
                        .create()
                    projMenuDlg.window?.setLayout(PICKER_DIALOG_WIDTH, ViewGroup.LayoutParams.WRAP_CONTENT)
                    projMenuDlg.show()
                    true
                }
            }.also { row ->
                val siteSessions = sessions.filter { it.jobSiteId == site.id }
                var totalMin = siteSessions.sumOf { workedMinutes(it) }
                // The built-in "Driving" project also reflects every auto-added drive
                // minute (the commute time credited across all projects) so its total
                // shows the real driving hours, not just manually-clocked ones.
                if (site.name == DRIVING_NAME) {
                    totalMin += autoDriveByDay(sessions).values.sumOf { it.second }
                }
                val wageVal = site.hourlyWage?.trim()?.toDoubleOrNull()
                val earnings = if (wageVal != null) wageVal * totalMin / 60.0 else null
                val autoDriveMin = if (site.name == DRIVING_NAME) autoDriveByDay(sessions).values.sumOf { it.second } else 0
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
                if (autoDriveMin > 0) {
                    ci.addView(TextView(this).apply { text = "↻ ${formatMinutesShort(autoDriveMin)} auto-added drive"; textSize = 12f; setTextColor(onSurfaceVariantColor) })
                }
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
        val themeDlg = AlertDialog.Builder(this@MainActivity, pickerDialogThemeId())
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
            .create()
        themeDlg.window?.setLayout(PICKER_DIALOG_WIDTH, ViewGroup.LayoutParams.WRAP_CONTENT)
        themeDlg.show()
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
        val drive = EditText(this).apply {
            hint = "Drive time per day (min)"; textSize = 18f
            setTextColor(onSurfaceColor); setHintTextColor(onSurfaceVariantColor)
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(28), dp(16), dp(28), dp(8))
        }
        name.layoutParams = LinearLayout.LayoutParams(dp(320), dp(56))
        employer.layoutParams = LinearLayout.LayoutParams(dp(320), dp(56))
        employer.setMargins(0, dp(10), 0, 0)
        wage.layoutParams = LinearLayout.LayoutParams(dp(320), dp(56))
        wage.setMargins(0, dp(10), 0, 0)
        drive.layoutParams = LinearLayout.LayoutParams(dp(320), dp(56))
        drive.setMargins(0, dp(10), 0, 0)
        wrap.addView(name)
        wrap.addView(employer)
        wrap.addView(wage)
        wrap.addView(drive)
        AlertDialog.Builder(this, pickerDialogThemeId())
            .setTitle("Add Project")
            .setView(wrap)
            .setPositiveButton("Add") { _, _ ->
                if (name.text.toString().isNotBlank())
                    addSite(name.text.toString().trim(), "", employer.text.toString().trim(), wage.text.toString().trim(), drive.text.toString().trim().toIntOrNull() ?: 0)
                else statusMessage = "Project name can't be empty"
            }
            .setNegativeButton("Cancel", null)
            .show()
        name.requestFocus()
    }

    // Edit an existing project: name, employer, wage, and per-day drive time.
    private fun showEditProject(site: JobSite) {
        val name = EditText(this).apply { setText(site.name); setTextColor(onSurfaceColor); inputType = InputType.TYPE_CLASS_TEXT }
        val employer = EditText(this).apply { setText(site.employer ?: ""); setTextColor(onSurfaceColor); inputType = InputType.TYPE_CLASS_TEXT }
        val wage = EditText(this).apply { setText(site.hourlyWage ?: ""); setTextColor(onSurfaceColor); inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL }
        val drive = EditText(this).apply { setText(if (site.driveMinutes > 0) site.driveMinutes.toString() else ""); setTextColor(onSurfaceColor); inputType = InputType.TYPE_CLASS_NUMBER }
        val nameLbl = TextView(this).apply { text = "Name"; textSize = 12f; setTextColor(onSurfaceVariantColor) }
        val employerLbl = TextView(this).apply { text = "Employer / client"; textSize = 12f; setTextColor(onSurfaceVariantColor) }
        val wageLbl = TextView(this).apply { text = "Hourly wage ($)"; textSize = 12f; setTextColor(onSurfaceVariantColor) }
        val driveLbl = TextView(this).apply { text = "Drive time per day (min)"; textSize = 12f; setTextColor(onSurfaceVariantColor) }
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, 0); minimumWidth = PICKER_DIALOG_WIDTH }
        wrap.addView(fieldColumn(nameLbl, name, employerLbl, employer, wageLbl, wage, driveLbl, drive))
        val editDlg = AlertDialog.Builder(this, pickerDialogThemeId())
            .setTitle("Edit Project")
            .setView(wrap)
            .setPositiveButton("Save") { _, _ ->
                if (name.text.toString().isNotBlank())
                    renameSite(site.id, name.text.toString().trim(), site.location ?: "", employer.text.toString().trim(), wage.text.toString().trim(), drive.text.toString().trim().toIntOrNull() ?: 0)
                else statusMessage = "Project name can't be empty"
            }
            .setNegativeButton("Cancel", null)
            .create()
        editDlg.window?.setLayout(PICKER_DIALOG_WIDTH, ViewGroup.LayoutParams.WRAP_CONTENT)
        editDlg.show()
    }

    // Built-in "Driving" project settings: set its hourly rate. Leave blank/0 to
// track driving hours without charging (it still counts toward overtime).
private fun showDrivingSettings() {
    val driving = jobSites.find { it.name == DRIVING_NAME }
    val wage = EditText(this).apply {
        setText(driving?.hourlyWage ?: "")
        setTextColor(onSurfaceColor)
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
    }
    val hint = TextView(this).apply {
        text = "Set an hourly rate for driving. Leave blank or 0 to track driving hours without charging. Driving counts toward overtime."
        textSize = 13f; setTextColor(onSurfaceVariantColor)
    }
    val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), dp(4)) }
    wrap.addView(hint)
    wrap.addView(wage, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
    val driveDlg = AlertDialog.Builder(this, pickerDialogThemeId())
        .setTitle("Driving")
        .setView(wrap)
        .setPositiveButton("Save") { _, _ -> setDrivingWage(wage.text.toString().trim()) }
        .setNegativeButton("Cancel", null)
        .create()
    driveDlg.window?.setLayout(PICKER_DIALOG_WIDTH, ViewGroup.LayoutParams.WRAP_CONTENT)
    driveDlg.show()
    wage.requestFocus()
}

private fun setDrivingWage(wage: String) {
    val driving = jobSites.find { it.name == DRIVING_NAME } ?: run { ensureDrivingProject(); refreshData(); jobSites.find { it.name == DRIVING_NAME } }
    if (driving == null) return
    val w = wage.toDoubleOrNull()
    android.content.ContentValues().apply {
        if (w == null) putNull("hourly_wage") else put("hourly_wage", w)
        db.writableDatabase.update("job_sites", this, "id=?", arrayOf(driving.id.toString()))
    }
    statusMessage = "Driving rate set to ${if (w == 0.0 || w == null) "0 (hours only, no charge)" else "$${String.format(Locale.US, "%.2f", w)}/hr"}"
    refreshData(); renderAll()
}

// Job picker: a roomy dialog with one card per project (name, rate, hours
    // booked so far). Used to CHOOSE the active job (the one you're working on),
    // both when starting the timer and when changing it. Not used at stop time —
    // stopping books straight to the active job.
    private fun showJobPicker(titleText: String, onPick: (JobSite) -> Unit) {
        if (jobSites.isEmpty()) {
            AlertDialog.Builder(this, pickerDialogThemeId())
                .setTitle(titleText)
                .setMessage("No projects yet. Add one first.")
                .setNegativeButton("Close") { _, _ -> renderAll() }
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
                setOnClickListener { dlg.dismiss(); onPick(site) }
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
                    text = if (sub.isEmpty()) "Tap to select" else sub.joinToString(" · ")
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
            .setTitle(titleText)
            .setView(col)
            .setNegativeButton("Cancel") { _, _ -> renderAll() }
            .create()
        dlg.show()
        // Same width as Add Project so the picker list matches the other menus.
        dlg.window?.setLayout(PICKER_DIALOG_WIDTH, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    // The job the user is working on; null if none chosen yet.
    private fun activeSite(): JobSite? = jobSites.find { it.id == activeJobId }

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
        // Same field width as the Add Project dialog (320dp) so this form fills
        // the same box — the dialog then sizes to identical content.
        date.layoutParams = LinearLayout.LayoutParams(dp(320), dp(56))
        start.layoutParams = LinearLayout.LayoutParams(dp(320), dp(56))
        end.layoutParams = LinearLayout.LayoutParams(dp(320), dp(56))
        brk.layoutParams = LinearLayout.LayoutParams(dp(320), dp(56))

        // project picker row
        val projNames = jobSites.map { it.name }.toTypedArray()
        var selectedIdx = if (initialSiteId > 0) jobSites.indexOfFirst { it.id == initialSiteId } else 0
        if (selectedIdx < 0) selectedIdx = 0
        val projLbl = TextView(this).apply { text = "Project: ${jobSites.getOrNull(selectedIdx)?.name ?: "?"}"; textSize = 14f; setTextColor(primaryColor); setPadding(0, dp(6), 0, 0) }
        projLbl.setOnClickListener {
            val projSelDlg = AlertDialog.Builder(this@MainActivity, pickerDialogThemeId())
                .setTitle("Select Project")
                .setSingleChoiceItems(if (projNames.isEmpty()) arrayOf("No projects") else projNames, selectedIdx) { _, w -> selectedIdx = w }
                .setPositiveButton("OK") { _, _ -> projLbl.text = "Project: ${jobSites.getOrNull(selectedIdx)?.name ?: "?"}" }
                .create()
            projSelDlg.window?.setLayout(PICKER_DIALOG_WIDTH, ViewGroup.LayoutParams.WRAP_CONTENT)
            projSelDlg.show()
        }

        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(28), dp(8), dp(28), dp(4)) }
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

        // Validate BEFORE closing: an untouched Start/End field used to be saved as
        // the empty string, which crashed the next render in LocalTime.parse.
        // Keep the dialog open and point at the field that needs attention.
        val dlg = AlertDialog.Builder(this@MainActivity, pickerDialogThemeId())
            .setTitle(title)
            .setView(form)
            .setPositiveButton(confirmLabel, null)
            .setNegativeButton("Cancel", null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val s24 = time24(start.text.toString())
                val e24 = time24(end.text.toString())
                val sBad = s24.isBlank()
                val eBad = e24.isBlank()
                start.error = if (sBad) "Pick a start time" else null
                end.error = if (eBad) "Pick an end time" else null
                if (sBad || eBad) return@setOnClickListener
                val rid = jobSites.getOrNull(selectedIdx)?.id ?: 1
                val brkMin = (brk.text.toString().toIntOrNull() ?: 0).coerceAtLeast(0)
                dlg.dismiss()
                onSave(date.text.toString(), s24, e24, brkMin, rid)
            }
        }
        dlg.show()
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

    // Minutes worked for one task. Tolerates junk rows written by older builds
    // (a blank start/end used to be storable) so one bad row can't take down
    // every screen that totals sessions. A break longer than the shift used to
    // come out negative and got summed into the week/pay-period totals as such.
    private fun workedMinutes(s: WorkSession): Int =
        (sessionSpanMinutes(s) - clampedBreak(s)).coerceAtLeast(0)

    /** Raw wall-clock span of a task in minutes, ignoring the break. */
    private fun sessionSpanMinutes(s: WorkSession): Int {
        val sm = parseTimeOfDay(s.startTime) ?: return 0
        val em = parseTimeOfDay(s.endTime) ?: return 0
        var diff = em - sm
        if (diff < 0) diff += 24 * 3600
        return diff / 60
    }

    /** A break can never exceed the shift it belongs to. */
    private fun clampedBreak(s: WorkSession): Int =
        s.breakMinutes.coerceIn(0, sessionSpanMinutes(s))

    /** "HH:MM" (24h, as stored) -> seconds since midnight; null when unparseable. */
    private fun parseTimeOfDay(t: String): Int? = try {
        LocalTime.parse(t.trim()).toSecondOfDay()
    } catch (e: Exception) {
        null
    }

    private fun parseIsoDate(d: String): LocalDate? = try {
        LocalDate.parse(d.trim())
    } catch (e: Exception) {
        null
    }

    // Nothing reaches the DB without a project, a parseable date and both times.
    // A blank start/end was storable before this guard and crashed every screen
    // that totalled sessions, so the check lives at the write path as well as in
    // the dialog that produces it.
    private fun isBookable(s: WorkSession): Boolean {
        val why = when {
            s.jobSiteId < 1 -> "Pick a project first"
            parseIsoDate(s.date) == null -> "Pick a valid date"
            parseTimeOfDay(s.startTime) == null -> "Start time is required"
            parseTimeOfDay(s.endTime) == null -> "End time is required"
            else -> null
        }
        if (why == null) return true
        statusMessage = "Not saved: $why"
        renderAll()
        return false
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

    // ==================== EXPORT ====================

    /**
     * Writes bytes into the app's own Export/ directory (see [ExportFile]).
     * Returns a display path and the file:// Uri used for sharing.
     */
    private fun writeDownload(subdir: String, filename: String, bytes: ByteArray): Uri? =
        ExportFile.write(this, prefs, subdir, filename, bytes)

    /** Shares a generated file (e.g. exported PDF) via the system share sheet. */
    private fun shareFile(filename: String, uri: Uri?) {
        if (uri == null) {
            statusMessage = "Sharing failed: file not available"
            renderAll()
            return
        }
        // On API 26-28 ExportFile returns a file:// Uri; route it through the
        // content provider so ACTION_SEND doesn't throw FileUriExposedException.
        val stream = ProjectFileProvider.shareUri(this, uri)
        try {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, stream)
                putExtra(Intent.EXTRA_SUBJECT, filename)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "Share PDF"))
        } catch (e: Exception) {
            statusMessage = "Share failed: ${e.message}"
            renderAll()
        }
    }

    // ==================== BACKUP / RESTORE ====================
    //
    // One JSON file holds every project and every task (dates, times, breaks,
    // notes, wages, and the overtime/pay-period/theme settings). Restoring it
    // rebuilds the database, which is what makes a clean install recoverable.
    //
    // Both directions use the app's own storage. Backups are written straight
    // into the app's Backup folder ([ExportFile.writeBackup]); Restore reads any
    // .json the user picks via the system document picker, so a copy that was
    // synced off-device (Drive, email) can be brought back after a reinstall.

    private val reqRestore = 3002

    /** Builds the JSON and writes it into the app's Backup folder (no document
         * picker needed — the folder is app-owned). Reports where it was saved.
         */
    private fun startBackup() {
        try {
            val uri = Backup.save(this, prefs)
            if (uri == null) throw IllegalStateException("Could not write backup")
            val c = Backup.currentCounts(this)
            statusMessage = "Backed up ${Backup.describe(c.first, c.second)} ✓"
            infoDialog(
                "Backup complete ✓",
                "Saved:\n• ${ExportFile.rootLabel(this, prefs)}/Backup/${uri.lastPathSegment?.substringAfterLast('/')}\n\n" +
                    "It holds every project and task, including times and breaks. Copy it off this phone (share, Drive, email) " +
                    "so it survives losing the device."
            )
        } catch (e: Exception) {
            infoDialog("Backup failed", e.message ?: "Unknown error")
        }
        refreshData(); renderAll()
    }

    private fun confirmRestore() {
        val counts = Backup.currentCounts(this)
        AlertDialog.Builder(this, pickerDialogThemeId())
            .setTitle("Restore from file?")
            .setMessage(
                "Restoring replaces everything on this device. If you haven't backed " +
                "up in a while, do that first.\n\nCurrent: ${Backup.describe(counts.first, counts.second)}."
            )
            .setPositiveButton("Choose file") { _, _ ->
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }
                try {
                    startActivityForResult(intent, reqRestore)
                } catch (e: Exception) {
                    infoDialog("Restore", "No file manager available to open the backup.")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun infoDialog(title: String, message: String) {
        AlertDialog.Builder(this, pickerDialogThemeId())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            reqRestore -> {
                try {
                    val text = contentResolver.openInputStream(uri)?.use {
                        it.readBytes().toString(Charsets.UTF_8)
                    } ?: throw IllegalStateException("Could not read that file")
                    val r = Backup.restore(this, prefs, text)
                    // Reload first: the check below is against the restored projects.
                    refreshData()
                    // The clock must not point at a project id that no longer exists.
                    if (jobSites.none { it.id == activeJobId }) {
                        activeJobId = -1
                        persistClock()
                    }
                    statusMessage = "Restored ${Backup.describe(r.projects, r.tasks)} ✓"
                    infoDialog(
                        "Restored ✓",
                        Backup.describe(r.projects, r.tasks) + " restored." +
                            if (r.skipped > 0)
                                "\n\n${r.skipped} unreadable task(s) in the file were skipped."
                            else ""
                    )
                } catch (e: Exception) {
                    infoDialog("Restore failed", e.message ?: "Unknown error")
                }
                refreshData(); renderAll()
            }
        }
    }

        private fun hhMm(totalMin: Int): String {
            val h = totalMin / 60
            val m = totalMin % 60
            return "${h}:${String.format(Locale.US, "%02d", m)}"
        }

        // ==================== DATE-RANGE EXPORT ====================

        private fun showExportRangeDialog(share: Boolean) {
            // Each preset resolves its [from, to] range and exports it; the
            // picker-based options open a dialog first. Lambdas are typed
            // () -> Unit, so the range must be consumed here explicitly.
            val opts = listOf(
                "This week" to { presetRange("thisweek", share) },
                "Last week" to { presetRange("lastweek", share) },
                "Pay period ($payHeaderLabel)" to { exportRange(payPeriodStart, payPeriodEnd, share) },
                "2 weeks from date…" to { showTwoWeekFromDatePicker(share) },
                "All time" to { presetRange("all", share) },
                "Custom range…" to { showCustomRangePicker(share) }
            )
            val exportDlg = AlertDialog.Builder(this, pickerDialogThemeId())
                .setTitle(if (share) "Share PDF" else "Export")
                .setItems(opts.map { it.first }.toTypedArray()) { _, which ->
                    opts[which].second()
                }
                .setNegativeButton("Cancel", null)
                .create()
            exportDlg.window?.setLayout(PICKER_DIALOG_WIDTH, ViewGroup.LayoutParams.WRAP_CONTENT)
            exportDlg.show()
        }

        // Run a named preset and either save (share=false) or share (share=true).
        private fun presetRange(key: String, share: Boolean) {
            val r = rangeForPreset(key)
            exportRange(r.first, r.second, share)
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

        private fun showCustomRangePicker(share: Boolean = false) {
            // Chain two DatePickerDialogs: pick start, then pick end, then export.
            val today = LocalDate.now()
            val pickEnd = { start: LocalDate ->
                DatePickerDialog(this, pickerDialogThemeId(), { _, y, mo, d ->
                    val end = LocalDate.of(y, mo + 1, d)
                    if (end.isBefore(start)) {
                        statusMessage = "End date can't be before start date"
                        renderAll()
                    } else {
                        exportRange(start.toString(), end.toString(), share)
                    }
                }, today.year, today.monthValue - 1, today.dayOfMonth).show()
            }
            DatePickerDialog(this, pickerDialogThemeId(), { _, y, mo, d ->
                pickEnd(LocalDate.of(y, mo + 1, d))
            }, today.year, today.monthValue - 1, today.dayOfMonth).show()
        }

        // Export 2 weeks (14 days) starting on a calendar-picked date.
        private fun showTwoWeekFromDatePicker(share: Boolean = false) {
            val today = LocalDate.now()
            DatePickerDialog(this, pickerDialogThemeId(), { _, y, mo, d ->
                showTwoWeekRange(LocalDate.of(y, mo + 1, d), share)
            }, today.year, today.monthValue - 1, today.dayOfMonth).show()
        }

        private fun showTwoWeekRange(start: LocalDate, share: Boolean = false) {
            exportRange(start.toString(), start.plusDays(13).toString(), share)
        }

        /**
         * Builds the date-range summary PDF and writes it to
         * Downloads/HoursTracker/Export/. This is the only file the app writes on
         * its own — per-project .xlsx workbooks are gone, so nothing is rewritten
         * in the background when a task is saved.
         * Summary page = per-project totals with an hh:mm grand total; By Week
         * page = each project's weekly hh:mm totals (weeks start Sunday) plus a
         * per-week grand total row.
         */
        private fun exportRange(from: String, to: String, share: Boolean = false) {
            try {
                val inRange = sessions.filter { it.date >= from && it.date <= to }
                if (inRange.isEmpty()) { statusMessage = "No tasks in range"; renderAll(); return }
                val siteName = { id: Int -> jobSites.find { it.id == id }?.name ?: "Unknown" }

                // Auto-drive credit per day, plus per-project and per-week aggregates so
                // drive time shows up alongside hours in the exported report.
                val dayDrives = autoDriveByDay(inRange)
                val driveBySite = HashMap<Int, Int>()
                dayDrives.values.forEach { (sid, m) -> driveBySite[sid] = (driveBySite[sid] ?: 0) + m }

                // Summary rows: one per project (hours + drive) + grand totals.
                val bySite = inRange.groupBy { it.jobSiteId }
                    .map { (id, rows) -> Triple(siteName(id), rows.sumOf { workedMinutes(it) }, id) }
                    .sortedBy { it.first }
                val summaryRows = mutableListOf<List<String>>(listOf("Project", "Hours", "Drive"))
                bySite.forEach {
                    summaryRows.add(listOf(it.first, hhMm(it.second), hhMm(driveBySite[it.third] ?: 0)))
                }
                summaryRows.add(listOf("TOTAL", hhMm(bySite.sumOf { it.second }), hhMm(driveBySite.values.sum())))

                // By-week rows: project x week-start(Sunday) -> hours and drive, per-week grand total row.
                val weekRows = mutableListOf<List<String>>(listOf("Project", "Week of", "Hours", "Drive"))
                val byWeek = inRange.groupBy { sundayOf(it.date) }.toSortedMap()
                val weeksByProject = mutableMapOf<Int, MutableMap<String, Pair<Int, Int>>>()
                inRange.forEach { s ->
                    val w = sundayOf(s.date)
                    weeksByProject.getOrPut(s.jobSiteId) { mutableMapOf() }[w] =
                        (weeksByProject[s.jobSiteId]?.get(w)?.first ?: 0) + workedMinutes(s) to
                        (weeksByProject[s.jobSiteId]?.get(w)?.second ?: 0)
                }
                dayDrives.forEach { (date, pair) ->
                    val w = sundayOf(date)
                    val cur = weeksByProject.getOrPut(pair.first) { mutableMapOf() }[w] ?: (0 to 0)
                    weeksByProject[pair.first]!![w] = cur.first to (cur.second + pair.second)
                }
                weeksByProject.toList().sortedBy { siteName(it.first) }.forEach { (pid, weeks) ->
                    weeks.toSortedMap().forEach { (w, hm) ->
                        weekRows.add(listOf(siteName(pid), isoDateDisplay(w), hhMm(hm.first), hhMm(hm.second)))
                    }
                    weekRows.add(listOf(siteName(pid), "— Total —", hhMm(weeks.values.sumOf { it.first }), hhMm(weeks.values.sumOf { it.second })))
                }
                byWeek.forEach { (w, rows) ->
                    val wDrive = dayDrives.filterKeys { sundayOf(it) == w }.values.sumOf { it.second }
                    weekRows.add(listOf("★ Week total", isoDateDisplay(w), hhMm(rows.sumOf { workedMinutes(it) }), hhMm(wDrive)))
                }

                val label = "${from}_to_${to}"
                val pdfUri = writeDownload("Export", "Summary_$label.pdf", buildPdf(from, to, summaryRows, weekRows))
                val filename = "Summary_$label.pdf"
                // Share mode: write the PDF (needed to get a Uri) then open the
                // share sheet directly instead of the save confirmation dialog.
                if (share) {
                    statusMessage = "Preparing $from → $to for sharing…"
                    shareFile(filename, pdfUri)
                    renderAll()
                    return
                }
                statusMessage = "Exported $from → $to (pdf) ✓"
                AlertDialog.Builder(this, pickerDialogThemeId())
                    .setTitle("Export complete ✓")
                    .setMessage("Saved:\n• ${ExportFile.rootLabel(this@MainActivity, prefs)}/Export/Summary_$label.pdf")
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
                pw.table(summary, columns = intArrayOf(360, 90, 90))
                pw.closePage()

                // --- Page: By Week ---
                pw = pw.newPage()
                pw.subhead("Range: ${isoDateDisplay(from)}  →  ${isoDateDisplay(to)}")
                pw.space()
                pw.table(byWeek, columns = intArrayOf(140, 180, 80, 80))
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

}