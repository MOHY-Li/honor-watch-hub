plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.watchhub.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.watchhub.app"
        minSdk = 24
        targetSdk = 34
        // versionCode 必须单调递增，自更新靠它比较新旧
        versionCode = 10202
        versionName = "1.2.2"

        // 自更新清单地址（CI 发布时覆盖 dist/latest.json）
        buildConfigField("String", "UPDATE_MANIFEST_URL", "\"https://raw.githubusercontent.com/MOHY-Li/honor-watch-hub/master/dist/latest.json\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions {
        unitTests {
            // JVM 单测里 android.util.Log / Build.VERSION 等是空壳，
            // 打开后返回默认值，避免 "not mocked" 异常。
            isReturnDefaultValues = true
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
    // 单测里用真实的 org.json 实现（Android 的 org.json 在 JVM 单测中是空壳）
    testImplementation("org.json:json:20240303")
}


