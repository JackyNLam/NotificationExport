plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// In CI these come from the workflow (VERSION_CODE = GitHub run number), so every
// build gets a higher versionCode and can update the previously installed APK.
val appVersionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
val appVersionName = System.getenv("VERSION_NAME") ?: "1.0"
val releaseKeystorePath = System.getenv("RELEASE_KEYSTORE_PATH")

android {
    namespace = "com.example.notificationexport"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.notificationexport"
        minSdk = 24
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // Only created when a keystore is supplied (CI). Local builds without the
        // env var stay unsigned, so `assembleDebug` keeps working unchanged.
        if (releaseKeystorePath != null) {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storeType = "PKCS12"
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseKeystorePath != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}