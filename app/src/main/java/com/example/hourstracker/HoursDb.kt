package com.example.hourstracker

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Native SQLite store for HoursTracker. Mirrors the Room schema exactly so
 * exported data is identical to the Compose version.
 */
class HoursDb(context: Context) : SQLiteOpenHelper(context, "hours_tracker.db", null, 6) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE job_sites (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "name TEXT NOT NULL, " +
                "location TEXT, " +
                "employer TEXT, " +
                "hourly_wage REAL, " +
                "overtime_start REAL, " +
                "overtime_rate REAL, " +
                "color TEXT NOT NULL DEFAULT '#6750A4', " +
                "drive_minutes INTEGER NOT NULL DEFAULT 0, " +
                "server_id TEXT)"
        )
        db.execSQL(
            "CREATE TABLE work_sessions (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "job_site_id INTEGER NOT NULL, " +
                "date TEXT NOT NULL, " +
                "start_time TEXT NOT NULL, " +
                "end_time TEXT NOT NULL, " +
                "break_minutes INTEGER NOT NULL DEFAULT 0, " +
                "notes TEXT, " +
                "server_id TEXT)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            try { db.execSQL("ALTER TABLE job_sites ADD COLUMN hourly_wage REAL") } catch (e: Exception) {}
        }
        if (oldVersion < 3) {
            try { db.execSQL("ALTER TABLE job_sites ADD COLUMN overtime_start REAL") } catch (e: Exception) {}
            try { db.execSQL("ALTER TABLE job_sites ADD COLUMN overtime_rate REAL") } catch (e: Exception) {}
        }
        if (oldVersion < 4) {
            try { db.execSQL("ALTER TABLE job_sites ADD COLUMN employer TEXT") } catch (e: Exception) {}
        }
        if (oldVersion < 5) {
            try { db.execSQL("ALTER TABLE job_sites ADD COLUMN drive_minutes INTEGER NOT NULL DEFAULT 0") } catch (e: Exception) {}
        }
        if (oldVersion < 6) {
            // Sync: server record id for each local row once it's pushed. NULL
            // (= not yet synced) is the retry-queue marker for work_sessions.
            try { db.execSQL("ALTER TABLE job_sites ADD COLUMN server_id TEXT") } catch (e: Exception) {}
            try { db.execSQL("ALTER TABLE work_sessions ADD COLUMN server_id TEXT") } catch (e: Exception) {}
        }
    }
}