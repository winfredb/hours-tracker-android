package com.example.hourstracker

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Whole-app backup and restore: every project and every task, as one JSON file
 * the user saves somewhere they choose (Storage Access Framework).
 *
 * Why JSON rather than carrying on with the .xlsx workbooks: a backup has to be
 * read back exactly, and xlsx cannot guarantee that. It's a ZIP of XML whose shape
 * is owned by whatever app last saved it — opening a workbook in Excel and hitting
 * Save re-encodes cells as shared strings and adds parts our parser would have to
 * chase. JSON round-trips losslessly, tolerates fields we don't know yet, and is
 * plain text the user can inspect.
 *
 * The database stays the single source of truth; this is a point-in-time copy of
 * it. Nothing in the app reads a backup except an explicit Restore.
 */
object Backup {

    /** Bumped only if the on-disk shape changes incompatibly. */
    const val FORMAT = 1
    private const val KIND = "HoursTracker"

    /** Counts reported back to the UI after a restore. */
    data class Result(val projects: Int, val tasks: Int, val skipped: Int)

    // ------------------------------------------------------------------ write

    /**
     * Serialises the whole database plus the user's settings. Reads the raw
     * column values rather than the display models so a wage of 42.5 is stored as
     * 42.5 and not as the formatted string "42.5" / "42.50".
     */
    fun build(context: Context, prefs: SharedPreferences): String {
        val db = HoursDb(context)
        val projects = JSONArray()
        val tasks = JSONArray()
        try {
            db.readableDatabase.rawQuery(
                "SELECT id, name, location, employer, hourly_wage, color FROM job_sites ORDER BY id", null
            ).use { c ->
                while (c.moveToNext()) {
                    val o = JSONObject()
                    o.put("id", c.getInt(0))
                    o.put("name", c.getString(1) ?: "")
                    o.put("location", if (c.isNull(2)) JSONObject.NULL else c.getString(2))
                    o.put("employer", if (c.isNull(3)) JSONObject.NULL else c.getString(3))
                    // Numeric, and null-safe: a project with no wage keeps no wage.
                    o.put("hourlyWage", if (c.isNull(4)) JSONObject.NULL else c.getDouble(4))
                    o.put("color", c.getString(5) ?: "#6750A4")
                    projects.put(o)
                }
            }
            db.readableDatabase.rawQuery(
                "SELECT id, job_site_id, date, start_time, end_time, break_minutes, notes " +
                    "FROM work_sessions ORDER BY id", null
            ).use { c ->
                while (c.moveToNext()) {
                    val o = JSONObject()
                    o.put("id", c.getInt(0))
                    o.put("projectId", c.getInt(1))
                    o.put("date", c.getString(2) ?: "")
                    o.put("startTime", c.getString(3) ?: "")
                    o.put("endTime", c.getString(4) ?: "")
                    o.put("breakMinutes", c.getInt(5))
                    o.put("notes", if (c.isNull(6)) JSONObject.NULL else c.getString(6))
                    tasks.put(o)
                }
            }
        } finally {
            db.close()
        }

        val settings = JSONObject()
        settings.put("otThresholdHours", prefs.getFloat("ot_threshold_hours", 40f).toDouble())
        settings.put("otRate", prefs.getFloat("ot_rate", 1.5f).toDouble())
        settings.put("payPeriodStart", prefs.getString("pay_start", null) ?: JSONObject.NULL)
        settings.put("payPeriodEnd", prefs.getString("pay_end", null) ?: JSONObject.NULL)
        settings.put("theme", prefs.getString("theme", "system") ?: "system")

        return JSONObject().apply {
            put("kind", KIND)
            put("format", FORMAT)
            put("exportedAt", LocalDateTime.now().withNano(0).toString())
            put("settings", settings)
            put("projects", projects)
            put("tasks", tasks)
        }.toString(2)
    }

    /** Suggested filename for the document picker. */
    fun suggestedName(): String = "hours-tracker-backup-${LocalDate.now()}.json"

    /** "N projects, M tasks" — used in the confirmation and result dialogs. */
    fun describe(projects: Int, tasks: Int): String =
        "$projects project${if (projects == 1) "" else "s"}, " +
            "$tasks task${if (tasks == 1) "" else "s"}"

    /** Last-backup label for the Settings row: "never", or "09/22/2026 10:23 AM". */
    fun prettyStamp(iso: String?): String = try {
        iso?.let {
            LocalDateTime.parse(it)
                .format(DateTimeFormatter.ofPattern("MM/dd/yyyy h:mm a", Locale.US))
        } ?: "never"
    } catch (e: Exception) {
        "never"
    }

    // ----------------------------------------------------------------- restore

    /**
     * Replaces the database contents with the backup, in one transaction: either
     * the whole file is applied or nothing changes. The file is fully validated
     * before anything is deleted, so a truncated or unrelated file cannot leave
     * an empty database behind.
     *
     * Project ids are remapped as rows are inserted, so a task always lands on the
     * project it was recorded against even if the ids in the file collide with
     * rows already present.
     */
    fun restore(context: Context, prefs: SharedPreferences, text: String): Result {
        val root = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw IllegalArgumentException("That file isn't a backup — it isn't valid JSON.")
        }
        if (root.optString("kind") != KIND) {
            throw IllegalArgumentException("That file isn't an HoursTracker backup.")
        }
        val format = root.optInt("format", 0)
        if (format > FORMAT) {
            throw IllegalArgumentException("This backup was made by a newer version of the app (format $format).")
        }
        val projects = root.optJSONArray("projects")
            ?: throw IllegalArgumentException("The backup has no projects section.")
        val tasks = root.optJSONArray("tasks") ?: JSONArray()
        if (projects.length() == 0) {
            throw IllegalArgumentException("The backup contains no projects.")
        }

