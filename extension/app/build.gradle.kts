import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.jetbrains.kotlin.serialization)
    alias(libs.plugins.jetbrains.kotlin.compose)
}

android {
    namespace = "io.roadbook.karoo"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.roadbook.karoo"
        minSdk = 23
        targetSdk = 34
        // versionCode: CI injects the monotonic run number; defaults to 1 locally.
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = "0.7.0" // x-release-please-version

        // Karoo is arm64 — only ship that ABI of the bundled SQLite native lib.
        ndk {
            abiFilters += "arm64-v8a"
        }

        // Google Places API key for the on-demand "check hours on Google" feature.
        // Read from local.properties (gitignored) or the PLACES_API_KEY env var; empty
        // when unset, which disables the feature at runtime. Never commit the key.
        val placesKey = run {
            val props = Properties()
            rootProject.file("local.properties").takeIf { it.exists() }
                ?.inputStream()?.use { props.load(it) }
            props.getProperty("PLACES_API_KEY") ?: System.getenv("PLACES_API_KEY") ?: ""
        }
        buildConfigField("String", "PLACES_API_KEY", "\"$placesKey\"")
    }

    buildTypes {
        release {
            // Debug signing for now so sideloaded builds install without a keystore.
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.karoo.ext)
    implementation(libs.timber)
    implementation(libs.qrose)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.sqlite.android)

    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.activity.compose)
    debugImplementation(libs.androidx.ui.tooling)
}
