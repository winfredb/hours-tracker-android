package com.example.hourstracker

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * Writes exports and backups into a fixed, user-visible place:
 *
 *   Downloads/HoursTracker/Export/    summary PDFs
 *   Downloads/HoursTracker/Backup/    JSON backups
 *
 * It uses MediaStore's Downloads collection (Android 10+/SDK 29+) so the OS
 * creates, names, and indexes the nested `HoursTracker/` folder under Download
 * itself — no Storage Access Framework, no folder picker, and no manifest
 * storage permission required. An existing entry is reused so re-writing the
 * same file overwrites it instead of MediaStore making "Name (1).pdf" copies.
 *
 * This replaces the earlier SAF-based implementation, which could not create
 * subfolders inside a user-picked tree on this runtime (the provider did not
 * propagate the write grant to folders the app created inside it, surfacing as
 * a "Permission Denial"). MediaStore Downloads is the approach that works.
 */
object ExportFile {

    /** Display path root used in messages, e.g. "Downloads/HoursTracker/Export/...". */
    fun rootLabel(context: Context, prefs: SharedPreferences): String = "Downloads/HoursTracker"

    /** Display-ish path for messages, e.g. "Export/Summary_2026-09-23.pdf". */
    fun displayPath(subdir: String, filename: String): String =
        "${if (subdir.isBlank()) "" else "$subdir/"}$filename"

    private fun relFolder(subdir: String): String =
        "Download/HoursTracker/${if (subdir.isBlank()) "" else "$subdir/"}"

    /**
     * Writes bytes into Downloads/HoursTracker/<subdir>/<filename>, reusing an
     * existing entry so re-exporting overwrites instead of duplicating. Returns
     * the content Uri, or null (with a logged error) on failure.
     */
    private fun writeMedia(context: Context, subdir: String, filename: String, bytes: ByteArray): Uri? {
        val rel = relFolder(subdir)
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val existing = context.contentResolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Downloads._ID),
                    "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH}=?",
                    arrayOf(filename, rel), null
                )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
                if (existing != null) {
                    val uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, existing)
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                        ?: return null
                    uri
                } else {
                    val cv = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, filename)
                        put(MediaStore.Downloads.RELATIVE_PATH, rel)
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                        ?: return null
                    try {
                        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                            ?: return null
                        cv.clear()
                        cv.put(MediaStore.Downloads.IS_PENDING, 0)
                        context.contentResolver.update(uri, cv, null, null)
                    } catch (e: Exception) {
                        context.contentResolver.delete(uri, null, null)
                        throw e
                    }
                    uri
                }
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "HoursTracker"
                )
                val sub = if (subdir.isBlank()) dir else File(dir, subdir)
                sub.mkdirs()
                val f = File(sub, filename)
                f.writeBytes(bytes)
                Uri.fromFile(f)
            }
        } catch (e: Exception) {
            android.util.Log.e("ExportFile", "MediaStore write failed for '$filename'", e)
            null
        }
    }

    /**
     * Writes an export under Downloads/HoursTracker/<subdir>/. On failure, falls
     * back to the app-private Export dir so an export is never lost. Returns the
     * file Uri, or null if even that fails.
     */
    fun write(context: Context, prefs: SharedPreferences, subdir: String, filename: String, bytes: ByteArray): Uri? {
        writeMedia(context, subdir, filename, bytes)?.let { return it }
        val dir = File(fallbackRootFile(context), subdir.ifBlank { "Export" }).apply { mkdirs() }
        val f = File(dir, filename)
        f.writeBytes(bytes)
        return Uri.fromFile(f)
    }

    /**
     * Writes a backup JSON under Downloads/HoursTracker/Backup/. Falls back to
     * the app-private Backup dir if MediaStore fails. Throws only if even the
     * fallback cannot write, so the caller can surface a real reason.
     */
    fun writeBackup(context: Context, prefs: SharedPreferences, filename: String, bytes: ByteArray): Uri {
        writeMedia(context, "Backup", filename, bytes)?.let { return it }
        val dir = File(fallbackRootFile(context), "Backup").apply { mkdirs() }
        val f = File(dir, filename)
        f.writeBytes(bytes)
        return Uri.fromFile(f)
    }

    /** Base directory of the app-private fallback (no permission needed). */
    fun fallbackRootFile(context: Context): File =
        context.getExternalFilesDir(null) ?: context.filesDir

    /** Physical path of the app-private Export dir (fallback when MediaStore fails). */
    fun appExportDir(context: Context): File {
        val d = File(fallbackRootFile(context), "Export")
        if (!d.exists()) d.mkdirs()
        return d
    }

    /** Physical path of the app-private Backup dir (fallback when MediaStore fails). */
    fun appBackupDir(context: Context): File {
        val d = File(fallbackRootFile(context), "Backup")
        if (!d.exists()) d.mkdirs()
        return d
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
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "HoursTracker"
                )
                val sub = if (subdir.isBlank()) dir else File(dir, subdir)
                File(sub, filename).delete()
            }
        } catch (e: Exception) {
            // The file may not exist or the OS may block deletion.
        }
    }
}