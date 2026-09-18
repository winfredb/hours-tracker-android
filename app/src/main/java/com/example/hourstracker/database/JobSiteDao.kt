package com.example.hourstracker.database

import androidx.room.Dao
import androidx.room.Query
import com.example.hourstracker.model.JobSite
import kotlinx.coroutines.flow.Flow

@Dao
interface JobSiteDao {
    @Query("SELECT * FROM job_sites ORDER BY name")
    fun getAllJobSites(): Flow<List<JobSite>>

    @Query("INSERT OR REPLACE INTO job_sites (id, name, location, color) VALUES (:id, :name, :location, :color)")
    suspend fun insertOrReplaceJobSite(id: Int, name: String, location: String?, color: String)

    @Query("DELETE FROM job_sites WHERE id = :id")
    suspend fun deleteJobSite(id: Int)

    @Query("UPDATE job_sites SET name = :name, location = :location, color = :color WHERE id = :id")
    suspend fun updateJobSite(id: Int, name: String, location: String?, color: String)
}