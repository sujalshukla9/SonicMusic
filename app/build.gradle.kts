import java.util.Properties
import java.io.FileInputStream
import org.gradle.api.GradleException

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
}

android {
    val localProperties = Properties()
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localProperties.load(FileInputStream(localPropertiesFile))
    }

    val innertubeApiKey = localProperties.getProperty("INNERTUBE_API_KEY", "").trim()
    val youtubeApiKey = localProperties.getProperty("YOUTUBE_API_KEY", "").trim()
    val isReleaseBuild = gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }

    namespace = "com.sonicmusic.app"
    compileSdk = 35
    ndkVersion = "26.1.10909125"

    defaultConfig {

        applicationId = "com.sonicmusic.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 8
        versionName = "1.5.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        buildConfigField("String", "APP_VERSION", "\"1.5.1\"")


        buildConfigField(
            "String",
            "INNERTUBE_API_KEY",
            "\"${if (innertubeApiKey.isNotBlank()) innertubeApiKey else "YOUR_API_KEY_HERE"}\""
        )
        buildConfigField(
            "String",
            "YOUTUBE_API_KEY",
            "\"${if (youtubeApiKey.isNotBlank()) youtubeApiKey else "YOUR_API_KEY_HERE"}\""
        )

    }


        
        signingConfigs {
            create("release") {
                val releaseKeystorePath =
                    System.getenv("KEYSTORE_FILE") ?: localProperties.getProperty("KEYSTORE_FILE")
                val releaseStorePassword =
                    System.getenv("KEYSTORE_PASSWORD") ?: localProperties.getProperty("KEYSTORE_PASSWORD")
                val releaseKeyAlias =
                    System.getenv("KEY_ALIAS") ?: localProperties.getProperty("KEY_ALIAS")
                val releaseKeyPassword =
                    System.getenv("KEY_PASSWORD") ?: localProperties.getProperty("KEY_PASSWORD")

                if (isReleaseBuild) {
                    if (innertubeApiKey.isBlank() || youtubeApiKey.isBlank()) {
                        throw GradleException(
                            "Release builds require INNERTUBE_API_KEY and YOUTUBE_API_KEY in local.properties or environment variables."
                        )
                    }
                    if (
                        releaseKeystorePath.isNullOrBlank() ||
                        releaseStorePassword.isNullOrBlank() ||
                        releaseKeyAlias.isNullOrBlank() ||
                        releaseKeyPassword.isNullOrBlank()
                    ) {
                        throw GradleException(
                            "Release signing credentials are missing. Configure KEYSTORE_FILE, KEYSTORE_PASSWORD, KEY_ALIAS, and KEY_PASSWORD in local.properties or environment variables."
                        )
                    }
                }

                if (!releaseKeystorePath.isNullOrBlank()) {
                    val releaseKeystoreFile = file(releaseKeystorePath)
                    if (!releaseKeystoreFile.exists()) {
                        throw GradleException("Release keystore file does not exist: $releaseKeystorePath")
                    }
                    storeFile = releaseKeystoreFile
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
                signingConfig = signingConfigs.getByName("release")
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
    androidTestImplementation(platform(libs.androidx.compose.bom))
    // ANDROID 8 FIX: Core library desugaring for java.time and other Java 8+ APIs
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")
    
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
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
    
    // Coroutines
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
    
    // Material Color Utilities
    implementation(libs.material.color.utilities)
    
    // NewPipe Extractor (YouTube stream extraction)
    implementation(libs.newpipe.extractor)
    implementation(libs.rhino)
    
    // Reorderable (Drag & Drop for Queue)
    implementation(libs.reorderable)

    // Paging
    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)
    
    // Testing
    testImplementation(libs.junit)
    testImplementation("org.mockito:mockito-inline:5.2.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.2.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
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
