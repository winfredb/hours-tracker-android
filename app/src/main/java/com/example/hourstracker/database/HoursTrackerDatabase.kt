package com.example.hourstracker.database

import com.example.hourstracker.model.JobSite
import com.example.hourstracker.model.WorkSession
import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [WorkSession::class, JobSite::class], version = 1, exportSchemas = false)
abstract class HoursTrackerDatabase : RoomDatabase() {
    abstract fun workSessionDao(): WorkSessionDao
    abstract fun jobSiteDao(): JobSiteDao
}