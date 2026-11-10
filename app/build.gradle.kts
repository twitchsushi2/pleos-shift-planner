import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.pleoscontest.app"
    compileSdk = 37

    // 표준 Android Automotive 차량 API(CarPropertyManager). 컴파일에만 쓰고, 실제 구현은 차량(에뮬레이터) OS에 들어 있다.
    useLibrary("android.car")

    defaultConfig {
        applicationId = "com.pleoscontest.app"
        // Automotive 에뮬레이터 이미지가 Android 14(API 34)다. Pleos Connect도 AAOS 기반이라 이보다 낮게 둘 이유가 없다.
        minSdk = 34
        targetSdk = 37
        versionCode = 2
        versionName = "1.0.0"

        // Pleos Connect 차량과 에뮬레이터 이미지는 64비트 ARM이다. 지도·보안 라이브러리의 다른 CPU용 파일(x86, 32비트 ARM 등)을
        // 빼면 APK가 91MB에서 크게 준다.
        ndk { abiFilters += "arm64-v8a" }

        // 차량 데이터를 어디서 읽을지. -Pvehicle=mock|car|pleos|auto
        // (기본 auto: Pleos Connect면 pleos, 다른 차량 OS면 car, 폰이면 mock).
        // 폰 에뮬레이터에서 화면만 볼 때, 또는 Automotive 에뮬레이터에서 일부러 시뮬레이션 주행을 돌릴 때 mock을 쓴다.
        val vehicle = providers.gradleProperty("vehicle").orNull ?: "auto"
        buildConfigField("String", "VEHICLE_SOURCE", "\"$vehicle\"")
    }

    buildTypes {
        // 에뮬레이터에서 5시간을 달릴 수는 없으니 디버그 빌드는 시간을 빠르게 돌린다. 기본 60배(실제 1초 = 주행 1분).
        // -PdemoSpeed=1 이면 실제 시간대로 돈다.
        debug {
            buildConfigField("int", "DEMO_TIME_SCALE", providers.gradleProperty("demoSpeed").orNull ?: "60")
        }
        // 출시 빌드. 실제 시간대로 돈다.
        // 설치할 수 있게 디버그 키로 서명한다. App Market에 올릴 때는 별도 출시 키로 바꾼다.
        release {
            buildConfigField("int", "DEMO_TIME_SCALE", "1")
            signingConfig = signingConfigs.getByName("debug")
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
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(libs.pleos.vehicle)
    implementation(libs.pleos.tts)
    implementation(libs.pleos.navi)
    implementation(libs.pleos.stt)
    implementation(libs.maplibre.android)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.core)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
