plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// CI passes these so every release gets a higher version (Obtainium needs that to see updates).
val ciVersionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
val ciVersionName = System.getenv("VERSION_NAME") ?: "1.0.0-dev"

android {
    namespace = "com.mossab.focusguard"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mossab.focusguard"
        minSdk = 29
        targetSdk = 34
        versionCode = ciVersionCode
        versionName = ciVersionName
    }

    signingConfigs {
        create("release") {
            // The same key must sign every release, or Android refuses the update.
            storeFile = rootProject.file("signing/release.jks")
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "focusguard"
            keyAlias = "focusguard"
            keyPassword = System.getenv("KEYSTORE_PASSWORD") ?: "focusguard"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}
