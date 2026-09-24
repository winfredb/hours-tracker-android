package com.example.hourstracker

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

/**
 * Minimal native stand-in for androidx FileProvider.
 *
 * The summary PDF lives in the app's own external directory (Export/). Sharing a
 * `file://` Uri to an ACTION_SEND intent throws FileUriExposedException on every
 * receiving app, so this provider maps a small `content://` Uri back to that file,
 * making sharing work on all API levels (minSdk is 26) without the AndroidX
 * dependency.
 *
 * The provider path encodes the absolute file path and only serves files that
 * resolve inside the app's own directories, so a coincidentally-crafted Uri
 * cannot read arbitrary files.
 */
class ProjectFileProvider : ContentProvider() {

    companion object {
        /** Authority must match the manifest <provider> android:authorities. */
        const val AUTHORITY = "com.example.hourstracker2.fileprovider"

        /**
         * Builds the content Uri used to share [fileUri]. If it is already a
         * content Uri (MediaStore path) it is returned unchanged.
         */
        fun shareUri(context: Context, fileUri: Uri?): Uri? {
            if (fileUri == null || fileUri.scheme != "file") return fileUri
            val path = fileUri.path ?: return fileUri
            val allowed = allowedRoots(context)
            val canonical = File(path).canonicalPath
            if (allowed.none { canonical.startsWith(it) }) return fileUri
            return Uri.parse("content://$AUTHORITY/share").buildUpon()
                .appendQueryParameter("path", canonical).build()
        }

        private fun allowedRoots(context: Context): List<String> {
            val roots = mutableListOf<String>()
            context.getExternalFilesDir(null)?.let { roots.add(it.canonicalPath) }
            context.filesDir.canonicalPath.let { roots.add(it) }
            return roots
        }
    }

    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val requested = uri.getQueryParameter("path")
            ?: throw FileNotFoundException("Missing file path")
        val f = File(requested)
        // Double-check the request points inside an allowed root before serving.
        val allowed = allowedRoots(context!!)
        val canonical = try { f.canonicalPath } catch (e: Exception) { throw FileNotFoundException("Bad path") }
        if (allowed.none { canonical.startsWith(it) }) throw FileNotFoundException("Path not allowed")
        if (!f.exists() || !f.canRead()) throw FileNotFoundException("File not readable")
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = "application/pdf"

    override fun query(uri: Uri, projection: Array<String>?, selection: String?,
                       selectionArgs: Array<String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?,
                       selectionArgs: Array<String>?): Int = 0
}