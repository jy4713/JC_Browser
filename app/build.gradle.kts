plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.streambrowser"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.streambrowser"
        minSdk = 24
        targetSdk = 34
        versionCode = 11
        versionName = "2.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    // FFmpeg Kit: HLS/DASH/TS/FLV 스트림 다운로드+합본
    implementation("com.arthenica:ffmpeg-kit-full-gpl:5.1")
}
