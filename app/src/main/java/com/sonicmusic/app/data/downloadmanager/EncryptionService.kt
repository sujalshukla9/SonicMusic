package com.sonicmusic.app.data.downloadmanager

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Encryption Service for Downloaded Audio Files
 * 
 * Uses AES-256-GCM encryption with Android KeyStore for secure key storage.
 * 
 * Features:
 * - AES-256-GCM authenticated encryption
 * - Keys stored in Android KeyStore (hardware-backed on supported devices)
 * - Per-file unique IV for security
 * - Streaming encryption for large files
 */
@Singleton
class EncryptionService @Inject constructor() {

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "SonicMusicDownloadKey"
        private const val AES_MODE = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH = 128 // bits
        private const val GCM_IV_LENGTH = 12 // bytes (96 bits, recommended for GCM)
        private const val ENCRYPTED_FILE_EXTENSION = ".enc"
    }

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply {
        load(null)
    }

    /**
     * Get or create the encryption key from Android KeyStore.
     */
    private fun getOrCreateSecretKey(): SecretKey {
        // Check if key already exists
        val existingKey = keyStore.getEntry(KEY_ALIAS, null)
        if (existingKey is KeyStore.SecretKeyEntry) {
            return existingKey.secretKey
        }

        // Generate new key
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )

        val keySpec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true) // Ensures unique IV per encryption
            .build()

        keyGenerator.init(keySpec)
        return keyGenerator.generateKey()
    }

    /**
     * Encrypt a file and save to the encrypted output path.
     * 
     * @param inputFile The plaintext file to encrypt
     * @param outputFile The destination for the encrypted file
     * @return Result indicating success or failure
     */
    fun encryptFile(inputFile: File, outputFile: File): Result<File> {
        return try {
            val secretKey = getOrCreateSecretKey()
            val cipher = Cipher.getInstance(AES_MODE)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            
            // GCM generates a random IV automatically with randomizedEncryptionRequired
            val iv = cipher.iv

            FileInputStream(inputFile).use { fis ->
                FileOutputStream(outputFile).use { fos ->
                    // Write IV at the beginning of the file (12 bytes)
                    fos.write(iv)

                    // Encrypt file contents in chunks
                    val buffer = ByteArray(8192) // 8KB buffer
                    var bytesRead: Int
                    
                    while (fis.read(buffer).also { bytesRead = it } != -1) {
                        val encryptedChunk = cipher.update(buffer, 0, bytesRead)
                        if (encryptedChunk != null) {
                            fos.write(encryptedChunk)
                        }
                    }

                    // Write final block (includes GCM authentication tag)
                    val finalBlock = cipher.doFinal()
                    if (finalBlock != null) {
                        fos.write(finalBlock)
                    }
                }
            }

            Result.success(outputFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Decrypt a file to a temporary playable location.
     * 
     * @param encryptedFile The encrypted file
     * @param outputFile The destination for the decrypted file
     * @return Result indicating success or failure
     */
    fun decryptFile(encryptedFile: File, outputFile: File): Result<File> {
        return try {
            val secretKey = getOrCreateSecretKey()

            FileInputStream(encryptedFile).use { fis ->
                // Read IV from the beginning (12 bytes)
                val iv = ByteArray(GCM_IV_LENGTH)
                val ivBytesRead = fis.read(iv)
                if (ivBytesRead != GCM_IV_LENGTH) {
                    return Result.failure(Exception("Invalid encrypted file: IV not found"))
                }

                val cipher = Cipher.getInstance(AES_MODE)
                val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
                cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)

                FileOutputStream(outputFile).use { fos ->
                    // Decrypt file contents in chunks
                    val buffer = ByteArray(8192 + 16) // 8KB + GCM tag buffer
                    var bytesRead: Int

                    while (fis.read(buffer).also { bytesRead = it } != -1) {
                        val decryptedChunk = cipher.update(buffer, 0, bytesRead)
                        if (decryptedChunk != null) {
                            fos.write(decryptedChunk)
                        }
                    }

                    // Write final block (verifies GCM authentication tag)
                    val finalBlock = cipher.doFinal()
                    if (finalBlock != null) {
                        fos.write(finalBlock)
                    }
                }
            }

            Result.success(outputFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Encrypt a file in-place (replaces original with encrypted version).
     * 
     * @param file The file to encrypt
     * @return Result with the encrypted file
     */
    fun encryptFileInPlace(file: File): Result<File> {
        val tempEncrypted = File(file.parent, "${file.name}.tmp$ENCRYPTED_FILE_EXTENSION")
        
        return try {
            encryptFile(file, tempEncrypted).onSuccess {
                // Delete original and rename encrypted
                file.delete()
                tempEncrypted.renameTo(File(file.parent, file.nameWithoutExtension + ENCRYPTED_FILE_EXTENSION))
            }
            
            val encryptedFile = File(file.parent, file.nameWithoutExtension + ENCRYPTED_FILE_EXTENSION)
            if (encryptedFile.exists()) {
                Result.success(encryptedFile)
            } else {
                Result.failure(Exception("Failed to create encrypted file"))
            }
        } catch (e: Exception) {
            tempEncrypted.delete()
            Result.failure(e)
        }
    }

    /**
     * Get the encrypted file extension suffix.
     */
    fun getEncryptedExtension(): String = ENCRYPTED_FILE_EXTENSION

    /**
     * Check if a file is encrypted (by extension).
     */
    fun isEncryptedFile(file: File): Boolean = file.extension == "enc"
}
