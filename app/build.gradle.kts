plugins {
    id("com.android.application")
}

android {
    namespace = "com.nickrocks.aibot"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nickrocks.aibot"
        minSdk = 26          // continued gestures (holding the joysticks) need Android 8
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
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
        // Release build: not debuggable, so the screen reader runs at full speed.
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

// No external libraries: plain Android framework APIs only. Everything runs on the phone, offline.
