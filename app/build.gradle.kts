plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.tidyhome"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.tidyhome"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.0.1-probe"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    buildFeatures {
        // 开发者工具页只在 debug 版出现（BuildConfig.DEBUG）
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// ADR-003：零第三方依赖，dependencies 块刻意留空（Kotlin 标准库由插件自动引入）
// ADR-011：允许测试专用依赖（只进单测，不进 APK）
dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")
}
