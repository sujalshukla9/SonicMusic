package com.sonicmusic.app.data.local.database

import android.content.Context
import android.util.Log
import androidx.sqlite.db.SimpleSQLiteQuery
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DatabaseBackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: SonicMusicDatabase
) {
    companion object {
        private const val TAG = "DatabaseBackupManager"
    }

    /**
     * Triggers a FULL WAL checkpoint to sync all pending database transactions 
     * to the main SQLite database file, then copies it to the outputStream.
     */
    suspend fun backupDatabase(outputStream: OutputStream): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            Log.d(TAG, "📦 Starting database backup...")
            // 1. Force a full checkpoint to commit all WAL contents to the main DB file
            database.query(SimpleSQLiteQuery("PRAGMA wal_checkpoint(FULL)")).use { cursor ->
                if (cursor.moveToFirst()) {
                    val status = cursor.getInt(0)
                    Log.d(TAG, "WAL checkpoint status: $status")
                }
            }

            // 2. Locate the database file and copy it
            val dbFile = context.getDatabasePath(SonicMusicDatabase.DATABASE_NAME)
            if (!dbFile.exists()) {
                throw IllegalStateException("Database file does not exist at ${dbFile.absolutePath}")
            }

            dbFile.inputStream().use { input ->
                input.copyTo(outputStream)
            }
            outputStream.flush()
            Log.d(TAG, "✅ Database backup successfully written to stream.")
            Unit
        }
    }

    /**
     * Safely closes the Room database connection, deletes existing database and temporary WAL/SHM
     * files, and replaces the database with data from the inputStream.
     */
    suspend fun restoreDatabase(inputStream: InputStream): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            Log.d(TAG, "📦 Starting database restore...")
            // 1. Close current Room connection
            database.close()

            // 2. Identify database file paths
            val dbFile = context.getDatabasePath(SonicMusicDatabase.DATABASE_NAME)
            val walFile = File(dbFile.path + "-wal")
            val shmFile = File(dbFile.path + "-shm")

            // 3. Clear existing database files
            if (dbFile.exists() && !dbFile.delete()) {
                Log.w(TAG, "Failed to delete existing database file: ${dbFile.absolutePath}")
            }
            if (walFile.exists() && !walFile.delete()) {
                Log.w(TAG, "Failed to delete active WAL file: ${walFile.absolutePath}")
            }
            if (shmFile.exists() && !shmFile.delete()) {
                Log.w(TAG, "Failed to delete active SHM file: ${shmFile.absolutePath}")
            }

            // 4. Overwrite main database file with backup stream
            dbFile.parentFile?.mkdirs()
            dbFile.outputStream().use { output ->
                inputStream.copyTo(output)
            }
            Log.d(TAG, "✅ Database backup file successfully restored to ${dbFile.absolutePath}.")
            Unit
        }
    }
}
