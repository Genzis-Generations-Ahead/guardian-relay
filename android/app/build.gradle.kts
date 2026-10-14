plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.guardianmesh.relay"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.guardianmesh.relay"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "0.3-app"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
        // AGP 8 turns BuildConfig off by default; ApiProvider reads BuildConfig.DEBUG.
        buildConfig = true
    }

    composeOptions {
        // Must match the Kotlin version in the root build.gradle.kts (1.9.24).
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    testOptions {
        // org.json in unit tests: android.jar only ships stubs, so pull the real impl in for tests.
        unitTests.isReturnDefaultValues = true
    }
    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

dependencies {
    // ── The SOS / BLE / location / safe-exit CORE stays framework-only ──
    // Nothing below is imported from com.guardianmesh.relay.{sos,ble,location,safeexit,net,diag}.
    // Those packages still compile with zero third-party libraries, which is what keeps the
    // offline fallback chain small, fast and unit-testable on a plain JVM.

    // ── UI layer: Compose + Material 3 ──
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // ── Persistence: Room (emergency contacts + settings + duress PIN hashes) ──
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // ── Networking for the UI layer: Retrofit (single BASE_URL in net/api/ApiConfig.kt) ──
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
