plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.hourstracker"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.hourstracker2"
        minSdk = 26
        targetSdk = 36
        versionCode = 48
        versionName = "3.28"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        getByName("debug") {
            // minSdk 26 skips v1 by default; force all schemes so the APK
            // installs reliably across runtimes.
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    kotlinOptions { jvmTarget = "17" }
}