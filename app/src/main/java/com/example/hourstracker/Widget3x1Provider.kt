package com.example.hourstracker

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.widget.RemoteViews
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 3x1 home-screen widget that shows today's worked hours AND controls the timer:
 *   - timerBtn  toggles Start / Pause / Resume entirely in-widget via an explicit
 *               broadcast back to this provider (onReceive -> toggleTimer).
 *   - stopBtn   books the session to the active job in-widget (no app launch).
 *   - hoursText is the hero line at the TOP of the left column (30sp bold):
 *               today's hours worked.
 *   - jobName   sits under it (12sp bold), then employerText (11sp, muted).
 *   - stateStripeOn / stateStripeOff are two stacked 6x90dp Views on the left
 *               edge; exactly one is visible, picked from the clock state. Two
 *               sibling views are used instead of one view + a runtime
 *               background swap because setViewVisibility is reliably remotable
 *               and setBackgroundResource here has never been verified on device.
 *
 * v3.44 "accent stripe" layout (the user picked it from a true-size mockup sheet).
 * The button FILLS live in the layout XML drawables and nothing writes a button
 * background at runtime any more: the previous code called
 * setInt(id, "setBackgroundColor", ...), which replaces the drawable with a plain
 * ColorDrawable — that is what rendered the round controls as solid squares.
 *
 * Both PendingIntents MUST be getBroadcast (this is a BroadcastReceiver, not an
 * Activity) and MUST carry FLAG_IMMUTABLE — on targetSdk 31+ creating a
 * PendingIntent without a mutability flag throws IllegalArgumentException, which
 * silently killed the buttons before (the throw was swallowed by the try/catch
 * in onUpdate, leaving a RemoteViews with no click handlers at all).
 *
 * Layout IS NESTED (v3.28, user-confirmed on device): a vertical root holding the
 * full-width job name on top and an inner horizontal row with hours + the two
 * buttons. An earlier note here claimed "this host rejects nested/weighted
 * containers — they render translucent and break clicks". That was WRONG: it was
 * a misdiagnosis of the PendingIntent mutability bug above, made in the same
 * session the buttons were dead. Do not flatten the layout on that account.
 * Root background is the solid dark panel declared in the layout XML.
 */
