package com.example.hourstracker

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfDocument
import android.os.Bundle
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
import com.example.hourstracker.model.JobSite
import com.example.hourstracker.model.WorkSession
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MainActivity : Activity() {

    private lateinit var db: HoursDb
    private lateinit var prefs: SharedPreferences
    private var isDark = false

    private var clockRunning = false
    private var clockPaused = false
    private var startedAt = ""
    // Persisted wall-clock state so the clock keeps accruing even if the
    // process is killed. segmentStartMs = epoch of the current running segment
    // (0 when paused/stopped); accumulatedMs = time banked from earlier segments.
    private var segmentStartMs = 0L
    private var accumulatedMs = 0L
    private var statusMessage = ""
    private var sessionsExpanded = true

    private var jobSites = mutableListOf<JobSite>()
    private var sessions = mutableListOf<WorkSession>()
    private var drawerOpen = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private var tickerRunning = false

    // view handles for live clock update (avoid rebuilding whole screen every second)
    private var elapsedView: TextView? = null
    private var stateView: TextView? = null
    private var glyphView: TextView? = null
    private var labelView: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = HoursDb(this)
        prefs = getSharedPreferences("hours_tracker", Context.MODE_PRIVATE)
        isDark = prefs.getBoolean("dark", false)
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

    // ==================== THEME ====================

    private val density get() = resources.displayMetrics.density
    private fun dp(v: Int): Int = (v * density).toInt()

    // height of the system status bar so top-anchored UI clears it
    private val statusBarTop: Int by lazy {
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        if (resId > 0) resources.getDimensionPixelSize(resId) else 0
    }

    private val bgColor get() = if (isDark) 0xFF101318.toInt() else 0xFFF7F8FB.toInt()
    private val surfaceColor get() = if (isDark) 0xFF101318.toInt() else 0xFFF7F8FB.toInt()
    private val surfaceVariantColor get() = if (isDark) 0xFF23262E.toInt() else 0xFFE6E9F2.toInt()
    private val onSurfaceColor get() = if (isDark) 0xFFE3E5E9.toInt() else 0xFF1B1C20.toInt()
    private val onSurfaceVariantColor get() = if (isDark) 0xFFBEC3CC.toInt() else 0xFF3D4048.toInt()
    private val primaryColor get() = if (isDark) 0xFF9EC3FF.toInt() else 0xFF2A5BD7.toInt()
    private val primaryContainerColor get() = if (isDark) 0xFF1E3B8F.toInt() else 0xFFD9E3FF.toInt()
    private val onPrimaryContainerColor get() = if (isDark) 0xFFD9E3FF.toInt() else 0xFF0B1F53.toInt()
    private val errorColor get() = if (isDark) 0xFFF0A8A0.toInt() else 0xFFC62828.toInt()

    private fun parseHex(hex: String): Int {
        val h = hex.removePrefix("#").trim()
        return if (h.length == 6) {
            try {
                Color.rgb(h.substring(0, 2).toInt(16), h.substring(2, 4).toInt(16), h.substring(4, 6).toInt(16))
            } catch (e: Exception) { 0xFF6750A4.toInt() }
        } else 0xFF6750A4.toInt()
    }

    private fun rounded(color: Int, radiusDp: Int): GradientDrawable {
        return GradientDrawable().apply {
            setShape(GradientDrawable.RECTANGLE)
            setColor(color)
            setCornerRadius(dp(radiusDp).toFloat())
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
                out.add(JobSite(
                    id = c.getInt(c.getColumnIndexOrThrow("id")),
                    name = c.getString(c.getColumnIndexOrThrow("name")),
                    location = loc,
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

    private fun addSite(name: String, location: String) {
        val cv = android.content.ContentValues()
        cv.put("name", name)
        cv.put("location", if (location.isBlank()) null else location)
        cv.put("color", "#6750A4")
        db.writableDatabase.insert("job_sites", null, cv)
        refreshData(); renderAll()
    }

    private fun renameSite(id: Int, name: String, location: String) {
        android.content.ContentValues().apply {
            put("name", name)
            put("location", if (location.isBlank()) null else location)
            db.writableDatabase.update("job_sites", this, "id=?", arrayOf(id.toString()))
        }
        refreshData(); renderAll()
    }

    private fun deleteSite(id: Int) {
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
        refreshData(); renderAll(); exportAll()
        statusMessage = "Saved ✓ ${session.date} ${session.startTime}-${session.endTime}"
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
        refreshData(); renderAll(); exportAll()
        statusMessage = "Updated ✓ ${session.date}"
    }

    private fun deleteSession(session: WorkSession) {
        db.writableDatabase.delete("work_sessions", "id=?", arrayOf(session.id.toString()))
        refreshData(); renderAll(); exportAll()
        statusMessage = "Deleted ✓"
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
        val topBand = View(this).apply { setBackgroundColor(0xFF101318.toInt()) }
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
        wrap.addView(halo, FrameLayout.LayoutParams(dp(224), dp(224), Gravity.CENTER))

        val inner = FrameLayout(this).apply {
            background = ovalGradient(0xFFFFAA00.toInt(), 0xFFFF4D00.toInt())
            isClickable = true
            setOnClickListener { onHaloTap() }
        }
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
                text = "■ Stop"; textSize = 16f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
                setTypeface(null, Typeface.BOLD)
                background = rounded(0xFFFF4038.toInt(), 8)
                setOnClickListener {
                    clockPaused = true
                    showStopPicker()
                }
            }
            column.addView(stopBtn, LinearLayout.LayoutParams(dp(200), dp(48)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        }

        updateClockViews()

        // ---- status ----
        if (statusMessage.isNotEmpty()) {
            val st = TextView(this).apply { text = statusMessage; textSize = 12f; setTextColor(onSurfaceVariantColor) }
            column.addView(st, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        column.addView(View(this).apply { }, LinearLayout.LayoutParams(1, dp(8)))

        // ---- sessions ----
        val sessionsCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (sessions.isEmpty()) {
            sessionsCol.addView(TextView(this).apply {
                text = "No sessions recorded yet"; textSize = 14f; setTextColor(onSurfaceVariantColor)
                gravity = Gravity.CENTER
            })
        } else {
            val totalMinutes = sessions.sumOf { workedMinutes(it) }
            // summary header
            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
                background = rounded(if (isDark) 0xFF1B1F26.toInt() else 0xFFFFFFFF.toInt(), 16)
                isClickable = true
                setOnClickListener { sessionsExpanded = !sessionsExpanded; renderAll() }
            }
            header.addView(dotView(primaryColor, 10))
            val hi = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
            hi.addView(TextView(this).apply { text = "Sessions"; textSize = 16f; setTypeface(null, Typeface.BOLD); setTextColor(onSurfaceColor) })
            hi.addView(TextView(this).apply { text = "${sessions.size} sessions recorded"; textSize = 12f; setTextColor(onSurfaceVariantColor) })
            header.addView(hi, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            header.addView(pill("Total ${formatMinutesShort(totalMinutes)}", primaryContainerColor, onPrimaryContainerColor))
            header.addView(TextView(this).apply {
                text = if (sessionsExpanded) "▾" else "▸"
                textSize = 18f; setTypeface(null, Typeface.BOLD); setTextColor(onSurfaceVariantColor); setPadding(dp(10), 0, 0, 0)
            })
            sessionsCol.addView(header)

            if (sessionsExpanded) {
                sessions.forEach { s -> sessionsCol.addView(sessionCard(s)) }
            }
        }
        column.addView(sessionsCol)

        // ---- floating ☰ (top-right) ----
        val menuBtn = TextView(this).apply {
            id = 3
            text = "☰"; textSize = 30f; setTypeface(null, Typeface.BOLD)
            setTextColor(onSurfaceColor); gravity = Gravity.CENTER
            background = rounded(surfaceVariantColor, 28)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setOnClickListener { openDrawer() }
        }
        root.addView(menuBtn, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.TOP or Gravity.END))

        // ---- drawer scrim + panel ----
        drawerScrim = View(this).apply {
            setBackgroundColor(0x66000000); visibility = View.GONE
            setOnClickListener { closeDrawer() }
        }
        root.addView(drawerScrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val panelView = buildDrawer()
        drawerPanel = panelView
        root.addView(panelView, FrameLayout.LayoutParams(dp(300), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END))
        panelView.visibility = View.GONE

        setContentView(root)
        if (drawerOpen) openDrawer()
    }

    private var drawerScrim: View? = null
    private var drawerPanel: View? = null

    private fun updateClockViews() {
        stateView?.text = when {
            !clockRunning -> "Idle"
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
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(6))
            background = rounded(if (isDark) 0xFF1B1F26.toInt() else 0xFFFFFFFF.toInt(), 20)
            setMargins(0, dp(8), 0, 0)
        }

        // row 1: dot + date/project + badge
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row1.addView(dotView(projectColor, 12))
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0) }
        info.addView(TextView(this).apply { text = session.date; textSize = 14f; setTypeface(null, Typeface.BOLD); setTextColor(onSurfaceColor) })
        info.addView(TextView(this).apply { text = site?.name ?: "Unknown project"; textSize = 12f; setTextColor(onSurfaceVariantColor) })
        row1.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row1.addView(pill(formatMinutesShort(workedMinutes(session)), tint(projectColor), onSurfaceColor))
        card.addView(row1)

        // row 2: time range + break + edit/delete
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(10), 0, dp(4)) }
        row2.addView(TextView(this).apply {
            text = "${session.startTime} → ${session.endTime}"; textSize = 14f; setTextColor(onSurfaceColor)
        })
        if (session.breakMinutes > 0) {
            val onSec = if (isDark) 0xFFD7E1EE.toInt() else 0xFF1F3045.toInt()
            val secContainer = if (isDark) 0xFF3A4A61.toInt() else 0xFFD7E1EE.toInt()
            row2.addView(pill("${session.breakMinutes}m break", secContainer, onSec).also {
                (it.layoutParams as? LinearLayout.LayoutParams)?.setMargins(dp(8), 0, 0, 0)
            })
        }
        row2.addView(View(this).apply {}, LinearLayout.LayoutParams(0, 1, 1f))
        row2.addView(textAction("Edit") { showEditSession(session) })
        row2.addView(textAction("Delete") {
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Delete session?")
                .setMessage("${session.date} ${session.startTime}-${session.endTime}")
                .setPositiveButton("Delete") { _, _ -> deleteSession(session) }
                .setNegativeButton("Cancel", null)
                .show()
        })
        card.addView(row2)
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

    private fun textAction(textVal: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = textVal; textSize = 13f; setTypeface(null, Typeface.BOLD)
        setTextColor(primaryColor); setPadding(dp(8), dp(4), dp(8), dp(4))
        setOnClickListener { onClick() }
    }

    private fun View.setMargins(l: Int, t: Int, r: Int, b: Int) {
        (layoutParams as? ViewGroup.MarginLayoutParams)?.setMargins(dp(l), dp(t), dp(r), dp(b))
    }

    // ==================== DRAWER ====================

    private fun openDrawer() { drawerScrim?.visibility = View.VISIBLE; drawerPanel?.visibility = View.VISIBLE; drawerOpen = true }
    private fun closeDrawer() { drawerScrim?.visibility = View.GONE; drawerPanel?.visibility = View.GONE; drawerOpen = false }

    private fun buildDrawer(): View {
        val panel = android.widget.ScrollView(this).apply { setBackgroundColor(if (isDark) 0xFF14181D.toInt() else 0xFFFFFFFF.toInt()) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(24), dp(20), dp(24)) }
        panel.addView(col)

        col.addView(TextView(this).apply { text = "Projects"; textSize = 22f; setTypeface(null, Typeface.BOLD); setTextColor(onSurfaceColor) })
        col.addView(TextView(this).apply { text = "Your job sites"; textSize = 13f; setTextColor(onSurfaceVariantColor); setPadding(0, dp(2), 0, dp(12)) })

        if (jobSites.isEmpty()) {
            col.addView(TextView(this).apply { text = "No projects yet. Add one."; textSize = 14f; setTextColor(onSurfaceVariantColor); setPadding(0, dp(6), 0, dp(6)) })
        }
        jobSites.sortedBy { it.name }.forEach { site ->
            col.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = rounded(surfaceVariantColor, 14)
            }.also { row ->
                row.addView(dotView(parseHex(site.color), 12))
                val ci = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
                ci.addView(TextView(this).apply { text = site.name; textSize = 14f; setTypeface(null, Typeface.BOLD); setTextColor(onSurfaceColor) })
                if (!site.location.isNullOrBlank()) ci.addView(TextView(this).apply { text = site.location; textSize = 12f; setTextColor(onSurfaceVariantColor) })
                row.addView(ci, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            })
        }

        col.addView(drawerButton("＋ Add Project") { closeDrawer(); showAddProject() })
        if (jobSites.isNotEmpty()) col.addView(drawerButton("Manage Projects") { closeDrawer(); showManageProjects() })
        col.addView(drawerButton("⤓ Export Date Range") { closeDrawer(); showExportRange() })
        col.addView(drawerButton(if (isDark) "☀ Light mode" else "🌙 Dark mode") { toggleDark() })
        return panel
    }

    private fun drawerButton(textVal: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = textVal; textSize = 15f; setTypeface(null, Typeface.BOLD)
        setTextColor(primaryColor); gravity = Gravity.CENTER
        background = rounded(surfaceVariantColor, 14)
        setPadding(0, dp(14), 0, dp(14))
        setMargins(0, dp(12), 0, 0)
        setOnClickListener { onClick() }
    }

    private fun toggleDark() {
        isDark = !isDark
        prefs.edit().putBoolean("dark", isDark).apply()
        renderAll()
        if (drawerOpen) openDrawer()
    }

    // ==================== DIALOGS ====================

    private fun showAddProject() {
        val name = EditText(this).apply { hint = "Project name"; setTextColor(onSurfaceColor); setHintTextColor(onSurfaceVariantColor) }
        val loc = EditText(this).apply { hint = "Location (optional)"; setTextColor(onSurfaceColor); setHintTextColor(onSurfaceVariantColor) }
        AlertDialog.Builder(this)
            .setTitle("Add Project")
            .setView(fieldColumn(name, loc))
            .setPositiveButton("Add") { _, _ ->
                if (name.text.toString().isNotBlank()) { addSite(name.text.toString().trim(), loc.text.toString()) }
                else statusMessage = "Project name can't be empty"
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showManageProjects() {
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        jobSites.forEach { site ->
            rows.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(8), dp(4), dp(8))
            }.also { row ->
                row.addView(dotView(parseHex(site.color), 10))
                row.addView(TextView(this).apply { text = site.name; textSize = 14f; setTextColor(onSurfaceColor) },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(10), 0, 0, 0) })
                row.addView(textAction("Rename") { showRename(site) })
                row.addView(TextView(this).apply {
                    text = "Delete"; textSize = 13f; setTypeface(null, Typeface.BOLD)
                    setTextColor(errorColor); setPadding(dp(8), dp(4), dp(8), dp(4))
                    setOnClickListener {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Delete ${site.name}?")
                            .setMessage("Sessions will stay; the project is removed.")
                            .setPositiveButton("Delete") { _, _ -> deleteSite(site.id) }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                })
            })
        }
        AlertDialog.Builder(this)
            .setTitle("Manage Projects")
            .setView(rows)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun showRename(site: JobSite) {
        val name = EditText(this).apply { setText(site.name); setTextColor(onSurfaceColor) }
        val loc = EditText(this).apply { setText(site.location ?: ""); setTextColor(onSurfaceColor) }
        AlertDialog.Builder(this)
            .setTitle("Rename Project")
            .setView(fieldColumn(name, loc))
            .setPositiveButton("Save") { _, _ ->
                if (name.text.toString().isNotBlank()) renameSite(site.id, name.text.toString().trim(), loc.text.toString())
                else statusMessage = "Project name can't be empty"
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showStopPicker() {
        val names = jobSites.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Which job were you working on?")
            .setItems(if (names.isEmpty()) arrayOf("No projects yet") else names) { _, which ->
                if (names.isNotEmpty() && which in jobSites.indices) stopClock(jobSites[which].id)
            }
            .setNegativeButton(if (names.isEmpty()) "Close" else "Cancel") { _, _ -> if (clockPaused) clockPaused = false; renderAll() }
            .show()
    }

    private fun showEditSession(session: WorkSession) {
        val date = EditText(this).apply { setText(session.date); setTextColor(onSurfaceColor) }
        val start = EditText(this).apply { setText(session.startTime); setTextColor(onSurfaceColor) }
        val end = EditText(this).apply { setText(session.endTime); setTextColor(onSurfaceColor) }
        val brk = EditText(this).apply { setText(session.breakMinutes.toString()); setTextColor(onSurfaceColor); inputType = InputType.TYPE_CLASS_NUMBER }

        // project picker row
        val projNames = jobSites.map { it.name }.toTypedArray()
        val projIdx = jobSites.indexOfFirst { it.id == session.jobSiteId }
        var selectedIdx = if (projIdx >= 0) projIdx else 0
        val projLbl = TextView(this).apply { text = "Project: ${jobSites.getOrNull(selectedIdx)?.name ?: "?"}"; textSize = 14f; setTextColor(primaryColor); setPadding(0, dp(6), 0, 0) }
        projLbl.setOnClickListener {
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Select Project")
                .setSingleChoiceItems(if (projNames.isEmpty()) arrayOf("No projects") else projNames, selectedIdx) { _, w -> selectedIdx = w }
                .setPositiveButton("OK") { _, _ -> projLbl.text = "Project: ${jobSites.getOrNull(selectedIdx)?.name ?: "?"}" }
                .show()
        }

        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), dp(4)) }
        listOf(date to "Date", start to "Start time", end to "End time", brk to "Break (min)").forEach { (f, labelTxt) ->
            f.hint = labelTxt; f.setHintTextColor(onSurfaceVariantColor); form.addView(f)
            form.addView(View(this).apply {}, LinearLayout.LayoutParams(1, dp(6)))
        }
        form.addView(projLbl)

        AlertDialog.Builder(this)
            .setTitle("Edit Session")
            .setView(form)
            .setPositiveButton("Save") { _, _ ->
                val rid = jobSites.getOrNull(selectedIdx)?.id ?: 1
                updateSession(WorkSession(
                    id = session.id, jobSiteId = rid, date = date.text.toString(),
                    startTime = start.text.toString(), endTime = end.text.toString(),
                    breakMinutes = brk.text.toString().toIntOrNull() ?: 0, notes = null
                ))
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

    private fun formatElapsedMs(totalMs: Long): String {
        val s = totalMs / 1000
        val h = s / 3600; val m = (s % 3600) / 60; val ss = s % 60
        return if (h > 0) "${h}h ${m}m ${ss}s" else if (m > 0) "${m}m ${ss}s" else "${ss}s"
    }

    // ==================== XLSX EXPORT ====================

    /** Writes one workbook per project into Downloads/HoursTracker/<Name>.xlsx after any session change. */
    private fun exportAll() {
        try {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "HoursTracker")
            dir.mkdirs()
            jobSites.forEach { site ->
                val siteSessions = sessions.filter { it.jobSiteId == site.id }.sortedWith(compareBy({ it.date }, { it.startTime }))
                val safeName = site.name.replace("/", "-").replace("\\", "-").trim() + ".xlsx"
                val file = File(dir, safeName)
                FileOutputStream(file).use { it.write(buildXlsxSheets(listOf("Session" to buildRows(siteSessions)))) }
            }
            statusMessage = "Files written to Downloads/HoursTracker ✓"
        } catch (e: Exception) {
            statusMessage = "Export FAILED: ${e.message}"
        }
        renderAll()
    }

    private fun buildRows(rows: List<WorkSession>): List<List<String>> {
            val out = mutableListOf(listOf("Date", "Start", "End", "Break (min)", "Worked (min)", "Notes"))
            rows.forEach { s ->
                val worked = durationMinutes(s.startTime, s.endTime) - s.breakMinutes
                out.add(listOf(s.date, s.startTime, s.endTime, s.breakMinutes.toString(), worked.toString(), s.notes ?: ""))
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
            val opts = listOf(
                "This week" to { rangeForPreset("thisweek") },
                "Last week" to { rangeForPreset("lastweek") },
                "This month" to { rangeForPreset("thismonth") },
                "Last month" to { rangeForPreset("lastmonth") },
                "Last 30 days" to { rangeForPreset("30days") },
                "All time" to { rangeForPreset("all") },
                "Custom range…" to { null }
            )
            AlertDialog.Builder(this)
                .setTitle("Export date range")
                .setItems(opts.map { it.first }.toTypedArray()) { _, which ->
                    val rst = opts[which].second()
                    if (which == opts.size - 1) showCustomRangePicker()
                    else rst?.let { exportRange(it.first, it.second) }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // Inclusive [from, to] as ISO dates for the given display preset.
        private fun rangeForPreset(key: String): Pair<String, String> {
            val today = LocalDate.now()
            return when (key) {
                "thisweek" -> {
                    val monday = today.with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    monday.toString() to monday.plusDays(6).toString()
                }
                "lastweek" -> {
                    val monday = today.with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    monday.minusWeeks(1).toString() to monday.minusDays(1).toString()
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
                DatePickerDialog(this, { _, y, mo, d ->
                    val end = LocalDate.of(y, mo + 1, d)
                    if (end.isBefore(start)) {
                        statusMessage = "End date can't be before start date"
                        renderAll()
                    } else {
                        exportRange(start.toString(), end.toString())
                    }
                }, today.year, today.monthValue - 1, today.dayOfMonth).show()
            }
            DatePickerDialog(this, { _, y, mo, d ->
                pickEnd(LocalDate.of(y, mo + 1, d))
            }, today.year, today.monthValue - 1, today.dayOfMonth).show()
        }

        /**
         * Builds the date-range xlsx + matching PDF and writes both to
         * Downloads/HoursTracker/. Summary sheet = per-project totals with an
         * hh:mm grand total; By Week sheet = each project's weekly hh:mm totals
         * (weeks start Monday) plus a per-week grand total row.
         */
        private fun exportRange(from: String, to: String) {
            try {
                val inRange = sessions.filter { it.date >= from && it.date <= to }
                if (inRange.isEmpty()) { statusMessage = "No sessions in range"; renderAll(); return }
                val siteName = { id: Int -> jobSites.find { it.id == id }?.name ?: "Unknown" }

                // Summary rows: one per project + grand total.
                val bySite = inRange.groupBy { it.jobSiteId }
                    .map { (id, rows) -> Triple(siteName(id), rows.sumOf { workedMinutes(it) }, id) }
                    .sortedBy { it.first }
                val grandTotal = bySite.sumOf { it.second }
                val summaryRows = mutableListOf<List<String>>(listOf("Project", "Hours"))
                bySite.forEach { summaryRows.add(listOf(it.first, hhMm(it.second))) }
                summaryRows.add(listOf("TOTAL", hhMm(grandTotal)))

                // By-week rows: project x week-start(Monday) -> hours, per-week grand total row.
                val weekRows = mutableListOf<List<String>>(listOf("Project", "Week of", "Week #", "Hours"))
                val byWeek = inRange.groupBy { mondayOf(it.date) }.toSortedMap()
                val weeksByProject = mutableMapOf<Int, MutableMap<String, Int>>()
                inRange.forEach { s ->
                    val w = mondayOf(s.date)
                    weeksByProject.getOrPut(s.jobSiteId) { mutableMapOf() }[w] =
                        (weeksByProject[s.jobSiteId]?.get(w) ?: 0) + workedMinutes(s)
                }
                weeksByProject.toList().sortedBy { siteName(it.first) }.forEach { (pid, weeks) ->
                    weeks.toSortedMap().forEach { (w, mins) ->
                        weekRows.add(listOf(siteName(pid), w, weekNumber(w).toString(), hhMm(mins)))
                    }
                    val total = weeks.values.sum()
                    weekRows.add(listOf(siteName(pid), "— Total —", "", hhMm(total)))
                }
                byWeek.forEach { (w, rows) ->
                    val total = rows.sumOf { workedMinutes(it) }
                    weekRows.add(listOf("★ Week total", w, "", hhMm(total)))
                }

                // Sessions sheet: raw detail in range.
                val sessionRows = buildRows(inRange.sortedWith(compareBy({ it.date }, { it.startTime })))

                val label = "${from}_to_${to}"
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "HoursTracker")
                dir.mkdirs()
                val xlsFile = File(dir, "Summary_$label.xlsx")
                FileOutputStream(xlsFile).use { it.write(buildXlsxSheets(listOf(
                    "Summary" to summaryRows,
                    "By Week" to weekRows,
                    "Sessions" to sessionRows
                ))) }
                val pdfFile = File(dir, "Summary_$label.pdf")
                FileOutputStream(pdfFile).use { it.write(buildPdf(from, to, summaryRows, weekRows)) }
                statusMessage = "Exported $from → $to (xlsx + pdf) ✓"
            } catch (e: Exception) {
                statusMessage = "Export FAILED: ${e.message}"
            }
            renderAll()
        }

        private fun weekNumber(mondayIso: String): Int =
                LocalDate.parse(mondayIso).get(WeekFields.ISO.weekOfWeekBasedYear())

            // ISO date string for the Monday of the week containing the given ISO date.
            private fun mondayOf(isoDate: String): String =
                LocalDate.parse(isoDate).with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString()

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
                pw.subhead("Range: $from  →  $to")
                pw.space()
                pw.table(summary, columns = intArrayOf(420, 100))
                pw.closePage()

                // --- Page: By Week ---
                pw = pw.newPage()
                pw.heading("Hours by Week (weeks start Monday)")
                pw.subhead("Range: $from  →  $to")
                pw.space()
                pw.table(byWeek, columns = intArrayOf(200, 220, 90, 90))
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