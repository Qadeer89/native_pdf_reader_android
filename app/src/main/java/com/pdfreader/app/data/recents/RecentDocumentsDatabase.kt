package com.pdfreader.app.data.recents

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [RecentDocumentEntity::class], version = 1, exportSchema = false)
abstract class RecentDocumentsDatabase : RoomDatabase() {
    abstract fun recentDocumentDao(): RecentDocumentDao

    companion object {
        @Volatile
        private var instance: RecentDocumentsDatabase? = null

        fun getInstance(context: Context): RecentDocumentsDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    RecentDocumentsDatabase::class.java,
                    "recent-documents.db",
                ).build().also { instance = it }
            }
    }
}
