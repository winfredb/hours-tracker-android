package com.example.hourstracker.sync

import org.json.JSONObject

/**
 * PocketBase user authentication: logs a worker in via
 * `POST /api/collections/users/auth-with-password`, persists the returned token
 * in the Keystore-backed [AuthStore], and restores it on later launches.
 *
 * A "user" here is a PocketBase account created by the office (the seeded
 * workers, e.g. alice.rios@example.com). The server distinguishes the office
 * superuser (sees all) from a worker (data-isolated to their own rows).
 *
 * All methods are BLOCKING — call off the main thread.
 */
class SyncAuth(
    private val api: ApiClient = ApiClient(),
    private val store: AuthStore
) {

    /**
     * Attempt to log in with [email]/[password]. On success the token and
     * identity are persisted and true is returned. Throws [SyncException]
     * (e.g. wrong credentials → HTTP 400) so callers can surface an error.
     */
    fun login(email: String, password: String): Boolean {
        val body = JSONObject()
            .put("identity", email)
            .put("password", password)

        // PocketBase users auth returns 200 (unlike create's 201); ApiClient
        // accepts any 2xx. The response's top-level "token" is the auth JWT.
        val resp = api.post(SyncConfig.PATH_USER_AUTH, body)
        val token = resp.optString("token").takeIf { it.isNotEmpty() }
            ?: throw SyncException("No token in auth response")

        // "record" is the user object. PocketBase may also return "admin".
        val record = resp.optJSONObject("record")
        val name = record?.optString("name")?.takeIf { it.isNotBlank() }
        val emailBack = record?.optString("email") ?: email
        val uid = record?.optString("id")?.takeIf { it.isNotEmpty() }

        store.saveToken(token)
        store.email = emailBack
        store.name = name
        store.userId = uid
        return true
    }

    /** True if a token is currently stored. */
    fun isLoggedIn(): Boolean = store.hasToken

    /** Decrypted current token, or null. */
    fun token(): String? = store.token

    /** Stored email/name (presented when logged in), or null. */
    fun currentEmail(): String? = store.email
    fun currentName(): String? = store.name
    fun currentUserId(): String? = store.userId

    /** Forget the stored token + identity. */
    fun logout() = store.clear()
}