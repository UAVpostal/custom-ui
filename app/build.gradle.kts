plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.cogco.home"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.cogco.home"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    // A fixed debug key kept in the repo, so every build (on any machine) is signed the same way
    // and new versions install over the old one without uninstalling first.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signs release builds with the debug key so the APK installs straight away.
            // Swap in your own signing config before publishing anywhere.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// No third-party libraries: everything is built on the Android framework + Kotlin stdlib.
dependencies {
}
