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
        versionCode = 83
        versionName = "2.11.29"
    }

    // 로그/디버깅에서 BuildConfig.VERSION_NAME 접근용 (AGP 8 기본값은 false)
    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        debug {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // APK 크기 축소: ABI별 분할 (universal도 함께 생성)
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // extractNativeLibs 와 함께: 설치 시 .so 륔 압축 해제해 nativeLibraryDir 에 배치 (실행용)
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
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
    // 토렌트 순차 재생(받으면서 재생): Tincat과 동일 엔진
    implementation("org.libtorrent4j:libtorrent4j:2.1.0-38")
    implementation("org.libtorrent4j:libtorrent4j-android-arm64:2.1.0-38")
    implementation("org.libtorrent4j:libtorrent4j-android-arm:2.1.0-38")
}
