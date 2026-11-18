import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "io.embodit.spatialoffice"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.embodit.spatialoffice"
        minSdk = 35
        targetSdk = 35
        versionCode = 5
        versionName = "0.2.3"

        // Cloud accounts (PRD §9): each service needs an app registration the user makes (README, "Connecting accounts").
        // Fill these in local.properties (never committed); left empty, the service says "register the app first".
        val local = Properties().also { p -> rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { p.load(it) } }
        fun cfg(key: String, default: String = "") = "\"" + (local.getProperty(key) ?: default).replace("\"", "") + "\""
        buildConfigField("String", "FEISHU_APP_ID", cfg("cloud.feishu.appId"))
        buildConfigField("String", "FEISHU_APP_SECRET", cfg("cloud.feishu.appSecret"))
        buildConfigField("String", "FEISHU_REDIRECT", cfg("cloud.feishu.redirect", "http://127.0.0.1:53682/callback"))
        buildConfigField("String", "GOOGLE_CLIENT_ID", cfg("cloud.google.clientId"))
        buildConfigField("String", "GOOGLE_CLIENT_SECRET", cfg("cloud.google.clientSecret"))
        buildConfigField("String", "GOOGLE_REDIRECT", cfg("cloud.google.redirect", "http://127.0.0.1:53682/callback"))
        buildConfigField("String", "ONEDRIVE_CLIENT_ID", cfg("cloud.onedrive.clientId"))
        buildConfigField("String", "ONEDRIVE_REDIRECT", cfg("cloud.onedrive.redirect", "spatialoffice://oauth/onedrive"))
        buildConfigField("String", "DROPBOX_APP_KEY", cfg("cloud.dropbox.appKey"))
        buildConfigField("String", "DROPBOX_REDIRECT", cfg("cloud.dropbox.redirect", "http://localhost:53682/callback"))

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters.add("arm64-v8a") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(platform(libs.spatial.bom))
    implementation(libs.spatial.core)
    implementation(libs.spatial.ui.platform)
    implementation(libs.spatial.ui.foundation)
    implementation(libs.spatial.ui.design)
    implementation(libs.spatial.ui.sense)
    implementation(libs.spatial.ui.tracking)
    implementation(libs.androidx.ui.tooling)
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation("androidx.security:security-crypto:1.1.0")
    // QR code for scan-to-sign-in (PRD §9.2 ③a); Apache 2.0, encoder only
    implementation("com.google.zxing:core:3.5.3")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    debugImplementation(libs.androidx.ui.tooling.preview)
}

configurations.all {
    resolutionStrategy {
        exclude("androidx.compose.ui", "ui")
        exclude("androidx.compose.ui", "ui-graphics")
        exclude("androidx.compose.ui", "ui-text")
        exclude("androidx.compose.foundation", "foundation")
    }
}
