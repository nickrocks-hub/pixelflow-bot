plugins {
    id("com.android.application")
}

android {
    namespace = "com.riderbot.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.riderbot.app"
        minSdk = 26          // continued gesture strokes need Android 8.0+
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the debug key so `assembleRelease` gives an installable APK for personal use.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // the replay tool lives in /tools and is not part of the app
}

