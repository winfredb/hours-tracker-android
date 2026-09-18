package com.example.hourstracker.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "work_sessions")
data class WorkSession(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    @ColumnInfo(name = "job_site_id") val jobSiteId: Int,
    @ColumnInfo(name = "date") val date: String,
    @ColumnInfo(name = "start_time") val startTime: String,
    @ColumnInfo(name = "end_time") val endTime: String,
    @ColumnInfo(name = "break_minutes") val breakMinutes: Int,
    @ColumnInfo(name = "notes") val notes: String? = null
)