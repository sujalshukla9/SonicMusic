import java.util.Properties
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import org.gradle.api.GradleException

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
}

val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
fun configuredValue(name: String): String? = System.getenv(name) ?: localProperties.getProperty(name)
fun releaseSetting(name: String): String? = configuredValue(name)?.trim()?.takeIf { it.isNotEmpty() }

val appId = "com.sonicmusic.app"
val supportedMinSdk = 26
val androidBuildTools = "34.0.0"
val windows = System.getProperty("os.name").startsWith("Windows")
val python = if (windows) "python" else "python3"
val sdkRoot = releaseSetting("ANDROID_HOME") ?: releaseSetting("ANDROID_SDK_ROOT")
    ?: localProperties.getProperty("sdk.dir")
val buildToolsDir = sdkRoot?.let { File(it, "build-tools/$androidBuildTools") }
val aapt = buildToolsDir?.resolve(if (windows) "aapt.exe" else "aapt")
val apksigner = buildToolsDir?.resolve(if (windows) "apksigner.bat" else "apksigner")
val compatibilityScript = rootProject.file("tools/verify_release_apk.py")
val previousApk = releaseSetting("PREVIOUS_RELEASE_APK")?.let(rootProject::file)
val releaseName = releaseSetting("RELEASE_VERSION_NAME")
val releasePin = releaseSetting("RELEASE_CERT_SHA256")
val releaseKeystore = releaseSetting("KEYSTORE_FILE")?.let(rootProject::file)
val releaseStorePassword = configuredValue("KEYSTORE_PASSWORD")?.takeIf { it.isNotEmpty() }
val releaseKeyAlias = configuredValue("KEY_ALIAS")?.takeIf { it.isNotEmpty() }
val releaseKeyPassword = configuredValue("KEY_PASSWORD")?.takeIf { it.isNotEmpty() }
val previousVersionCode = if (previousApk?.isFile == true && aapt?.isFile == true) {
    providers.exec { commandLine(aapt.absolutePath, "dump", "badging", previousApk.absolutePath) }
        .standardOutput.asText.map { output ->
            Regex("versionCode='([0-9]+)'").find(output)?.groupValues?.get(1)?.toIntOrNull()
                ?: throw GradleException("The previous APK does not have a readable version code.")
        }.get()
} else null

val validateReleaseInputs by tasks.registering(Exec::class) {
    group = "verification"
    doFirst {
        if (releaseKeystore?.isFile != true || releaseStorePassword == null ||
            releaseKeyAlias == null || releaseKeyPassword == null
        ) throw GradleException("Configure the original release keystore: KEYSTORE_FILE, KEYSTORE_PASSWORD, KEY_ALIAS and KEY_PASSWORD.")
        if (previousApk?.isFile != true || previousVersionCode == null || aapt?.isFile != true || apksigner?.isFile != true) {
            throw GradleException("Set PREVIOUS_RELEASE_APK to the last published APK and install Android SDK Build-Tools $androidBuildTools.")
        }
        if (releasePin == null || releaseName == null) {
            throw GradleException("Configure RELEASE_CERT_SHA256 and RELEASE_VERSION_NAME.")
        }
        val pinnedCert = releasePin.replace(":", "").lowercase()
        if (!Regex("[0-9a-f]{64}").matches(pinnedCert)) {
            throw GradleException("RELEASE_CERT_SHA256 must contain the original certificate’s SHA-256 fingerprint.")
        }
        val certificate = try {
            val keystore = KeyStore.getInstance(releaseKeystore, releaseStorePassword.toCharArray())
            if (keystore.getKey(releaseKeyAlias, releaseKeyPassword.toCharArray()) !is PrivateKey) {
                throw GradleException("The configured alias does not contain a private signing key.")
            }
            keystore.getCertificate(releaseKeyAlias) ?: throw GradleException("The key has no certificate.")
        } catch (error: Exception) {
            throw GradleException("Cannot read the original release signing key. Check its file, alias, and passwords.", error)
        }
        val keystoreCert = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
            .joinToString("") { "%02x".format(it) }
        if (keystoreCert != pinnedCert) {
            throw GradleException("The keystore does not match the pinned original release certificate.")
        }
        if (listOf("INNERTUBE_API_KEY", "YOUTUBE_API_KEY").any { key ->
                releaseSetting(key).let { it == null || it == "YOUR_API_KEY_HERE" }
            }
        ) {
            throw GradleException("Release builds require the existing INNERTUBE_API_KEY and YOUTUBE_API_KEY configuration.")
        }
        commandLine(
            python, compatibilityScript, "baseline",
            "--aapt", aapt, "--apksigner", apksigner, "--previous", previousApk,
            "--cert", releasePin, "--version-name", releaseName
        )
    }
}

