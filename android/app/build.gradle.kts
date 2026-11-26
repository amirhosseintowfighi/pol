plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.compose")
}

android {
    namespace = "studio.virgule.pol.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "studio.virgule.pol"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    // Two apps from one code base, so each install declares only the permissions its role needs:
    //  - gateway: the Android phone that holds the SIM cards (BLE peripheral, telephony access)
    //  - client:  an Android phone used in hand instead of (or as well as) an iPhone (BLE central, no telephony)
    flavorDimensions += "role"
    productFlavors {
        create("gateway") {
            dimension = "role"
            applicationIdSuffix = ".gateway"
            minSdk = 26
        }
        create("client") {
            dimension = "role"
            applicationIdSuffix = ".client"
            // Android 12+: BLUETOOTH_SCAN with neverForLocation, so the client never needs location permission.
            minSdk = 31
        }
    }

    // Release signing from environment variables (CI secrets). Never commit keystores or passwords.
    // Without them, release builds are signed with the debug key so test APKs still install, and are marked as such.
    val keystore = System.getenv("POL_KEYSTORE_FILE")
    signingConfigs {
        if (keystore != null) create("release") {
            storeFile = file(keystore)
            storePassword = System.getenv("POL_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("POL_KEY_ALIAS")
            keyPassword = System.getenv("POL_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            signingConfig = if (keystore != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }
}

dependencies {
    implementation("studio.virgule.pol:pol-core")

    implementation(platform("androidx.compose:compose-bom:2025.05.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Gateway only: Shizuku (Apache-2.0) starts the call-audio helper as the shell user over Binder. No network.
    "gatewayImplementation"("dev.rikka.shizuku:api:13.1.5")
    "gatewayImplementation"("dev.rikka.shizuku:provider:13.1.5")

    // Deliberately absent: analytics, crash reporting, ads, cloud SDKs, networking libraries.
    testImplementation("junit:junit:4.13.2")
}
