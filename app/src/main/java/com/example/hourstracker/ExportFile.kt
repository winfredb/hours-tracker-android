package com.example.hourstracker

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * Puts a generated file into Downloads/HoursTracker/<subdir>/<filename> and hands
 * back its display path plus a shareable Uri.
 *
 * This used to be [ProjectWorkbook], which also built and wrote per-project .xlsx
 * workbooks on every task save. That export is gone: the database is the record,
 * the user's JSON backup is the portable copy, and this is now only the sink for
 * the summary PDF. Keeping one definition of where exports go means the writer
 * and anything that later deletes or opens them cannot disagree about the path.
 *
 * Uses MediaStore on Android 10+ so the OS creates and indexes the nested folder
 * under Downloads; falls back to a plain File on API 26-28.
 */
object ExportFile {

    /** Display path of an export, e.g. "Downloads/HoursTracker/Export/Summary.pdf". */
    fun displayPath(subdir: String, filename: String): String =
        "Downloads/HoursTracker/${if (subdir.isBlank()) "" else "$subdir/"}$filename"

    private fun relFolder(subdir: String): String =
        "Download/HoursTracker/${if (subdir.isBlank()) "" else "$subdir/"}"

    /**
     * Writes bytes and returns the display path plus a shareable Uri. An existing
     * entry is reused so re-exporting overwrites the same file instead of
     * MediaStore creating "Name (1).pdf" copies.
     */
    fun write(context: Context, subdir: String, filename: String, bytes: ByteArray): Pair<String, Uri?> {
        val rel = relFolder(subdir)
        if (Build.VERSION.SDK_INT >= 29) {
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
                context.contentResolver.delete(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, sel, arrayOf(filename, relFolder(subdir))
                )
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "HoursTracker")
                val sub = if (subdir.isBlank()) dir else File(dir, subdir)
                File(sub, filename).delete()
            }
        } catch (e: Exception) {
            // The file may not exist or the OS may block deletion.
        }
    }
}
