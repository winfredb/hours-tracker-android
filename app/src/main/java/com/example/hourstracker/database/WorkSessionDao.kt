package com.example.hourstracker.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Delete
import com.example.hourstracker.model.JobSite
import com.example.hourstracker.model.WorkSession
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkSessionDao {
    @Query("SELECT * FROM work_sessions ORDER BY date DESC")
    fun getAllSessions(): Flow<List<WorkSession>>

    @Query("SELECT * FROM work_sessions WHERE job_site_id = :jobSiteId ORDER BY date, start_time")
    fun getSessionsForJobSite(jobSiteId: Int): Flow<List<WorkSession>>

    @Query("SELECT * FROM work_sessions WHERE date BETWEEN :start AND :end ORDER BY date")
    fun getSessionsInPeriod(start: String, end: String): Flow<List<WorkSession>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: WorkSession)

    @Update
    suspend fun updateSession(session: WorkSession)

    @Delete
    suspend fun deleteSession(session: WorkSession)

    @Query("SELECT * FROM job_sites")
    fun getAllJobSites(): Flow<List<JobSite>>
}