android {
    namespace = appId
    compileSdk = 35
    buildToolsVersion = androidBuildTools
    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = appId
        minSdk = supportedMinSdk
        targetSdk = 35
        versionCode = previousVersionCode?.let { Math.addExact(it, 1) } ?: 1
        versionName = releaseName ?: "2.0.0"

        vectorDrawables { useSupportLibrary = true }

        listOf("INNERTUBE_API_KEY", "YOUTUBE_API_KEY").forEach { key ->
            val value = (releaseSetting(key) ?: "YOUR_API_KEY_HERE")
                .replace("\\", "\\\\").replace("\"", "\\\"")
            buildConfigField("String", key, "\"$value\"")
        }
    }

    signingConfigs {
        if (releaseKeystore?.isFile == true && releaseStorePassword != null &&
            releaseKeyAlias != null && releaseKeyPassword != null
        ) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
            isMinifyEnabled = false
            isDebuggable = true
        }
    }
    compileOptions {
        // ANDROID 8 FIX: Enable core library desugaring for java.time and other Java 8+ APIs
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.10"
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

val verifyReleaseApk by tasks.registering(Exec::class) {
    group = "verification"
    dependsOn(validateReleaseInputs)
    mustRunAfter("packageRelease")
    val releaseOutput = layout.buildDirectory.dir("outputs/apk/release")
    doFirst {
        val apk = releaseOutput.get().asFile.listFiles { file -> file.extension == "apk" }?.singleOrNull()
            ?: throw GradleException("Expected exactly one signed universal release APK.")
        commandLine(
            python, compatibilityScript, "candidate",
            "--aapt", aapt, "--apksigner", apksigner, "--previous", previousApk,
            "--cert", releasePin, "--version-name", releaseName,
            "--apk", apk, "--metadata", releaseOutput.get().file("sonicmusic-update.json").asFile
        )
    }
}

val hasReleaseCredentials = releaseKeystore?.isFile == true && releaseStorePassword != null &&
    releaseKeyAlias != null && releaseKeyPassword != null && releasePin != null

if (hasReleaseCredentials) {
    tasks.matching { it.name in setOf("preReleaseBuild", "validateSigningRelease") }.configureEach {
        dependsOn(validateReleaseInputs)
    }
    tasks.matching { it.name == "assembleRelease" }.configureEach {
        dependsOn(verifyReleaseApk)
    }
    verifyReleaseApk.configure { dependsOn("packageRelease") }
}

// ═══ Compose Compiler: stability config + recomposition reports ═══
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.addAll(
            // Strong skipping: skip recomposition even for unstable params when instance equality holds
            "-P", "plugin:androidx.compose.compiler.plugins.kotlin:experimentalStrongSkipping=true",
            "-P", "plugin:androidx.compose.compiler.plugins.kotlin:stabilityConfigurationPath=" +
                rootProject.file("compose-stability.conf").absolutePath,
            "-P", "plugin:androidx.compose.compiler.plugins.kotlin:reportsDestination=" +
                project.layout.buildDirectory.dir("compose_metrics").get().asFile.absolutePath,
            "-P", "plugin:androidx.compose.compiler.plugins.kotlin:metricsDestination=" +
                project.layout.buildDirectory.dir("compose_metrics").get().asFile.absolutePath
        )
    }
}

android {
    packaging {
        // AGP 8.2 stores uncompressed JNI libraries at 4 KB ZIP offsets.
        // Extract the 16 KB-compatible ELF libraries on install instead.
        jniLibs.useLegacyPackaging = true
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "DebugProbesKt.bin",
                "kotlin-tooling-metadata.json",
                "kotlin/**"
            )
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    // ANDROID 8 FIX: Core library desugaring for java.time and other Java 8+ APIs
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")
    
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.bundles.compose)
    implementation(libs.androidx.material3.windowsizeclass)
    
    // Navigation
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    
    // Hilt
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
    implementation(libs.androidx.hilt.work)
    kapt(libs.androidx.hilt.compiler)
    
    // WorkManager
    implementation(libs.androidx.work.runtime.ktx)
    
    // Room
    implementation(libs.bundles.room)
    kapt(libs.androidx.room.compiler)
    
    // Media3
    implementation(libs.bundles.media3)
    implementation(libs.androidx.media) // For MediaStyle notification
    
    // Networking
    implementation(libs.bundles.networking)
    
    // Image Loading
    implementation(libs.coil.compose)
    
    // Graphics Shapes for expressive polygons and morphing
    implementation(libs.androidx.graphics.shapes)
    
    // Coroutines
    implementation(libs.google.play.auth)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)

    // DataStore
    implementation(libs.androidx.datastore)

    // Kotlinx Serialization
    implementation(libs.kotlinx.serialization.json)
    
    // Palette
    implementation(libs.androidx.palette)

    // Baseline Profiles
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    
    // Material Color Utilities & Material Components
    implementation(libs.material.color.utilities)
    implementation(libs.google.material)
    
    // NewPipe Extractor (YouTube stream extraction)
    implementation(libs.newpipe.extractor)
    implementation(libs.rhino)
    
    // Reorderable (Drag & Drop for Queue)
    implementation(libs.reorderable)

    debugImplementation(libs.androidx.ui.tooling)
}

kapt {
    correctErrorTypes = true
}

configurations.all {
    resolutionStrategy {
        force("org.jetbrains.kotlinx:kotlinx-serialization-core:1.6.2")
        force("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
    }
}
