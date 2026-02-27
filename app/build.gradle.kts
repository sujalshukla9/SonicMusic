import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.sonicmusic.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sonicmusic.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "1.0-Beta"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        buildConfigField("String", "APP_VERSION", "\"1.0-Beta\"")

        val localProperties = Properties()
        val localPropertiesFile = rootProject.file("local.properties")
        if (localPropertiesFile.exists()) {
            localProperties.load(FileInputStream(localPropertiesFile))
        }
        
        buildConfigField("String", "INNERTUBE_API_KEY", "\"${localProperties.getProperty("INNERTUBE_API_KEY", "YOUR_API_KEY_HERE")}\"")
        buildConfigField("String", "YOUTUBE_API_KEY", "\"${localProperties.getProperty("YOUTUBE_API_KEY", "YOUR_API_KEY_HERE")}\"")
        }
        
        signingConfigs {
            create("release") {
                val keystoreFile = rootProject.file("keystore/sonicmusic.jks")
                if (keystoreFile.exists()) {
                    storeFile = keystoreFile
                    storePassword = "sonicmusic"
                    keyAlias = "sonicmusic"
                    keyPassword = "sonicmusic"
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
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose)
    
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
