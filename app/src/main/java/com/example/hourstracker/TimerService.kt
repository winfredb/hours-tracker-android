package com.example.hourstracker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.util.Locale

/**
 * Foreground service that keeps a persistent, ongoing notification in the shade
 * showing whether the stopwatch timer is running or paused — so the user can see
 * the clock state without opening the app. It reads the same persisted wall-clock
 * state (clockRunning / clockPaused / segmentStartMs / accumulatedMs) that
 * MainActivity writes, so it continues to reflect the timer even if the Activity
 * is destroyed, and ticks the elapsed time live once a second.
 *
 * MainActivity starts it when the timer starts/resumes/pauses and stops it when
 * the timer is stopped (stop the notification when the clock isn't running).
 */
class TimerService : Service() {

    companion object {
        private const val CHANNEL_ID = "timer_status"
        private const val NOTIFICATION_ID = 1001
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var nm: NotificationManager
    private lateinit var openApp: PendingIntent
    private val handler = Handler(Looper.getMainLooper())

    // The widget hero only renders whole minutes (H:MM), so it needs a nudge when
    // that minute rolls over - the host's own updatePeriodMillis is floored at 30
    // minutes, so without this the widget sat frozen while the clock ran. One
    // RemoteViews push per minute is cheap; the notification itself still ticks
    // every second.
    private var lastWidgetMinute = Long.MIN_VALUE

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("hours_tracker", Context.MODE_PRIVATE)
        nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        if (Build.VERSION.SDK_INT >= 26) createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastWidgetMinute = Long.MIN_VALUE
        syncNotification()
        handler.removeCallbacksAndMessages(null)
        handler.post(object : Runnable {
            override fun run() {
                if (!syncNotification()) return // timer stopped -> service stopped itself
                syncWidgetMinute()
                handler.postDelayed(this, 1000L)
            }
        })
        return START_STICKY
    }

    /**
     * Refresh the home-screen widget when the displayed minute changes. The widget
     * shows today's whole minutes, so this is the only moment its value can change;
     * it is also the thing that used to never happen while the clock was running.
     */
    private fun syncWidgetMinute() {
        val minute = Clock.elapsedMs(prefs) / 60000L
        if (minute == lastWidgetMinute) return
        lastWidgetMinute = minute
        try {
            Widget3x1Provider.updateAll(this)
        } catch (t: Throwable) {
            // A widget that fails to update must never take the timer down with it.
        }
    }

    /** Returns false (and stops the service) when the timer is not running. */
    private fun syncNotification(): Boolean {
        val state = Clock.read(prefs)
        if (!state.running) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return false
        }
        val elapsed = Clock.elapsedMs(prefs)
        val title = if (state.paused) "Timer paused" else "Timer running"
        val text = if (state.paused)
            "Tap to resume — ${state.startedAt} • ${formatElapsed(elapsed)}"
        else
            "Working since ${state.startedAt} • ${formatElapsed(elapsed)}"

        val notification = if (Build.VERSION.SDK_INT >= 26)
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setOngoing(true)
                .setContentIntent(openApp)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build()
        else
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setOngoing(true)
                .setContentIntent(openApp)
                .build()

        startForeground(NOTIFICATION_ID, notification)
        nm.notify(NOTIFICATION_ID, notification)
        return true
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Timer status",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows whether the timer is running or paused"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun formatElapsed(totalMs: Long): String {
        val s = totalMs / 1000
        val h = s / 3600; val m = (s % 3600) / 60; val ss = s % 60
        return if (h > 0) "${h}h ${m}m ${ss}s" else if (m > 0) "${m}m ${ss}s" else "${ss}s"
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
