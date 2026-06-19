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
            // 1. Force a full checkpoint to commit as much as possible
            runCatching {
                val db = database.openHelper.writableDatabase
                db.query(SimpleSQLiteQuery("PRAGMA wal_checkpoint(FULL)")).use { cursor ->
                    if (cursor.moveToFirst()) {
                        val status = cursor.getInt(0)
                        Log.d(TAG, "WAL checkpoint status: $status")
                    }
                }
            }

            // 2. Locate the database and datastore preference files
            val dbFile = context.getDatabasePath(SonicMusicDatabase.DATABASE_NAME)
            val walFile = File(dbFile.path + "-wal")
            val datastoreDir = File(context.filesDir, "datastore")
            val settingsPrefFile = File(datastoreDir, "sonic_music_settings.preferences_pb")
            val audioPrefFile = File(datastoreDir, "audio_engine_prefs.preferences_pb")

            if (!dbFile.exists()) {
                throw IllegalStateException("Database file does not exist at ${dbFile.absolutePath}")
            }

            // 3. Package database files (excluding -shm) and preferences into a single ZIP archive
            java.util.zip.ZipOutputStream(outputStream).use { zipOut ->
                // Main database file
                zipOut.putNextEntry(java.util.zip.ZipEntry(dbFile.name))
                dbFile.inputStream().use { input -> input.copyTo(zipOut) }
                zipOut.closeEntry()

                // WAL file
                if (walFile.exists() && walFile.length() > 0) {
                    zipOut.putNextEntry(java.util.zip.ZipEntry(walFile.name))
                    walFile.inputStream().use { input -> input.copyTo(zipOut) }
                    zipOut.closeEntry()
                }

                // Settings DataStore
                if (settingsPrefFile.exists() && settingsPrefFile.length() > 0) {
                    zipOut.putNextEntry(java.util.zip.ZipEntry(settingsPrefFile.name))
                    settingsPrefFile.inputStream().use { input -> input.copyTo(zipOut) }
                    zipOut.closeEntry()
                }

                // Audio Engine DataStore
                if (audioPrefFile.exists() && audioPrefFile.length() > 0) {
                    zipOut.putNextEntry(java.util.zip.ZipEntry(audioPrefFile.name))
                    audioPrefFile.inputStream().use { input -> input.copyTo(zipOut) }
                    zipOut.closeEntry()
                }
            }
            outputStream.flush()
            Log.d(TAG, "✅ Database and settings backup successfully written to ZIP stream.")
            Unit
        }
    }

    /**
     * Safely closes the Room database connection, deletes existing database and temporary WAL/SHM
     * files, deletes current DataStore preferences, and restores files from the inputStream.
     */
    suspend fun restoreDatabase(inputStream: InputStream): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            Log.d(TAG, "📦 Starting database restore...")
            // 1. Close current Room connection
            database.close()

            // 2. Identify file paths
            val dbFile = context.getDatabasePath(SonicMusicDatabase.DATABASE_NAME)
            val parentDir = dbFile.parentFile
            parentDir?.mkdirs()
            val datastoreDir = File(context.filesDir, "datastore")
            val settingsPrefFile = File(datastoreDir, "sonic_music_settings.preferences_pb")
            val audioPrefFile = File(datastoreDir, "audio_engine_prefs.preferences_pb")

            // 3. Wrap stream to detect if it is a ZIP archive
            val bufferedInput = java.io.BufferedInputStream(inputStream)
            bufferedInput.mark(1024)

            val isZip = runCatching {
                val header = ByteArray(4)
                val read = bufferedInput.read(header, 0, 4)
                bufferedInput.reset()
                read == 4 && header[0] == 0x50.toByte() && header[1] == 0x4B.toByte() &&
                        header[2] == 0x03.toByte() && header[3] == 0x04.toByte()
            }.getOrDefault(false)

            // 4. Clear existing database files cleanly using Context API
            context.deleteDatabase(SonicMusicDatabase.DATABASE_NAME)

            // Clear current preferences files
            if (settingsPrefFile.exists()) settingsPrefFile.delete()
            if (audioPrefFile.exists()) audioPrefFile.delete()

            // 5. Restore files
            if (isZip) {
                Log.d(TAG, "Restoring from ZIP backup format...")
                java.util.zip.ZipInputStream(bufferedInput).use { zipIn ->
                    var entry = zipIn.nextEntry
                    while (entry != null) {
                        val entryName = File(entry.name).name
                        val targetFile = when {
                            entryName == dbFile.name -> dbFile
                            entryName == dbFile.name + "-wal" -> File(dbFile.path + "-wal")
                            entryName == settingsPrefFile.name -> {
                                settingsPrefFile.parentFile?.mkdirs()
                                settingsPrefFile
                            }
                            entryName == audioPrefFile.name -> {
                                audioPrefFile.parentFile?.mkdirs()
                                audioPrefFile
                            }
                            else -> null
                        }

                        if (targetFile != null) {
                            targetFile.outputStream().use { output ->
                                zipIn.copyTo(output)
                                output.flush()
                            }
                            Log.d(TAG, "Restored file: ${targetFile.name}")
                        } else {
                            Log.w(TAG, "Unknown entry in backup zip: ${entry.name}, skipping.")
                        }

                        zipIn.closeEntry()
                        entry = zipIn.nextEntry
                    }
                }
            } else {
                Log.d(TAG, "Restoring from legacy raw DB backup format...")
                dbFile.outputStream().use { output ->
                    bufferedInput.copyTo(output)
                    output.flush()
                }
            }

            Log.d(TAG, "✅ Database and settings successfully restored.")
            Unit
        }
    }
}
