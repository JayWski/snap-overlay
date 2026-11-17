plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.snapoverlay"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.snapoverlay"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
    }

    // A fixed key, kept in the repo, so each new build installs over the old one
    // on the phone instead of asking to uninstall first. Personal app, not on the Play Store.
    signingConfigs {
        create("shared") {
            storeFile = file("snap-overlay.keystore")
            storePassword = "snapoverlay"
            keyAlias = "snapoverlay"
            keyPassword = "snapoverlay"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
