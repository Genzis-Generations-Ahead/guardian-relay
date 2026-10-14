plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.guardianmesh.relay"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.guardianmesh.relay"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "0.2-demo"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    testOptions {
        // org.json in unit tests: android.jar only ships stubs, so pull the real impl in for tests.
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // The SOS/BLE/location/safe-exit core is deliberately framework-only (no AndroidX,
    // OkHttp, coroutines or Play services), so it drops into any UI stack Tanush picks.
    // Add AppCompat/Compose here for the real UI as needed.

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
