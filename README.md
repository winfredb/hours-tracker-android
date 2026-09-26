# Hours Tracker

A pure-native Android app for tracking **work hours per job / project site** — clock in/out with breaks, per-site drive time and pay rate, weekly & pay-period reporting, per-project spreadsheet export, a homescreen widget, and optional cloud sync to a self-hosted backend for office view.

Built with **only `android.*` / `java.*`** — no Jetpack Compose, Room, or Hilt. UI is `android.widget`, storage is `SQLiteOpenHelper`.

---

## Features

- **Timer-based time tracking** — Start / Pause / Resume / Stop per job site; a foreground `TimerService` keeps the clock alive while the app runs.
- **Breaks** — enter break duration on resume (including widget-initiated pauses); breaks are subtracted from paid hours.
- **Job sites & projects** — each site has a name, site label, per-site **employer**, **hourly wage**, and **drive minutes**.
- **Drive time** — per-project drive minutes auto-credited once per day (commute) and included in overtime; a catch-all *Driving* site avoids double-counting manual commute clock-ins.
- **Home reports** — weekly bar chart and **pay-period** summary (hours by project, hours per week, OT over 40h, drive time), ticking live with the timer.
- **Per-project export** — each project exports to its own `.xlsx` spreadsheet.
- **Pay-period PDF** — Office-style pay-period report card rendered to PDF.
- **Home screen widget (3×1)** — shows hours today as H:MM, the active job, jobs worked, and estimated pay, live-updated by the `TimerService` while running.
- **Backup & restore** — manual JSON backup/restore plus a **daily automatic backup**; exports and backups go to `Downloads/HoursTracker/` via `MediaStore`.
- **Optional cloud sync** — sign in to a self-hosted [PocketBase](https://pocketbase.io/) backend: two-way push/pull, project-detail sync (employer/wage/drive), change-password self-service. Users not signed in simply use the app locally.

---

## Screenshots

*(Add screen captures of the Home screen, Settings, widget, exported sheet here.)*

---

## Tech stack

| Layer | Choice |
|-------|--------|
| Language | Kotlin 1.9.24 |
| UI | `android.widget` (programmatic, no XML layouts) |
| Storage | `SQLiteOpenHelper` (local), JSON backup/restore |
| Networking | `java.net.HttpURLConnection` + `org.json` (blocking, off-main) |
| Auth tokens | AES/GCM in `SharedPreferences`, key in Android Keystore |
| Build | Gradle 8.4, AGP 8.2.0, JDK 17 |
| Min / target / compile SDK | 26 / 36 / 36 |

The app never requires sign-in — the backend is optional and used only when a worker logs into Settings → Account.

---

## Project layout

```
app/src/main/java/com/example/hourstracker/
├── MainActivity.kt          # Single Activity; all screens + dialogs rendered in code
├── HoursDb.kt               # SQLiteOpenHelper (schema, v6)
├── model/
│   ├── JobSite.kt           # Local site model (id, name, site, employer, wage, drive, server_id)
│   └── WorkSession.kt       # Local session/entry model
├── Clock.kt                 # Wall-clock + elapsed-time helpers
├── TimerService.kt          # Foreground service driving the running timer
├── Widget3x1Provider.kt     # 3×1 homescreen widget + instant app widget updates
├── ExportFile.kt            # MediaStore.Downloads writer (Export/, Backup/)
├── Backup.kt / AutoBackup.kt# Manual & daily automatic JSON backup/restore
├── ProjectFileProvider.kt   # Native FileProvider stand-in for ACTION_SEND sharing
└── sync/
    ├── SyncConfig.kt        # BASE_URL (public funnel / LAN dev), constants
    ├── ApiClient.kt         # REST client (HttpURLConnection), PATCH support
    ├── AuthStore.kt         # Keystore-wrapped token + identity persistence
    ├── SyncAuth.kt          # users/auth-with-password login, logout, token
    └── SyncEngine.kt        # two-way push/pull, dedup by server_id
```

---

## Building

Requirements: **JDK 17**, Android SDK with platform 36 + build-tools 36.0.0.

```bash
# Debug APK → app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleDebug --no-daemon
```

CI (`.github/workflows/android-build.yml`) builds the debug APK on every push/PR to `main`, uploads it as an artifact (90 days), and creates a GitHub Release when you tag `v*`.

> Note: the app's application ID is **`com.example.hourstracker2`** — a renamed install requires a full uninstall/reinstall of the old package.

---

## Backend & Office dashboard

The optional backend is a **self-hosted PocketBase** instance storing all users' time data for office read-side. See the companion repository:

- **Backend + office dashboard:** [`winfredb/hours-tracker-dashboard`](https://github.com/winfredb/hours-tracker-dashboard)

Sync flow: the app pushes local `job_sites → projects` and `work_sessions → time_entries` (deduped by a local `server_id`), pulls the worker's own entries back for restore, and keeps the source-of-truth on the server so a lost phone only risks *unsynced* entries (auto-sync on stop/finish plus a retry queue).

---

## Scripts & tooling

- `scripts/ci_poll.sh` — watch a GitHub Actions build for the repo.
- `tools/` — widget layout render/check helpers used during development.

---

## License

Proprietary / internal use. See the repository owner for distribution terms.
