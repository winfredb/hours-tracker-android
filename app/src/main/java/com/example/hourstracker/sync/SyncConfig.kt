package com.example.hourstracker.sync

/**
 * Backend configuration for the Hours Tracker PocketBase server.
 *
 * The public URL is the Tailscale Funnel HTTPS endpoint — this is what the app
 * uses for real remote sync (no client install, HTTPS). The LAN URL is useful
 * for local testing on the same Wi-Fi; switch BASE_URL to it only for dev.
 *
 * Both point at the same PocketBase instance; the public one fronts our
 * office/worker REST API. Workers authenticate as pocketbase `users` and are
 * data-isolated server-side by the `time_entries.user` relation rules.
 */
object SyncConfig {

    /** Public HTTPS funnel URL — use this for production/remote sync. */
    const val BASE_URL = "https://zimaos.taild49c7f.ts.net"


    /** PocketBase REST paths (no leading/trailing slashes). */
    const val PATH_USER_AUTH = "api/collections/users/auth-with-password"
    const val PATH_USERS_RECORDS = "api/collections/users/records"
    const val PATH_HEALTH = "api/health"
    const val PATH_TIME_ENTRIES = "api/collections/time_entries/records"
    const val PATH_PROJECTS = "api/collections/projects/records"
}