import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.donghua.tv"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.donghua.tv"
        minSdk = 21
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("keystore/donghua.jks")
            storePassword = "donghua123"
            keyAlias = "donghua"
            keyPassword = "donghua123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.01.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.18.1")
    implementation("io.coil-kt:coil-compose:2.7.0")
    // The Views flavour, for the chapter grid inside the player. The Compose
    // artifact above does not cover the dialogs, which are plain Views.
    implementation("io.coil-kt:coil:2.7.0")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    // OkHttp data source: HTTP/2 + connection pooling for HLS segments (much smoother)
    implementation("androidx.media3:media3-datasource-okhttp:1.5.1")
    // Disk cache so segments already downloaded are reused and hiccups never stall playback
    implementation("androidx.media3:media3-database:1.5.1")
    // The player HUD is laid out with percentage constraints so it scales with
    // the screen instead of with density, which is wrong on televisions.
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")
}