        // Validate every task BEFORE touching the database.
        val incoming = mutableListOf<Triple<JSONObject, String, Pair<String, String>>>()
        var skipped = 0
        for (i in 0 until tasks.length()) {
            val t = tasks.optJSONObject(i)
            if (t == null) { skipped++; continue }
            val start = t.optString("startTime").trim()
            val end = t.optString("endTime").trim()
            val date = t.optString("date").trim()
            if (!isIsoDate(date) || !isHHmm(start) || !isHHmm(end)) {
                skipped++
                continue
            }
            incoming.add(Triple(t, date, start to end))
        }

        val db = HoursDb(context)
        var projectCount = 0
        var taskCount = 0
        try {
            val w = db.writableDatabase
            w.beginTransaction()
            try {
                w.delete("work_sessions", null, null)
                w.delete("job_sites", null, null)

                val idMap = HashMap<Int, Int>()
                for (i in 0 until projects.length()) {
                    val p = projects.optJSONObject(i) ?: continue
                    val name = p.optString("name").trim()
                    if (name.isEmpty()) continue
                    val cv = ContentValues().apply {
                        put("name", name)
                        put("location", p.optStringOrNull("location"))
                        put("employer", p.optStringOrNull("employer"))
                        if (p.isNull("hourlyWage")) putNull("hourly_wage")
                        else put("hourly_wage", p.optDouble("hourlyWage"))
                        put("color", p.optString("color").ifBlank { "#6750A4" })
                    }
                    val newId = w.insert("job_sites", null, cv)
                    if (newId == -1L) continue
                    idMap[p.optInt("id")] = newId.toInt()
                    projectCount++
                }

                incoming.forEach { (t, date, times) ->
                    val newProject = idMap[t.optInt("projectId")]
                    if (newProject == null) {
                        skipped++
                        return@forEach
                    }
                    val cv = ContentValues().apply {
                        put("job_site_id", newProject)
                        put("date", date)
                        put("start_time", times.first)
                        put("end_time", times.second)
                        // A negative break is nonsense; a huge one is clamped on read.
                        put("break_minutes", t.optInt("breakMinutes", 0).coerceAtLeast(0))
                        put("notes", t.optStringOrNull("notes"))
                    }
                    if (w.insert("work_sessions", null, cv) != -1L) taskCount++
                }
                w.setTransactionSuccessful()
            } finally {
                w.endTransaction()
            }
        } finally {
            db.close()
        }

        // Settings are secondary: restore them only when the file carries them.
        root.optJSONObject("settings")?.let { s ->
            prefs.edit().apply {
                if (s.has("otThresholdHours")) putFloat("ot_threshold_hours", s.optDouble("otThresholdHours", 40.0).toFloat())
                if (s.has("otRate")) putFloat("ot_rate", s.optDouble("otRate", 1.5).toFloat())
                if (s.has("payPeriodStart")) {
                    val v = s.optStringOrNull("payPeriodStart")
                    if (v != null) putString("pay_start", v) else remove("pay_start")
                }
                if (s.has("payPeriodEnd")) {
                    val v = s.optStringOrNull("payPeriodEnd")
                    if (v != null) putString("pay_end", v) else remove("pay_end")
                }
                if (s.has("theme")) putString("theme", s.optString("theme", "system"))
            }.apply()
        }

        return Result(projectCount, taskCount, skipped)
    }

    // ------------------------------------------------------------------ helpers

    /** Convenience for the "back up before you wipe" path. */
    fun currentCounts(context: Context): Pair<Int, Int> {
        val db = HoursDb(context)
        return try {
            var p = 0
            var t = 0
            db.readableDatabase.rawQuery("SELECT COUNT(*) FROM job_sites", null).use { c ->
                if (c.moveToFirst()) p = c.getInt(0)
            }
            db.readableDatabase.rawQuery("SELECT COUNT(*) FROM work_sessions", null).use { c ->
                if (c.moveToFirst()) t = c.getInt(0)
            }
            p to t
        } finally {
            db.close()
        }
    }

    /** Absent, JSON null and "" are all "no value" — the DB uses NULL for both. */
    private fun JSONObject.optStringOrNull(key: String): String? {
        if (isNull(key)) return null
        val v = optString(key, "")
        return if (v.isEmpty()) null else v
    }

    fun isIsoDate(s: String): Boolean = try {
        LocalDate.parse(s); true
    } catch (e: Exception) {
        false
    }

    /** Times are stored 24h ("HH:MM"); "7:5" or "7:05 am" is not written by us. */
    fun isHHmm(s: String): Boolean = try {
        LocalTime.parse(s)
        s.length == 5
    } catch (e: Exception) {
        false
    }
}