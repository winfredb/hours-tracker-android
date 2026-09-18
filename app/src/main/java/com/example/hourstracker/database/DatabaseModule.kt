package com.example.hourstracker.database

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class DatabaseModule {

    companion object {
        @Provides
        fun provideHoursTrackerDatabase(
            @ApplicationContext appContext: Context
        ): HoursTrackerDatabase {
            return Room.databaseBuilder(
                appContext,
                HoursTrackerDatabase::class.java,
                "hours_tracker.db"
            ).build()
        }
    }
}