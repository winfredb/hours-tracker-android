package com.example.hourstracker

import android.content.Context
import android.content.SharedPreferences
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * The single source of truth for the wall-clock timer.
 *
 * Every key lives in the "hours_tracker" preferences and every transition is
 * written there, so MainActivity, the 3x1 widget and TimerService all read the
 * same state — even across process death. MainActivity used to carry a private
 * copy of this state machine and the widget carried another, which is how the
 * pause handling below drifted out of sync.
 *
 * Wall time and WORKED time are not the same thing:
 *   elapsedMs()  = worked time: paused spans are excluded.
 *   a booked task = startedAt -> now, with the paused spans stored as its break.
 * Before this, stopping after a pause booked startedAt->now with break 0, so
 * two paused hours were silently counted as work.
 */
object Clock {

    const val PREFS = "hours_tracker"
    private const val HHMM = "HH:mm"

    private const val K_RUNNING = "clockRunning"
    private const val K_PAUSED = "clockPaused"
    private const val K_STARTED_AT = "startedAt"
    private const val K_SEGMENT_START = "segmentStartMs"
    private const val K_ACCUMULATED = "accumulatedMs"
    private const val K_PAUSED_ACCUM = "pausedAccumMs"
    private const val K_PAUSE_START = "pauseStartedMs"
    private const val K_ACTIVE_JOB = "activeJobId"

    /** Snapshot of the persisted clock state. */
    data class State(
        val running: Boolean = false,
        val paused: Boolean = false,
        val startedAt: String = "",
        val segmentStartMs: Long = 0L,
        val accumulatedMs: Long = 0L,
        val pausedAccumMs: Long = 0L,
        val pauseStartedMs: Long = 0L,
        val activeJobId: Int = -1
    )

    /** What a stopped clock hands to the session writer. */
    data class Booked(val startedAt: String, val endTime: String, val breakMinutes: Int)

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun read(p: SharedPreferences): State = State(
        running = p.getBoolean(K_RUNNING, false),
        paused = p.getBoolean(K_PAUSED, false),
        startedAt = p.getString(K_STARTED_AT, "") ?: "",
        segmentStartMs = p.getLong(K_SEGMENT_START, 0L),
        accumulatedMs = p.getLong(K_ACCUMULATED, 0L),
        pausedAccumMs = p.getLong(K_PAUSED_ACCUM, 0L),
        pauseStartedMs = p.getLong(K_PAUSE_START, 0L),
        activeJobId = p.getInt(K_ACTIVE_JOB, -1)
    )

    fun write(p: SharedPreferences, s: State) {
        p.edit()
            .putBoolean(K_RUNNING, s.running)
            .putBoolean(K_PAUSED, s.paused)
            .putString(K_STARTED_AT, s.startedAt)
            .putLong(K_SEGMENT_START, s.segmentStartMs)
            .putLong(K_ACCUMULATED, s.accumulatedMs)
            .putLong(K_PAUSED_ACCUM, s.pausedAccumMs)
            .putLong(K_PAUSE_START, s.pauseStartedMs)
            .putInt(K_ACTIVE_JOB, s.activeJobId)
            .apply()
    }

    /** Worked milliseconds: accumulated + the live segment, paused time excluded. */
    fun elapsedMs(p: SharedPreferences): Long {
        val s = read(p)
        if (!s.running) return 0L
        if (s.paused || s.segmentStartMs == 0L) return s.accumulatedMs
        return s.accumulatedMs + (System.currentTimeMillis() - s.segmentStartMs)
    }

    /** Total paused milliseconds, including a pause that is still in progress. */
    fun pausedMs(p: SharedPreferences): Long {
        val s = read(p)
        if (!s.running) return 0L
        if (s.paused && s.pauseStartedMs != 0L) {
            return s.pausedAccumMs + (System.currentTimeMillis() - s.pauseStartedMs)
        }
        return s.pausedAccumMs
    }

    /** Elapsed whole minutes spent paused — the booked task's break. */
    fun pausedMinutes(p: SharedPreferences): Int = (pausedMs(p) / 60000L).toInt()

    fun nowHhMm(): String = LocalTime.now().format(DateTimeFormatter.ofPattern(HHMM))

    /** Start from idle against [jobSiteId], discarding any previous state. */
    fun start(p: SharedPreferences, jobSiteId: Int) {
        write(p, State(
            running = true,
            paused = false,
            startedAt = nowHhMm(),
            segmentStartMs = System.currentTimeMillis(),
            accumulatedMs = 0L,
            pausedAccumMs = 0L,
            pauseStartedMs = 0L,
            activeJobId = jobSiteId
        ))
    }

    /** Bank the running segment, then open a pause span. No-op when already paused. */
    fun pause(p: SharedPreferences) {
        val s = read(p)
        if (!s.running || s.paused) return
        write(p, s.copy(
            paused = true,
            accumulatedMs = elapsedMs(p),
            segmentStartMs = 0L,
            pauseStartedMs = System.currentTimeMillis()
        ))
    }

    /**
     * Close the open pause span (adding it to the break total) and run on.
     * When [pauseMinutes] is provided it overrides the wall-clock pause length
     * for this span — used when the user edits the pause time on resume.
     */
    fun resume(p: SharedPreferences, pauseMinutes: Int? = null) {
        val s = read(p)
        if (!s.running || !s.paused) return
        val extra = when {
            pauseMinutes != null -> pauseMinutes * 60000L
            s.pauseStartedMs == 0L -> 0L
            else -> System.currentTimeMillis() - s.pauseStartedMs
        }
        write(p, s.copy(
            paused = false,
            segmentStartMs = System.currentTimeMillis(),
            pausedAccumMs = s.pausedAccumMs + extra.coerceAtLeast(0),
            pauseStartedMs = 0L
        ))
    }

    /** Stop and reset, returning the data needed to book the session. */
    fun stop(p: SharedPreferences): Booked {
        val s = read(p)
        val b = Booked(s.startedAt, nowHhMm(), pausedMinutes(p))
        write(p, State(activeJobId = s.activeJobId))
        return b
    }
}