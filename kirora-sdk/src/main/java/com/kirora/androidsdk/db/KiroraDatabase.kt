package com.kirora.androidsdk.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import java.io.File

/**
 * One database per workspace and API deployment ([com.kirora.androidsdk.utils.KiroraNamespace]),
 * so two clients never exchange events.
 */
@Database(
    entities = [EventEntity::class, AppOpenEntity::class],
    version = 5,
    exportSchema = true
)
internal abstract class KiroraDatabase : RoomDatabase() {
    abstract fun eventDao(): EventDao
    abstract fun appOpenDao(): AppOpenDao

    companion object {
        private val instances = HashMap<String, KiroraDatabase>()

        fun getInstance(context: Context, namespace: String): KiroraDatabase = synchronized(instances) {
            instances.getOrPut(namespace) {
                val app = context.applicationContext
                deleteLegacyDatabase(app)
                Room.databaseBuilder(
                    app,
                    KiroraDatabase::class.java,
                    // Kept out of auto-backup so a restore never replays another device's events.
                    File(app.noBackupFilesDir, "kirora_$namespace.db").absolutePath
                ).fallbackToDestructiveMigration(true).build()
            }
        }

        /** Before 0.1.0 the queue was one file for every workspace, with no owner it could trust. */
        private fun deleteLegacyDatabase(context: Context) {
            try {
                val legacy = File(context.noBackupFilesDir, "kirora_events.db")
                if (legacy.exists()) context.deleteDatabase(legacy.absolutePath)
            } catch (_: Throwable) {
            }
        }

        internal fun closeAllForTesting() = synchronized(instances) {
            instances.values.forEach { it.close() }
            instances.clear()
        }
    }
}
