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

    /**
     * Change the signed-in user's password.
     *
     * PocketBase (this version) has no `/change-password` route — the way a
     * logged-in worker changes their own password is a PATCH to their own user
     * record carrying the existing password plus the new one. Verified live:
     *   PATCH /api/collections/users/records/{id}
     *   { oldPassword, password, passwordConfirm }  (auth: worker's JWT)
     *
     * A correct old password → 200 and the old password stops working; a wrong
     * one → HTTP 400. A STALE/expired token yields HTTP 404 (PocketBase hides
     * the record from an unauthenticated caller), so we re-authenticate with
     * the email + the current password the user just typed to mint a FRESH
     * token first — this both validates the old password and guarantees the
     * PATCH carries a live token. Nothing is persisted client-side (the user's
     * stored token is untouched; the server stores only the hash).
     */
    fun changePassword(oldPassword: String, newPassword: String): Boolean {
        val email = store.email ?: throw SyncException("Not signed in")
        val uid = store.userId ?: throw SyncException("Not signed in")

        // Fresh token from the entered password validates it and avoids the
        // server's 404-on-stale-token behaviour.
        val authResp = try {
            api.post(SyncConfig.PATH_USER_AUTH, JSONObject()
                .put("identity", email)
                .put("password", oldPassword))
        } catch (e: SyncException) {
            // 400 = wrong current password (most common); anything else is a
            // reachability issue. Translate to a human message.
            if (e.code == 400) throw SyncException("Current password is incorrect")
            else throw e
        }
        val token = authResp.optString("token").takeIf { it.isNotEmpty() }
            ?: throw SyncException("Current password is incorrect")

        val body = JSONObject()
            .put("oldPassword", oldPassword)
            .put("password", newPassword)
            .put("passwordConfirm", newPassword)
        api.patch("${SyncConfig.PATH_USERS_RECORDS}/$uid", body, token)
        return true
    }

    /** Forget the stored token + identity. */
    fun logout() = store.clear()
}