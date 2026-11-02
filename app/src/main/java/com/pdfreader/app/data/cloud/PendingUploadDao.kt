package com.pdfreader.app.data.cloud

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingUploadDao {

    @Upsert
    suspend fun upsert(entity: PendingUploadEntity)

    @Query("SELECT * FROM pending_uploads ORDER BY queuedAtEpochMillis ASC")
    fun observeAll(): Flow<List<PendingUploadEntity>>

    @Query("SELECT * FROM pending_uploads WHERE localUri = :localUri")
    suspend fun get(localUri: String): PendingUploadEntity?

    @Query("DELETE FROM pending_uploads WHERE localUri = :localUri")
    suspend fun delete(localUri: String)

    @Query("DELETE FROM pending_uploads")
    suspend fun clearAll()
}
