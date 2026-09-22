package com.example.hourstracker

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.example.hourstracker.model.WorkSession
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes a project's .xlsx workbook, straight from the database.
 *
 * This lives outside MainActivity because the 3x1 widget books sessions without
 * opening the app: it used to insert the row and never touch the file, so every
 * widget-stopped session was silently missing from the workbook until the user
 * happened to edit some task. Both paths now call [exportSite].
 *
 * The MediaStore write/delete helpers live here too so there is exactly one
 * definition of "where exports go and what they're called" — the writer, the
 * overwrite-cleanup and the project-delete have to agree on the filename, and
 * they previously did not (the old sanitiser replaced two backslashes).
 */
object ProjectWorkbook {

    const val SUBDIR = "Tasks"

    /** Filename for a project's exported workbook. */
    fun name(siteName: String): String =
        siteName.replace("/", "-").replace("\\", "-").trim().ifBlank { "project" } + ".xlsx"

    /**
     * Writes (or removes) only this project's workbook. Returns true when the
     * file is in place, false when the project has no tasks left (its stale file
     * is removed) or the write failed.
     */
    fun exportSite(context: Context, siteId: Int): Boolean {
        val db = HoursDb(context)
        val siteName: String
        val sessions: List<WorkSession>
        try {
            siteName = db.readableDatabase.rawQuery(
                "SELECT name FROM job_sites WHERE id=?", arrayOf(siteId.toString())
            ).use { c -> if (c.moveToNext()) c.getString(0) ?: "" else return false }
            val list = mutableListOf<WorkSession>()
            db.readableDatabase.rawQuery(
                "SELECT id, job_site_id, date, start_time, end_time, break_minutes, notes " +
                    "FROM work_sessions WHERE job_site_id=? ORDER BY date ASC, start_time ASC",
                arrayOf(siteId.toString())
            ).use { c ->
                while (c.moveToNext()) list.add(WorkSession(
                    id = c.getInt(0),
                    jobSiteId = c.getInt(1),
                    date = c.getString(2) ?: "",
                    startTime = c.getString(3) ?: "",
                    endTime = c.getString(4) ?: "",
                    breakMinutes = c.getInt(5),
                    notes = c.getString(6)
                ))
            }
            sessions = list
        } finally {
            db.close()
        }

        val filename = name(siteName)
        if (sessions.isEmpty()) {
            delete(context, SUBDIR, filename)
            return false
        }
        deleteCopies(context, SUBDIR, filename)
        write(context, SUBDIR, filename, xlsx(listOf("Task" to rows(sessions))))
        return true
    }

    // ---- Table rows: Date, Start, End, Break (min), Worked (min), Notes ----

    fun rows(sessions: List<WorkSession>): List<List<String>> {
        val out = mutableListOf(listOf("Date", "Start", "End", "Break (min)", "Worked (min)", "Notes"))
        sessions.forEach { s ->
            val brk = breakMinutes(s)
            val worked = (spanMinutes(s) - brk).coerceAtLeast(0)
            out.add(listOf(
                isoDisplay(s.date), time12(s.startTime), time12(s.endTime),
                brk.toString(), worked.toString(), s.notes ?: ""
            ))
        }
        return out
    }

    /** Wall-clock span of a task in minutes; 0 when a time is unparseable. */
    fun spanMinutes(s: WorkSession): Int {
        val sm = timeOfDay(s.startTime) ?: return 0
        val em = timeOfDay(s.endTime) ?: return 0
        var diff = em - sm
        if (diff < 0) diff += 24 * 3600
        return diff / 60
    }

    /** A break never exceeds the shift it belongs to. */
    fun breakMinutes(s: WorkSession): Int = s.breakMinutes.coerceIn(0, spanMinutes(s))

    private fun timeOfDay(t: String?): Int? = try {
        LocalTime.parse(t!!.trim()).toSecondOfDay()
    } catch (e: Exception) {
        null
    }

    fun time12(hhmm: String): String {
        val p = hhmm.trim().split(":")
        if (p.size < 2) return hhmm
        val h = p.getOrNull(0)?.toIntOrNull() ?: 0
        val m = p.getOrNull(1)?.toIntOrNull() ?: 0
        val am = h < 12
        var hh = h % 12; if (hh == 0) hh = 12
        return "$hh:${String.format(Locale.US, "%02d", m)} ${if (am) "AM" else "PM"}"
    }

