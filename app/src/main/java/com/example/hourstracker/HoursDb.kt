package com.example.hourstracker

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Native SQLite store for HoursTracker. Mirrors the Room schema exactly so
 * exported data is identical to the Compose version.
 */
class HoursDb(context: Context) : SQLiteOpenHelper(context, "hours_tracker.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE job_sites (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "name TEXT NOT NULL, " +
                "location TEXT, " +
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

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
}