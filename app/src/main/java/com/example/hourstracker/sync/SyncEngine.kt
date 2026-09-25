package com.example.hourstracker.sync

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.example.hourstracker.HoursDb
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Pushes the worker's local data to the PocketBase backend (office source of
 * truth). Pure java + org.json, blocking — run on a background thread.
 *
 * Mapping (local → server):
 *   job_sites    → projects      (matched by name to avoid duplicates)
 *   work_sessions → time_entries (start/end from local date+time, user = the
 *                                 signed-in worker, project = mapped project id)
 *
 * Retry queue: a local row is marked synced by its `server_id` column (the
 * PocketBase record id we got back). For work_sessions, NULL server_id = not
 * yet sent = queued for the next [sync] call. For job_sites, server_id is set
 * once the project exists server-side so subsequent sessions in that project
 * reuse it instead of re-creating it. If any call fails we stop and leave the
 * remaining rows unsynced; the caller retries later.
 */
class SyncEngine(
    private val db: HoursDb,
    private val api: ApiClient = ApiClient(),
    private val token: String,
    private val workerUserId: String
) {

    /** True if there are work_sessions waiting to sync (nothing has been sent yet). */
    fun hasPendingSessions(): Boolean {
        db.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM work_sessions WHERE server_id IS NULL", null
        ).use { c -> return c.moveToFirst() && c.getInt(0) > 0 }
    }

    /**
     * Push every unsynced session (and create any missing projects). Returns a
     * short human summary of what happened this call. Throws [SyncException] on
     * the first failure.
     */
    fun sync(): String {
        val wdb = db.writableDatabase
        var pushedSessions = 0
        var createdProjects = 0

        // ---- read all job sites (need local fields + server_id) ----
        val siteById = HashMap<Int, SiteRow>()
        wdb.rawQuery("SELECT id, name, location, employer, hourly_wage, drive_minutes, server_id FROM job_sites", null).use { c ->
            while (c.moveToNext()) {
                val idxName = c.getColumnIndexOrThrow("name")
                val idxLoc = c.getColumnIndex("location")
                val idxEmp = c.getColumnIndex("employer")
                val idxWage = c.getColumnIndex("hourly_wage")
                val idxDrive = c.getColumnIndex("drive_minutes")
                val idxSid = c.getColumnIndex("server_id")
                siteById[c.getInt(c.getColumnIndexOrThrow("id"))] = SiteRow(
                    name = c.getString(idxName),
                    location = if (idxLoc >= 0 && !c.isNull(idxLoc)) c.getString(idxLoc) else null,
                    employer = if (idxEmp >= 0 && !c.isNull(idxEmp)) c.getString(idxEmp) else null,
                    hourlyWage = if (idxWage >= 0 && !c.isNull(idxWage)) c.getDouble(idxWage) else null,
                    driveMinutes = if (idxDrive >= 0 && !c.isNull(idxDrive)) c.getInt(idxDrive) else 0,
                    serverId = if (idxSid >= 0 && !c.isNull(idxSid)) c.getString(idxSid) else null
                )
            }
        }

        // ---- pending sessions ----
        val pending = mutableListOf<List<Any>>() // Int id, Int siteId
        wdb.rawQuery("SELECT id, job_site_id FROM work_sessions WHERE server_id IS NULL", null).use { c ->
            while (c.moveToNext()) {
                pending.add(listOf(
                    c.getInt(c.getColumnIndexOrThrow("id")),
                    c.getInt(c.getColumnIndexOrThrow("job_site_id"))
                ))
            }
        }

        for (entry in pending) {
            val localId = entry[0] as Int
            val siteId = entry[1] as Int

            val site = siteById[siteId]
                ?: throw SyncException("Session #$localId references a missing job site")

            // ensure the project exists server-side
            val projectId = site.serverId ?: findOrCreateProject(wdb, site).also {
                createdProjects++
                siteById[siteId] = site.copy(serverId = it)
            }

            val sess = readSession(wdb, localId)
            pushSession(wdb, localId, projectId, sess)
            pushedSessions++
        }

        // ---- propagate detail edits for already-linked projects -------------
        // Even with no new sessions, sync the employer / hourly wage / drive
        // time for every site that already has a server project, so local edits
        // reach the office (the office dashboard reads these fields).
        var updatedProjects = 0
        for ((_, site) in siteById) {
            val pid = site.serverId
            if (!pid.isNullOrEmpty()) {
                updateProjectDetails(pid, site)
                updatedProjects++
            }
        }

        return when {
            pushedSessions > 0 && createdProjects > 0 ->
                "Synced $pushedSessions session" + (if (pushedSessions == 1) "" else "s") +
                    " and created $createdProjects project" + (if (createdProjects == 1) "" else "s") + "."
            pushedSessions > 0 ->
                "Synced $pushedSessions session" + (if (pushedSessions == 1) "" else "s") + "."
            createdProjects > 0 ->
                "Created $createdProjects project" + (if (createdProjects == 1) "" else "s") + "."
            updatedProjects > 0 ->
                "Updated $updatedProjects project detail${if (updatedProjects == 1) "" else "s"}."
            else -> "Nothing to sync."
        }
    }

    /**
     * Pull the worker's OWN entries down from the backend onto this device.
     * This is the restore/merge path (new phone, reinstall, or office edits):
     * server records that don't already exist locally are written into the
     * local DB so the worker sees their full history without a manual restore.
     *
     * Server rows for OTHER workers are never returned (RLS gates listing to
     * `user.id = @request.auth.id`). A row already present locally (matched by
     * server_id) is skipped, so pull is idempotent and never duplicates an
     * entry that was already pushed up.
     *
     * Returns a short human summary. Throws [SyncException] on the first
     * failure. Run off the main thread.
     */
    fun pull(): String {
        val wdb = db.writableDatabase

        // ---- fetch server projects (id → project fields) to map server entries
        // back to a local job_site and restore project details. The worker can
        // list projects. ----
        val projects = HashMap<String, JSONObject>() // serverProjectId → project
        val projResp = api.get("${SyncConfig.PATH_PROJECTS}?perPage=200", token)
        val projItems = projResp.optJSONArray("items")
            ?: throw SyncException("Bad projects response")
        for (i in 0 until projItems.length()) {
            val p = projItems.getJSONObject(i)
            val id = p.optString("id")
            if (id.isNotEmpty() && p.optString("name").isNotEmpty()) projects[id] = p
        }

        // ---- fetch this worker's time entries (RLS already scopes to them) ----
        val resp = api.get("${SyncConfig.PATH_TIME_ENTRIES}?perPage=200", token)
        val items = resp.optJSONArray("items")
            ?: throw SyncException("Bad time_entries response")

        var pulled = 0
        var createdSites = 0

        for (i in 0 until items.length()) {
            val e = items.getJSONObject(i)
            val serverId = e.optString("id")
            val projectId = e.optString("project")
            if (serverId.isEmpty()) continue

            // skip if already local (dedup by server_id)
            if (hasLocalSession(serverId)) continue

            // resolve local job site for this entry's server project
            val proj = projects[projectId]
            val site = if (proj == null) null
            else findOrCreateLocalSite(wdb, projectId, proj)
            if (site == null) continue // can't place the entry without a site
            val siteId = site.first
            if (site.second) createdSites++

            // convert UTC start/end back to the store's local "yyyy-MM-dd" + "HH:MM"
            val startLocal = fromUtc(e.optString("start"))
            val endLocal = fromUtc(e.optString("end"))

            val cv = ContentValues().apply {
                put("job_site_id", siteId)
                put("date", startLocal.first)
                put("start_time", startLocal.second)
                put("end_time", endLocal.second)
                put("break_minutes", e.optInt("break_minutes"))
                if (!e.isNull("note")) put("notes", e.optString("note"))
                put("server_id", serverId)
            }
            wdb.insert("work_sessions", null, cv)
            pulled++
        }

        return when {
            pulled > 0 && createdSites > 0 ->
                "Pulled $pulled entr${if (pulled == 1) "y" else "ies"} and restored $createdSites project${if (createdSites == 1) "" else "s"} to your history."
            pulled > 0 ->
                "Pulled $pulled entr${if (pulled == 1) "y" else "ies"} into your history."
            else -> "Nothing new on the server."
        }
    }

    /** True if a local work_session already carries [serverId]. */
    private fun hasLocalSession(serverId: String): Boolean {
        db.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM work_sessions WHERE server_id=?", arrayOf(serverId)
        ).use { c -> return c.moveToFirst() && c.getInt(0) > 0 }
    }

    /**
     * Find the local job_site backing a server project (by server_id first,
     * then by name), creating it if needed. Project detail fields (employer,
     * hourly wage, drive minutes) are restored onto the local site so a fresh
     * install/reinstall gets the same project metadata as the server. Returns
     * (site id, wasCreated). Returns null if the site couldn't be resolved or
     * created.
     */
    private fun findOrCreateLocalSite(wdb: SQLiteDatabase, projectId: String, proj: JSONObject): Pair<Int, Boolean>? {
        val name = proj.optString("name")
        val employer = proj.optString("employer").takeIf { it.isNotBlank() }
        val hourlyWage = if (proj.has("hourly_wage") && !proj.isNull("hourly_wage")) proj.optDouble("hourly_wage", Double.NaN).takeIf { !it.isNaN() } else null
        val driveMinutes = if (proj.has("drive_minutes") && !proj.isNull("drive_minutes")) proj.optInt("drive_minutes") else 0
        val site = proj.optString("site").takeIf { it.isNotBlank() }

        // by server_id
        wdb.rawQuery("SELECT id FROM job_sites WHERE server_id=? LIMIT 1", arrayOf(projectId)).use { c ->
            if (c.moveToFirst()) {
                val id = c.getInt(c.getColumnIndexOrThrow("id"))
                restoreSiteDetails(wdb, id, employer, hourlyWage, driveMinutes, site)
                return id to false
            }
        }
        // by name (a project the worker already has locally under this name)
        wdb.rawQuery("SELECT id FROM job_sites WHERE name=? LIMIT 1", arrayOf(name)).use { c ->
            if (c.moveToFirst()) {
                val id = c.getInt(c.getColumnIndexOrThrow("id"))
                wdb.execSQL("UPDATE job_sites SET server_id=? WHERE id=?", arrayOf(projectId, id.toString()))
                restoreSiteDetails(wdb, id, employer, hourlyWage, driveMinutes, site)
                return id to false
            }
        }
        // create it (mirrors the app's addSite defaults + restored details)
        val cv = ContentValues().apply {
            put("name", name)
            put("color", "#059669")
            put("drive_minutes", driveMinutes)
            put("server_id", projectId)
            employer?.let { put("employer", it) }
            hourlyWage?.let { put("hourly_wage", it) }
            site?.let { put("location", it) }
        }
        val id = wdb.insert("job_sites", null, cv)
        return if (id > 0) id.toInt() to true else null
    }

    /**
     * Non-destructively fill in project detail fields on an existing local
     * site from the server (only overwrites an empty/null local value, so a
     * worker's own local edits win over the pulled copy).
     */
    private fun restoreSiteDetails(wdb: SQLiteDatabase, siteId: Int, employer: String?, hourlyWage: Double?, driveMinutes: Int, location: String?) {
        // read current local values for the nullable fields we want to backfill
        var curEmp: String? = null
        var curWage: Double? = null
        var curLoc: String? = null
        wdb.rawQuery("SELECT employer, hourly_wage, location FROM job_sites WHERE id=?", arrayOf(siteId.toString())).use { c ->
            if (c.moveToFirst()) {
                val ie = c.getColumnIndex("employer"); if (ie >= 0 && !c.isNull(ie)) curEmp = c.getString(ie)
                val iw = c.getColumnIndex("hourly_wage"); if (iw >= 0 && !c.isNull(iw)) curWage = c.getDouble(iw)
                val il = c.getColumnIndex("location"); if (il >= 0 && !c.isNull(il)) curLoc = c.getString(il)
            }
        }
        val cv = ContentValues()
        if (curEmp.isNullOrEmpty() && !employer.isNullOrEmpty()) cv.put("employer", employer)
        if (curWage == null && hourlyWage != null) cv.put("hourly_wage", hourlyWage)
        if (curLoc.isNullOrEmpty() && !location.isNullOrEmpty()) cv.put("location", location)
        // drive_minutes always fills (local default is 0)
        if (driveMinutes != 0) cv.put("drive_minutes", driveMinutes)
        if (cv.size() > 0) wdb.update("job_sites", cv, "id=?", arrayOf(siteId.toString()))
    }

    /**
     * Convert a UTC RFC3339/ISO instant (e.g. "2026-09-21T13:00:00.000Z") back
     * to the store's local representation: a Pair("yyyy-MM-dd", "HH:MM").
     * Also tolerates PocketBase's space-separated "2026-09-21 13:00:00.000Z".
     */
    private fun fromUtc(utc: String): Pair<String, String> {
        val cleaned = utc.trim().replace(' ', 'T')
        val zdt = if (cleaned.endsWith("Z")) ZonedDateTime.ofInstant(Instant.parse(cleaned), ZoneId.systemDefault())
        else ZonedDateTime.parse(cleaned)
        val date = zdt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        val time = zdt.format(DateTimeFormatter.ofPattern("HH:mm"))
        return date to time
    }

    // ---- project mapping ---------------------------------------------------

    data class SiteRow(
        val name: String,
        val location: String?,
        val employer: String?,
        val hourlyWage: Double?,
        val driveMinutes: Int,
        val serverId: String?
    )

    /** Project write payload: the detail fields we sync both ways. */
    private fun projectBody(site: SiteRow): JSONObject = JSONObject().apply {
        put("name", site.name)
        if (!site.location.isNullOrBlank()) put("site", site.location)
        if (!site.employer.isNullOrBlank()) put("employer", site.employer)
        if (site.hourlyWage != null) put("hourly_wage", site.hourlyWage)
        if (site.driveMinutes != 0) put("drive_minutes", site.driveMinutes)
    }

    /** Find a server project by exact name, else create it. Returns its id. */
    private fun findOrCreateProject(wdb: SQLiteDatabase, site: SiteRow): String {
        // list projects (worker can list; createRule now allows worker create)
        val resp = api.get(SyncConfig.PATH_PROJECTS, token)
        val items = resp.optJSONArray("items")
            ?: throw SyncException("Bad projects response")
        for (i in 0 until items.length()) {
            val p = items.getJSONObject(i)
            if (p.optString("name") == site.name) {
                val id = p.optString("id")
                if (id.isNotEmpty()) {
                    rememberProject(wdb, site.name, id)
                    return id
                }
            }
        }
        // not found → create
        val created = api.post(SyncConfig.PATH_PROJECTS, projectBody(site), token)
        val id = created.optString("id")
        if (id.isEmpty()) throw SyncException("Project created without an id")
        rememberProject(wdb, site.name, id)
        return id
    }

    /**
     * Push detail edits (employer, hourly wage, drive time) for a project that
     * already exists server-side. Runs when a site is already linked (server_id
     * set) so local edits propagate without re-creating the project.
     */
    private fun updateProjectDetails(projectId: String, site: SiteRow) {
        api.patch("${SyncConfig.PATH_PROJECTS}/$projectId", projectBody(site), token)
    }

    /** Persist server_id on the matching local job_site (by name). */
    private fun rememberProject(wdb: SQLiteDatabase, name: String, serverId: String) {
        wdb.execSQL(
            "UPDATE job_sites SET server_id=? WHERE name=? AND (server_id IS NULL OR server_id='')",
            arrayOf(serverId, name)
        )
    }

    // ---- session push ------------------------------------------------------

    data class SessionRow(val date: String, val startTime: String, val endTime: String, val breakMin: Int, val notes: String?)

    private fun readSession(wdb: SQLiteDatabase, localId: Int): SessionRow {
        wdb.rawQuery("SELECT date, start_time, end_time, break_minutes, notes FROM work_sessions WHERE id=?", arrayOf(localId.toString())).use { c ->
            if (!c.moveToFirst()) throw SyncException("Session #$localId disappeared")
            val idxN = c.getColumnIndex("notes")
            return SessionRow(
                date = c.getString(c.getColumnIndexOrThrow("date")),
                startTime = c.getString(c.getColumnIndexOrThrow("start_time")),
                endTime = c.getString(c.getColumnIndexOrThrow("end_time")),
                breakMin = c.getInt(c.getColumnIndexOrThrow("break_minutes")),
                notes = if (idxN >= 0 && !c.isNull(idxN)) c.getString(idxN) else null
            )
        }
    }

    /** Convert a local "yyyy-MM-dd" + "HH:MM" to a UTC RFC3339 instant string. */
    private fun toUtc(date: String, time: String): String {
        val d = LocalDate.parse(date)
        val t = LocalTime.parse(time.let { if (it.length == 5) "$it:00" else it })
        val zdt = ZonedDateTime.of(d, t, ZoneId.systemDefault())
        return zdt.withZoneSameInstant(ZoneId.of("UTC")).format(DateTimeFormatter.ISO_INSTANT)
    }

    private fun pushSession(wdb: SQLiteDatabase, localId: Int, projectId: String, s: SessionRow) {
        val body = JSONObject()
            .put("user", workerUserId)
            .put("project", projectId)
            .put("start", toUtc(s.date, s.startTime))
            .put("end", toUtc(s.date, s.endTime))
            .put("break_minutes", s.breakMin)
        if (!s.notes.isNullOrBlank()) body.put("note", s.notes)

        val created = api.post(SyncConfig.PATH_TIME_ENTRIES, body, token)
        val serverId = created.optString("id")
        if (serverId.isEmpty()) throw SyncException("Entry created without an id")
        wdb.execSQL("UPDATE work_sessions SET server_id=? WHERE id=?", arrayOf(serverId, localId.toString()))
    }
}