class Widget3x1Provider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) {
            val views = try { build(context, id) } catch (t: Throwable) { RemoteViews(context.packageName, R.layout.widget_3x1) }
            try { manager.updateAppWidget(id, views) } catch (t: Throwable) { }
        }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: android.os.Bundle) {
        val views = try { build(context, id) } catch (t: Throwable) { RemoteViews(context.packageName, R.layout.widget_3x1) }
        try { manager.updateAppWidget(id, views) } catch (t: Throwable) { }
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_TOGGLE -> toggleTimer(context)
            ACTION_STOP -> stopFromWidget(context)
            else -> super.onReceive(context, intent)
        }
        if (intent.action == ACTION_TOGGLE || intent.action == ACTION_STOP) {
            updateAll(context)
        }
    }

    companion object {
        const val ACTION_TOGGLE = "com.example.hourstracker.widget.TOGGLE"
        const val ACTION_STOP = "com.example.hourstracker.widget.STOP"
        const val CMD_STOP_APP = "com.example.hourstracker.CMD_STOP"
        const val CMD_START_APP = "com.example.hourstracker.CMD_START"
        const val CMD_TOGGLE_APP = "com.example.hourstracker.CMD_TOGGLE"

        private fun prefs(context: Context): SharedPreferences = Clock.prefs(context)

        private fun resolveDark(context: Context): Boolean {
            val mode = prefs(context).getString("theme", "system") ?: "system"
            return when (mode) {
                "dark" -> true
                "light" -> false
                else -> {
                    val ui = context.resources.configuration.uiMode
                    val mask = android.content.res.Configuration.UI_MODE_NIGHT_MASK
                    (ui and mask) == android.content.res.Configuration.UI_MODE_NIGHT_YES
                }
            }
        }

        private fun formatMinutesShort(total: Int): String {
            // H:MM (e.g. "5:05") — no leading zero on the hour digit.
            val h = total / 60; val m = total % 60
            return String.format(Locale.US, "%d:%02d", h, m)
        }

        /** "HH:MM" as stored -> seconds since midnight; null when unparseable. */
        private fun timeOfDay(t: String?): Int? = try {
            LocalTime.parse(t!!.trim()).toSecondOfDay()
        } catch (t2: Throwable) {
            null
        }

        // ---- Timer state: owned by Clock, shared with MainActivity/TimerService ----
        private fun toggleTimer(context: Context) {
            val p = prefs(context)
            val s = Clock.read(p)
            if (!s.running) {
                // Start. Only allowed if an active (working-on) job is set; the
                // widget can't show a picker, so hand off to the app to choose.
                if (s.activeJobId < 1) {
                    startFromApp(context)
                    return
                }
                Clock.start(p, s.activeJobId)
            } else if (s.paused) {
                Clock.resume(p)
            } else {
                Clock.pause(p)
            }
            // Keep the status notification in step with the widget's own action
            // (running/paused -> show, stopped -> hide) without opening the app.
            syncTimerService(context)
        }

        private fun syncTimerService(context: Context) {
            val p = prefs(context)
            try {
                if (p.getBoolean("clockRunning", false)) {
                    val i = Intent(context, TimerService::class.java).setAction("sync")
                    if (android.os.Build.VERSION.SDK_INT >= 26) context.startForegroundService(i)
                    else context.startService(i)
                } else {
                    context.stopService(Intent(context, TimerService::class.java))
                }
            } catch (t: Throwable) { }
        }

        private fun startFromApp(context: Context) {
            val intent = Intent(context, MainActivity::class.java)
                .setAction(CMD_START_APP)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            try { context.startActivity(intent) } catch (t: Throwable) { }
        }

        private fun stopFromWidget(context: Context) {
            // Book the session directly to the active job (no app launch).
            val p = prefs(context)
            val s = Clock.read(p)
            if (!s.running) return
            val jobId = s.activeJobId
            if (jobId < 1) return
            // Same reservation the app makes: start -> now, paused minutes as the
            // break. Really stop the clock first so a failed insert can't leave a
            // running clock behind a widget that says stopped.
            val booked = Clock.stop(p)
            if (booked.startedAt.isBlank()) return
            try {
                val db = HoursDb(context)
                val cv = android.content.ContentValues()
                cv.put("job_site_id", jobId)
                cv.put("date", LocalDate.now().toString())
                cv.put("start_time", booked.startedAt)
                cv.put("end_time", booked.endTime)
                cv.put("break_minutes", booked.breakMinutes)
                cv.put("notes", "clock")
                db.writableDatabase.insert("work_sessions", null, cv)
                db.close()
            } catch (t: Throwable) { }
            // No workbook is written: the DB is the record, and backups are on demand.
            syncTimerService(context)
        }

        /** Hours today + live running elapsed. Throws? handled by caller. */
        private fun build(context: Context, appWidgetId: Int): RemoteViews {
            val today = LocalDate.now().toString()
            val p = prefs(context)

            var minutesToday = 0
            var activeName = ""
            var activeEmployer = ""
            val db = HoursDb(context)
            val activeId = p.getInt("activeJobId", -1)
            if (activeId >= 1) {
                db.readableDatabase.rawQuery("SELECT name FROM job_sites WHERE id=?", arrayOf(activeId.toString())).use { c ->
                    if (c.moveToNext()) activeName = c.getString(c.getColumnIndexOrThrow("name")) ?: ""
                }
                // Separate query, in its own try/catch: "employer" only exists on
                // DBs that ran the ALTER TABLE upgrade, and a throw here would
                // abort build() and ship an EMPTY widget (callers fall back to a
                // bare RemoteViews). A missing column must only cost the subtitle.
                try {
                    db.readableDatabase.rawQuery("SELECT employer FROM job_sites WHERE id=?", arrayOf(activeId.toString())).use { c ->
                        if (c.moveToNext()) activeEmployer = c.getString(0) ?: ""
                    }
                } catch (t: Throwable) { activeEmployer = "" }
            }
            db.readableDatabase.rawQuery(
                "SELECT start_time, end_time, break_minutes FROM work_sessions WHERE date=?",
                arrayOf(today)
            ).use { c ->
                while (c.moveToNext()) {
                    // Skip rows we can't read rather than throwing: one junk row
                    // (older builds could store a blank time) must not blank out
                    // the whole widget via build()'s fallback.
                    val sm = timeOfDay(c.getString(c.getColumnIndexOrThrow("start_time"))) ?: continue
                    val em = timeOfDay(c.getString(c.getColumnIndexOrThrow("end_time"))) ?: continue
                    var diff = em - sm
                    if (diff < 0) diff += 24 * 3600
                    minutesToday += (diff / 60 - c.getInt(c.getColumnIndexOrThrow("break_minutes")).coerceAtLeast(0)).coerceAtLeast(0)
                }
            }
            db.close()
            minutesToday += (Clock.elapsedMs(p) / 60000).toInt().coerceAtLeast(0)

            val running = p.getBoolean("clockRunning", false)
            val paused = p.getBoolean("clockPaused", false)

            // Root panel is the solid dark shape declared in the layout XML.
            // Do NOT override it at runtime: setBackgroundColor would replace the
            // drawable outright (losing the rounded corners) and would also
            // re-introduce the transparent-widget look the panel is meant to fix.
            // Because the panel is always dark, text is always the light on-dark tone.
            val onSurface = 0xFFE1E3DE.toInt()

            val views = RemoteViews(context.packageName, R.layout.widget_3x1)

            // v3.28: nested layout, user-confirmed working. Name spans the top line;
            // the inner row holds hours + the two buttons. Nesting is fine here —
            // see the layout comment (the old "host rejects nesting" theory was a
            // misdiagnosis of the PendingIntent mutability bug).
            val nameLine = activeName.ifBlank { "No job" }
            views.setTextViewText(R.id.jobName, nameLine)
            views.setTextColor(R.id.jobName, onSurface)

            // Today's hours — bottom-left line of the inner row.
            views.setTextViewText(R.id.hoursText, formatMinutesShort(minutesToday))
            views.setTextColor(R.id.hoursText, onSurface)

            // Job + employer under the hero time. The employer line collapses when
            // the job has none, so the column never keeps an empty gap.
            views.setTextViewText(R.id.employerText, activeEmployer)
            views.setViewVisibility(
                R.id.employerText,
                if (activeEmployer.isBlank()) android.view.View.GONE else android.view.View.VISIBLE)

            // Left edge stripe = clock state. Idle (brick) is the XML default, so a
            // widget that has never been updated still reads as idle rather than
            // "running".
            val active = running || paused
            views.setViewVisibility(R.id.stateStripeOn, if (active) android.view.View.VISIBLE else android.view.View.GONE)
            views.setViewVisibility(R.id.stateStripeOff, if (active) android.view.View.GONE else android.view.View.VISIBLE)

            val toggleLabel = when {
                !running -> "▶"     // start (idle)
                paused -> "▶"      // resume (paused)
                else -> "⏸"        // pause (running)
            }
            views.setTextViewText(R.id.timerBtn, toggleLabel)

            // NO setBackgroundColor on the controls. The fills are the oval
            // drawables declared in widget_3x1.xml (@drawable/widget_circle_teal /
            // _brick); a runtime background write would swap them for a plain
            // ColorDrawable and square off the circles, which is exactly the
            // defect this layout was rewritten to fix. Glyphs stay white.
            views.setTextColor(R.id.timerBtn, 0xFFFFFFFF.toInt())
            views.setTextColor(R.id.stopBtn, 0xFFFFFFFF.toInt())

            val togglePi = PendingIntent.getBroadcast(
                context, appWidgetId,
                Intent(context, Widget3x1Provider::class.java).setAction(ACTION_TOGGLE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val stopPi = PendingIntent.getBroadcast(
                context, appWidgetId + 1000,
                Intent(context, Widget3x1Provider::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.timerBtn, togglePi)
            views.setOnClickPendingIntent(R.id.stopBtn, stopPi)
            return views
        }

        /** Refresh all placed instances (called from MainActivity + after actions). */
        fun updateAll(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(android.content.ComponentName(context, Widget3x1Provider::class.java))
            for (id in ids) {
                val views = try { build(context, id) } catch (t: Throwable) { RemoteViews(context.packageName, R.layout.widget_3x1) }
                try { mgr.updateAppWidget(id, views) } catch (t: Throwable) { }
            }
        }
    }
}