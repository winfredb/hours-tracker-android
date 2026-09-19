package com.example.hourstracker

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Native SQLite store for HoursTracker. Mirrors the Room schema exactly so
 * exported data is identical to the Compose version.
 */
class HoursDb(context: Context) : SQLiteOpenHelper(context, "hours_tracker.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE job_sites (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "name TEXT NOT NULL, " +
                "location TEXT, " +
                "hourly_wage REAL, " +
                "color TEXT NOT NULL DEFAULT '#6750A4')"
        )
        db.execSQL(
            "CREATE TABLE work_sessions (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "job_site_id INTEGER NOT NULL, " +
                "date TEXT NOT NULL, " +
                "start_time TEXT NOT NULL, " +
                "end_time TEXT NOT NULL, " +
                "break_minutes INTEGER NOT NULL DEFAULT 0, " +
                "notes TEXT)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            try {
                db.execSQL("ALTER TABLE job_sites ADD COLUMN hourly_wage REAL")
            } catch (e: Exception) {
                // column may already exist on a partially-migrated install
            }
        }
    }
}