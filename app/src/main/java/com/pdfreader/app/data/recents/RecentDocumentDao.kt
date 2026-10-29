package com.pdfreader.app.data.recents

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface RecentDocumentDao {

    @Upsert
    suspend fun upsert(entity: RecentDocumentEntity)

    @Query("SELECT * FROM recent_documents ORDER BY lastOpenedAtEpochMillis DESC")
    fun observeAll(): Flow<List<RecentDocumentEntity>>

    @Query("DELETE FROM recent_documents")
    suspend fun clearAll()

    @Query("DELETE FROM recent_documents WHERE uri = :uri")
    suspend fun delete(uri: String)
}
