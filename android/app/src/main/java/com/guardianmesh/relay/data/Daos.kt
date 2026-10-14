package com.guardianmesh.relay.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TrustedContactDao {

    @Query("SELECT * FROM trusted_contacts ORDER BY priority ASC, name ASC")
    fun observeAll(): Flow<List<TrustedContact>>

    @Query("SELECT * FROM trusted_contacts ORDER BY priority ASC, name ASC")
    suspend fun getAll(): List<TrustedContact>

    @Query("SELECT * FROM trusted_contacts WHERE notifyBySms = 1 ORDER BY priority ASC, name ASC")
    suspend fun getNotifiable(): List<TrustedContact>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(contact: TrustedContact): Long

    @Update
    suspend fun update(contact: TrustedContact)

    @Delete
    suspend fun delete(contact: TrustedContact)

    @Query("SELECT COUNT(*) FROM trusted_contacts WHERE notifyBySms = 1")
    suspend fun countNotifiable(): Int
}

@Dao
interface AppSettingsDao {

    // AppSettings.ROW_ID is 1; inlined because @Query takes a plain constant string.
    @Query("SELECT * FROM app_settings WHERE id = 1")
    fun observe(): Flow<AppSettings?>

    // AppSettings.ROW_ID is 1; inlined because @Query takes a plain constant string.
    @Query("SELECT * FROM app_settings WHERE id = 1")
    suspend fun get(): AppSettings?

    @Upsert
    suspend fun upsert(settings: AppSettings)
}
