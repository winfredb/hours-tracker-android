package com.example.hourstracker.sync

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Minimal synchronous REST client for PocketBase built purely on
 * java.net.HttpURLConnection + org.json — no external HTTP library, matching
 * the app's "android.* + java.* only" constraint.
 *
 * All methods are BLOCKING. Callers must run them off the main thread
 * (a background thread / coroutine / WorkManager worker). Throws
 * [SyncException] on transport or HTTP errors, so callers can catch it and
 * route to the retry queue.
 */
class ApiClient(
    private val baseUrl: String = SyncConfig.BASE_URL
) {

    /** HTTP status groups we accept. */
    private val OK_2XX = 200..299

    /**
     * GET a JSON object from [path], authenticated with [token] if provided.
     */
    fun get(path: String, token: String? = null): JSONObject {
        val conn = open("GET", path, token)
        return read(conn, emptyBody = false)
    }

    /**
     * POST [body] to [path]. Authenticated with [token] if provided.
     * Some PocketBase endpoints (auth) return 200 with a body; create
     * endpoints return 201. We accept any 2xx.
     */
    fun post(path: String, body: JSONObject, token: String? = null): JSONObject {
        val conn = open("POST", path, token)
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        return writeAndRead(conn, body)
    }

    /**
     * PATCH [body] to [path] (a partial update; PocketBase merges provided keys).
     * Used to push project detail edits back to the server without re-creating
     * the record. Authenticated with [token].
     */
    fun patch(path: String, body: JSONObject, token: String? = null): JSONObject {
        val conn = open("PATCH", path, token)
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        return writeAndRead(conn, body)
    }

    private fun writeAndRead(conn: HttpURLConnection, body: JSONObject): JSONObject {
        val out: OutputStream = conn.outputStream
        out.write(body.toString().toByteArray(StandardCharsets.UTF_8))
        out.flush()
        out.close()
        return read(conn, emptyBody = false)
    }

    private fun open(method: String, path: String, token: String?): HttpURLConnection {
        val url = URL("${trimSlash(baseUrl)}/${trimSlash(path)}")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/json")
            if (!token.isNullOrEmpty()) {
                setRequestProperty("Authorization", "Bearer $token")
            }
        }
        return conn
    }

    private fun read(conn: HttpURLConnection, emptyBody: Boolean): JSONObject {
        val code = try {
            conn.responseCode
        } catch (e: Exception) {
            throw SyncException("Connection failed: ${e.message}", cause = e)
        }

        val bodyStr = if (emptyBody) "" else readBody(conn)

        if (code !in OK_2XX) {
            throw SyncException("HTTP $code from ${conn.url}", code = code, body = bodyStr)
        }
        if (bodyStr.isBlank()) return JSONObject()
        return try {
            JSONObject(bodyStr)
        } catch (e: Exception) {
            throw SyncException("Non-JSON response: ${bodyStr.take(200)}", cause = e)
        }
    }

    /** Read the response body, preferring the error stream when present. */
    private fun readBody(conn: HttpURLConnection): String {
        val stream: InputStream? = try {
            conn.inputStream
        } catch (e: Exception) {
            conn.errorStream
        }
        if (stream == null) return ""
        return try {
            BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { it.readText() }
        } finally {
            stream.close()
        }
    }

    private fun trimSlash(s: String): String = s.trimEnd('/')
}

/** Raised for any transport or non-2xx HTTP response from the backend. */
class SyncException(
    message: String,
    val code: Int = 0,
    val body: String? = null,
    cause: Throwable? = null
) : Exception(message, cause)