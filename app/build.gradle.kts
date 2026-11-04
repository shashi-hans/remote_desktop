plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "rdpmobile.app"
    compileSdk = 36
    // Used to strip debug symbols from the FreeRDP .so files when packaging.
    ndkVersion = "29.0.13113456"

    defaultConfig {
        applicationId = "rdpmobile.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            // Personal sideload build: signed with this machine's debug key.
            // Use a real keystore before giving the APK to anyone else.
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        jniLibs.useLegacyPackaging = false
    }
}

dependencies {
    implementation(project(":rdpcore"))
    // Embedded Tailscale node; rebuild with scripts/build-tsbridge.sh.
    implementation(files("libs/tsbridge.aar"))

    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    // Custom Tabs: Tailscale login opens as a tab over the app, which Google and
    // Microsoft sign-in allow (they block plain WebViews).
    implementation("androidx.browser:browser:1.8.0")

    testImplementation("junit:junit:4.13.2")
}
