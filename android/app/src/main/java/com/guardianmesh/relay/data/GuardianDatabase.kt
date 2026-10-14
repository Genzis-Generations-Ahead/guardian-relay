package com.guardianmesh.relay.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [TrustedContact::class, AppSettings::class],
    version = 1,
    exportSchema = false
)
abstract class GuardianDatabase : RoomDatabase() {

    abstract fun contacts(): TrustedContactDao
    abstract fun settings(): AppSettingsDao

    companion object {
        @Volatile private var instance: GuardianDatabase? = null

        fun get(context: Context): GuardianDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    GuardianDatabase::class.java,
                    "guardian.db"
                )
                    // v0.3 is the first schema. Destructive migration keeps a dev build from
                    // crashing on a stale install; replace with real migrations before release.
                    .fallbackToDestructiveMigration()
                    .build().also { instance = it }
            }
    }
}
