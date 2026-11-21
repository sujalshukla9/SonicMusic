package com.sonicmusic.app.core.updater

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

internal object UpdateApkVerifier {
    @Suppress("DEPRECATION")
    fun verify(context: Context, apk: File, expected: UpdatePackage) {
        val packageManager = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val installed = packageManager.getPackageInfo(context.packageName, flags)
        val candidate = packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
            ?: error("The update file is not a readable Android APK.")
        require(candidate.packageName == context.packageName) { "The APK is for a different application." }
        val installedCode = PackageInfoCompat.getLongVersionCode(installed)
        val candidateCode = PackageInfoCompat.getLongVersionCode(candidate)
        require(candidateCode == expected.versionCode.toLong() && candidateCode > installedCode) {
            "The APK version does not match the release or is already installed."
        }

        val approved = setOf(expected.certificateSha256)
        require(certificateHashes(installed) == approved && certificateHashes(candidate) == approved) {
            "The update signing certificate does not match this installation."
        }

        val appInfo = candidate.applicationInfo ?: error("The update is missing Android manifest information.")
        require(appInfo.minSdkVersion <= Build.VERSION.SDK_INT) { "This update requires a newer Android version." }
        require(candidate.splitNames.isNullOrEmpty()) { "This update requires a standalone APK." }
        ZipFile(apk).use { zip ->
            val abis = zip.entries().asSequence().mapNotNull { entry ->
                val parts = entry.name.split('/')
                parts.getOrNull(1)?.takeIf { parts.size == 3 && parts[0] == "lib" && entry.name.endsWith(".so") }
            }.toSet()
            require(abis.isEmpty() || Build.SUPPORTED_ABIS.any(abis::contains)) {
                "This update does not support your phone’s CPU architecture."
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun certificateHashes(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return signatures.orEmpty().mapTo(mutableSetOf()) { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).hex()
        }
    }
}

internal fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
