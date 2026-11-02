package com.pdfreader.app.data.cloud

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [PendingUploadEntity::class], version = 1, exportSchema = false)
abstract class PendingUploadsDatabase : RoomDatabase() {
    abstract fun pendingUploadDao(): PendingUploadDao

    companion object {
        @Volatile
        private var instance: PendingUploadsDatabase? = null

        fun getInstance(context: Context): PendingUploadsDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    PendingUploadsDatabase::class.java,
                    "pending-uploads.db",
                ).build().also { instance = it }
            }
    }
}
