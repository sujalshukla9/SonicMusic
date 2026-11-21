package com.sonicmusic.app.core.updater

data class UpdatePackage(
    val downloadUrl: String,
    val versionCode: Int,
    val sha256: String,
    val certificateSha256: String
) {
    init {
        require(versionCode > 0 && SHA256.matches(sha256) && SHA256.matches(certificateSha256)) {
            "The release has invalid APK verification metadata."
        }
    }

    private companion object {
        val SHA256 = Regex("[0-9a-f]{64}")
    }
}
