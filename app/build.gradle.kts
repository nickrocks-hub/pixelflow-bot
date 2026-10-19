plugins {
    id("com.android.application")
}

android {
    namespace = "com.aigame.subwaysurfers"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aigame.subwaysurfers"
        minSdk = 26          // gesture dispatch with continued strokes, adaptive icons
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// No external libraries: plain Android framework APIs only. Everything runs on the phone.
