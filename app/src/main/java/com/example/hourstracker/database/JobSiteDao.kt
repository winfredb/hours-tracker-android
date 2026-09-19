package com.example.hourstracker.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.hourstracker.model.JobSite
import kotlinx.coroutines.flow.Flow

@Dao
interface JobSiteDao {
    @Query("SELECT * FROM job_sites ORDER BY name")
    fun getAllJobSites(): Flow<List<JobSite>>

    /** Inserts a new project; Room auto-generates the id (id=0 is treated as NULL, not a real value). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertJobSite(jobSite: JobSite): Long

    @Query("SELECT * FROM job_sites WHERE id = :id")
    suspend fun getJobSite(id: Int): JobSite?

    @Query("DELETE FROM job_sites WHERE id = :id")
    suspend fun deleteJobSite(id: Int)

    @Query("UPDATE job_sites SET name = :name, location = :location, color = :color WHERE id = :id")
    suspend fun updateJobSite(id: Int, name: String, location: String?, color: String)
}