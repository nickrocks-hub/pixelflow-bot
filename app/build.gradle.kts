plugins {
    id("com.android.application")
}

android {
    namespace = "com.aigame.minesweeper"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aigame.minesweeper"
        minSdk = 26          // adaptive icons, gesture dispatch
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"
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
        // Release build: not debuggable, so the vision code runs at full speed.
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
