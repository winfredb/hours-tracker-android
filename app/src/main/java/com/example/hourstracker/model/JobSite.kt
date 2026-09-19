package com.example.hourstracker.model

data class JobSite(
    val id: Int = 0,
    val name: String,
    val location: String? = null,
    val color: String = "#6750A4"
)