package com.example.hourstracker.model

data class WorkSession(
    val id: Int = 0,
    val jobSiteId: Int,
    val date: String,
    val startTime: String,
    val endTime: String,
    val breakMinutes: Int,
    val notes: String? = null
)