    fun isoDisplay(iso: String): String = try {
        val d = LocalDate.parse(iso)
        "${String.format(Locale.US, "%02d", d.monthValue)}/${String.format(Locale.US, "%02d", d.dayOfMonth)}/${d.year}"
    } catch (e: Exception) {
        iso
    }

    // ---- Minimal xlsx (Office Open XML) writer ----

    fun xlsx(sheets: List<Pair<String, List<List<String>>>>): ByteArray {
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
                        val col = columnLetter(ci)
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

    /** 0 -> "A" ... 25 -> "Z", 26 -> "AA". The old A+ci trick broke past column Z. */
    private fun columnLetter(index: Int): String {
        var i = index
        val sb = StringBuilder()
        while (true) {
            sb.insert(0, ('A'.code + (i % 26)).toChar())
            i = i / 26 - 1
            if (i < 0) break
        }
        return sb.toString()
    }

    fun xmlEscape(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    // ---- Downloads/HoursTracker/<subdir>/<filename> ----

    private fun relFolder(subdir: String): String =
        "Download/HoursTracker/${if (subdir.isBlank()) "" else "$subdir/"}"

    /** Display path of an export, e.g. "Downloads/HoursTracker/Tasks/Job.xlsx". */
    fun displayPath(subdir: String, filename: String): String =
        "Downloads/HoursTracker/${if (subdir.isBlank()) "" else "$subdir/"}$filename"

    /**
     * Writes bytes into Downloads/HoursTracker/<subdir>/<filename> and returns the
     * display path plus a shareable Uri. Uses MediaStore on Android 10+ so the OS
     * creates and indexes the nested folder under Downloads; falls back to a plain
     * File on older versions.
     */
    fun write(context: Context, subdir: String, filename: String, bytes: ByteArray): Pair<String, Uri?> {
        val rel = relFolder(subdir)
        if (Build.VERSION.SDK_INT >= 29) {
            // Reuse the existing entry when present so repeated writes overwrite the
            // same file instead of MediaStore creating "Name (1).xlsx" copies.
            val existing = context.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH}=?",
                arrayOf(filename, rel), null
            )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
            val uri: Uri
            if (existing != null) {
                uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, existing)
                context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                    ?: error("Could not open output stream")
            } else {
                val cv = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, filename)
                    put(MediaStore.Downloads.RELATIVE_PATH, rel)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                    ?: error("Could not create file entry")
                try {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                        ?: error("Could not open output stream")
                    cv.clear()
                    cv.put(MediaStore.Downloads.IS_PENDING, 0)
                    context.contentResolver.update(uri, cv, null, null)
                } catch (e: Exception) {
                    context.contentResolver.delete(uri, null, null)
                    throw e
                }
            }
            return displayPath(subdir, filename) to uri
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "HoursTracker")
            val sub = if (subdir.isBlank()) dir else File(dir, subdir)
            sub.mkdirs()
            val f = File(sub, filename)
            f.writeBytes(bytes)
            return displayPath(subdir, filename) to Uri.fromFile(f)
        }
    }

    /** Removes a previously exported file. Non-fatal if it isn't there. */
    fun delete(context: Context, subdir: String, filename: String) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val sel = "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH}=?"
                context.contentResolver.delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI, sel, arrayOf(filename, relFolder(subdir)))
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "HoursTracker")
                val sub = if (subdir.isBlank()) dir else File(dir, subdir)
                File(sub, filename).delete()
            }
        } catch (e: Exception) {
            // The file may not exist or the OS may block deletion.
        }
    }

    /** Deletes "Name (1).xlsx"-style entries left by earlier duplicate inserts. */
    fun deleteCopies(context: Context, subdir: String, canonicalName: String) {
        if (Build.VERSION.SDK_INT < 29) return
        try {
            val base = canonicalName.removeSuffix(".xlsx")
            // The project name is literal data: % and _ inside it must be escaped or
            // LIKE treats them as wildcards ("Job_1" would match "JobX1 (1).xlsx").
            val literal = base.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
            val pattern = "$literal (%).xlsx"
            val sel = "${MediaStore.Downloads.DISPLAY_NAME} LIKE ? ESCAPE '\\' AND ${MediaStore.Downloads.RELATIVE_PATH}=?"
            val ids = mutableListOf<Long>()
            context.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Downloads._ID), sel, arrayOf(pattern, relFolder(subdir)), null
            )?.use { c -> while (c.moveToNext()) ids.add(c.getLong(0)) }
            ids.forEach { id ->
                context.contentResolver.delete(
                    ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id), null, null)
            }
        } catch (e: Exception) {
            // Non-fatal cleanup.
        }
    }
}