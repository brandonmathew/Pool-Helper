plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val signingFile = System.getenv("PH_KEYSTORE_FILE")
val hasSigning = !signingFile.isNullOrBlank()

android {
    namespace = "com.poolhelper.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.poolhelper.app"
        minSdk = 26
        targetSdk = 35
        // CI assigns versionCode (monotonic across both channels) and
        // versionName (semver; betas are <target>-beta.N). Feature branches
        // never set these.
        versionCode = System.getenv("PH_VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("PH_VERSION_NAME") ?: "1.0.0"
    }

    signingConfigs {
        if (hasSigning) {
            create("release") {
                storeFile = file(signingFile)
                storePassword = System.getenv("PH_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("PH_KEY_ALIAS")
                keyPassword = System.getenv("PH_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (hasSigning) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
        debug {
            // Debug builds share the release applicationId so a beta install
            // and a CI debug install update each other instead of conflicting.
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

dependencies {
    implementation(project(":core"))
    implementation("androidx.core:core:1.13.1")
}
