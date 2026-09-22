package com.example.hourstracker

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import java.time.LocalDateTime
import java.util.Calendar

/**
 * Daily automatic backup: once a day at a user-chosen time we re-run the same
 * JSON build as the manual "Back up now" button, but write it straight to a
 * destination the user picked once (a Storage Access Framework document).
 *
 * Why an AlarmManager alarm and not WorkManager: the project has no external
 * dependencies, and a single repeating RTC alarm is exactly the shape of "run
 * once a day around this time". It is deliberately inexact (no exact-alarm
 * permission needed on Android 12+); Android fires it within the hour of the
 * requested time, or on the next maintenance window when idle. A backup a
 * little late is fine; a backup that never runs is not.
 *
 * The write permission for the saved document is persisted with
 * [android.content.Context.takePersistableUriPermission] when the file is
 * chosen (in MainActivity), so the receiver can write to it later after the
 * process has died and across reboots, without popping any UI.
 *
 * The database remains the single source of truth. This is the same point-in-
 * time JSON copy as the manual backup — nothing in the app reads it except an
 * explicit Restore.
 */
object AutoBackup {

    /** Implicit action for the pending intent; also used to identify a retry. */
    const val ACTION = "com.example.hourstracker.AUTO_BACKUP"

    // Preference keys — single source of truth for the settings UI.
    const val KEY_ENABLED = "auto_backup_enabled"
    const val KEY_TIME = "auto_backup_time" // "HH:mm", 24h
    const val KEY_URI = "auto_backup_uri" // string form of the destination Uri
    const val KEY_NAME = "auto_backup_name" // human-readable destination label
    const val KEY_LAST = "last_auto_backup" // ISO stamp of the last successful run

    const val DEFAULT_TIME = "21:00" // in 24h; displayed as 9:00 PM

    // ------------------------------------------------------------------ alarm

    /** Arm (enabled) or cancel (disabled) the daily repeating alarm. */
    fun schedule(context: Context, prefs: SharedPreferences) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = alarmPendingIntent(context)
        if (!prefs.getBoolean(KEY_ENABLED, false)) {
            am.cancel(pi)
            return
        }
        val parts = prefs.getString(KEY_TIME, DEFAULT_TIME)!!.split(":")
            .mapNotNull { it.toIntOrNull() }
        val hour = parts.getOrElse(0) { 21 }
        val minute = parts.getOrElse(1) { 0 }
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        // If the chosen time already passed today, first fire is tomorrow.
        if (cal.timeInMillis <= System.currentTimeMillis()) cal.add(Calendar.DAY_OF_YEAR, 1)
        am.setRepeating(
            AlarmManager.RTC_WAKEUP,
            cal.timeInMillis,
            AlarmManager.INTERVAL_DAY,
            pi
        )
    }

    fun alarmPendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, 4001,
            Intent(context, AutoBackupReceiver::class.java).setAction(ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /** 24h "HH:mm" -> display "9:00 PM"; mirrors MainActivity.time12. */
    fun displayTime(prefs: SharedPreferences): String {
        val t = prefs.getString(KEY_TIME, DEFAULT_TIME) ?: DEFAULT_TIME
        val p = t.split(":").mapNotNull { it.toIntOrNull() }
        val h = p.getOrElse(0) { 21 }
        val m = p.getOrElse(1) { 0 }
        val am = h < 12
        var hh = h % 12; if (hh == 0) hh = 12
        return "$hh:${String.format(java.util.Locale.US, "%02d", m)} ${if (am) "AM" else "PM"}"
    }
}

/** Handles the daily alarm and rescheduling after a reboot. */
class AutoBackupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        when (action) {
            AutoBackup.ACTION -> runOnce(context)
            Intent.ACTION_BOOT_COMPLETED -> {
                val prefs = context.getSharedPreferences("hours_tracker", Context.MODE_PRIVATE)
                AutoBackup.schedule(context, prefs)
            }
        }
    }

    /** Build and write the JSON, ignoring failures (next day's firing retries). */
    private fun runOnce(context: Context) {
        val prefs = context.getSharedPreferences("hours_tracker", Context.MODE_PRIVATE)
        if (!prefs.getBoolean(AutoBackup.KEY_ENABLED, false)) return
        val uri = prefs.getString(AutoBackup.KEY_URI, null)?.let {
            try { Uri.parse(it) } catch (e: Exception) { null }
        } ?: return // no destination chosen yet — nothing sensible to do.
        try {
            val json = Backup.build(context, prefs)
            context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                out.write(json.toByteArray(Charsets.UTF_8))
            } ?: return
            val now = LocalDateTime.now().toString()
            prefs.edit()
                .putString("last_backup_at", now)
                .putString(AutoBackup.KEY_LAST, now)
                .apply()
        } catch (e: Exception) {
            // Write failed (e.g. the document was moved/deleted on the other side).
            // Leave last_auto_backup untouched so the user can see it's stale, and
            // let the daily cycle retry. Nothing to surface in the receiver UI.
        }
    }
}