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
 *   - timerBtn  toggles Start / Pause / Resume (writes the same persisted wall-clock
 *               state the app uses, so it works without the app being open).
 *   - stopBtn   hands off to the app's save flow (job picker) so the session is kept.
 *   - hoursValue shows today's elapsed hours (live once a minute + on each action).
 *
 * Layout stays FLAT (this host rejects nested/weighted columns). Text/button colors
 * and the solid background are applied via RemoteViews actions at runtime.
 */
class Widget3x1Provider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val views = try { build(context) } catch (t: Throwable) { RemoteViews(context.packageName, R.layout.widget_3x1) }
        for (id in ids) {
            try { manager.updateAppWidget(id, views) } catch (t: Throwable) { }
        }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: android.os.Bundle) {
        val views = try { build(context) } catch (t: Throwable) { RemoteViews(context.packageName, R.layout.widget_3x1) }
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

        private fun prefs(context: Context): SharedPreferences =
            context.getSharedPreferences("hours_tracker", Context.MODE_PRIVATE)

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
            // HH:MM (e.g. "02:05") — the widget shows elapsed time as clock-style
            // hours:minutes, not "2h 5m".
            val h = total / 60; val m = total % 60
            return String.format(Locale.US, "%02d:%02d", h, m)
        }

        // ---- Timer state (mirrors MainActivity's persisted wall-clock keys) ----
        private fun elapsedMs(p: SharedPreferences): Long {
            if (!p.getBoolean("clockRunning", false)) return 0L
            val paused = p.getBoolean("clockPaused", false)
            val seg = p.getLong("segmentStartMs", 0L)
            val acc = p.getLong("accumulatedMs", 0L)
            return if (paused || seg == 0L) acc else acc + (System.currentTimeMillis() - seg)
        }

        private fun toggleTimer(context: Context) {
            val p = prefs(context)
            val running = p.getBoolean("clockRunning", false)
            val paused = p.getBoolean("clockPaused", false)
            val e = p.edit()
            if (!running) {
                // Start
                e.putString("startedAt", LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")))
                e.putBoolean("clockRunning", true)
                e.putBoolean("clockPaused", false)
                e.putLong("accumulatedMs", 0L)
                e.putLong("segmentStartMs", System.currentTimeMillis())
            } else if (paused) {
                // Resume
                e.putBoolean("clockPaused", false)
                e.putLong("segmentStartMs", System.currentTimeMillis())
            } else {
                // Pause
                e.putLong("accumulatedMs", elapsedMs(p))
                e.putLong("segmentStartMs", 0L)
                e.putBoolean("clockPaused", true)
            }
            e.apply()
        }

        private fun stopFromWidget(context: Context) {
            // Hand off to the app so the session is saved through the normal job
            // picker, rather than silently dropping the run's data.
            val intent = Intent(context, MainActivity::class.java)
                .setAction(CMD_STOP_APP)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            try { context.startActivity(intent) } catch (t: Throwable) { }
        }

        /** Hours today + live running elapsed. Throws? handled by caller. */
        private fun build(context: Context): RemoteViews {
            val today = LocalDate.now().toString()
            val p = prefs(context)

            var minutesToday = 0
            val db = HoursDb(context)
            db.readableDatabase.rawQuery(
                "SELECT start_time, end_time, break_minutes FROM work_sessions WHERE date=?",
                arrayOf(today)
            ).use { c ->
                while (c.moveToNext()) {
                    val sm = LocalTime.parse(c.getString(c.getColumnIndexOrThrow("start_time"))).toSecondOfDay()
                    val em = LocalTime.parse(c.getString(c.getColumnIndexOrThrow("end_time"))).toSecondOfDay()
                    var diff = em - sm
                    if (diff < 0) diff += 24 * 3600
                    minutesToday += diff / 60 - c.getInt(c.getColumnIndexOrThrow("break_minutes")).coerceAtLeast(0)
                }
            }
            db.close()
            minutesToday += (elapsedMs(p) / 60000).toInt()

            val running = p.getBoolean("clockRunning", false)
            val paused = p.getBoolean("clockPaused", false)

            val dark = resolveDark(context)
            val views = RemoteViews(context.packageName, R.layout.widget_3x1)
            views.setTextViewText(R.id.hoursValue, formatMinutesShort(minutesToday))

            val toggleLabel = when {
                !running -> "▶"     // start (idle)
                paused -> "▶"      // resume (paused)
                else -> "⏸"        // pause (running)
            }
            views.setTextViewText(R.id.timerBtn, toggleLabel)

            views.setInt(R.id.widgetRoot, "setBackgroundColor", if (dark) 0xFF1B1F1D.toInt() else 0xFFF1F4F0.toInt())
            val onSurface = if (dark) 0xFFE1E3DE.toInt() else 0xFF191C1A.toInt()
            val btnFg = if (dark) 0xFF00251A.toInt() else 0xFFFFFFFF.toInt()
            val toggleFill = when {
                !running -> 0xFF00695C.toInt()            // teal: Start
                paused -> 0xFFFF6D00.toInt()               // orange: Resume
                else -> 0xFFFFC107.toInt()                 // gold: Pause
            }
            views.setTextColor(R.id.hoursValue, onSurface)
            views.setInt(R.id.timerBtn, "setBackgroundColor", toggleFill)
            views.setTextColor(R.id.timerBtn, btnFg)
            views.setInt(R.id.stopBtn, "setBackgroundColor", 0xFFFF4038.toInt())
            views.setTextColor(R.id.stopBtn, 0xFFFFFFFF.toInt())

            views.setOnClickPendingIntent(R.id.timerBtn, PendingIntent.getBroadcast(
                context, 0,
                Intent(context, Widget3x1Provider::class.java).setAction(ACTION_TOGGLE),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            views.setOnClickPendingIntent(R.id.stopBtn, PendingIntent.getActivity(
                context, 1,
                Intent(context, MainActivity::class.java)
                    .setAction(CMD_STOP_APP)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            return views
        }

        /** Refresh all placed instances (called from MainActivity + after actions). */
        fun updateAll(context: Context) {
            val views = try { build(context) } catch (t: Throwable) { RemoteViews(context.packageName, R.layout.widget_3x1) }
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(android.content.ComponentName(context, Widget3x1Provider::class.java))
            for (id in ids) {
                try { mgr.updateAppWidget(id, views) } catch (t: Throwable) { }
            }
        }
    }
}