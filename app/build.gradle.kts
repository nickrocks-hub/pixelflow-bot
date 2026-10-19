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
        versionCode = 4
        versionName = "1.3"
    }

    // Fixed key committed to the repo, so every build from GitHub installs as an update.
    signingConfigs {
        create("fixed") {
            storeFile = file("release.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        // Release build: not debuggable, so Android runs the vision code at full speed.
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
        }
        debug {
            signingConfig = signingConfigs.getByName("fixed")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// No external libraries: plain Android framework APIs only. Everything runs on the phone.
