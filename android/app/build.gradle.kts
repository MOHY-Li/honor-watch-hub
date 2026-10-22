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
        versionCode = 20000
        versionName = "2.0.0"

        // 自更新清单地址（CI 发布时覆盖 dist/latest.json）
        buildConfigField("String", "UPDATE_MANIFEST_URL", "\"https://raw.githubusercontent.com/MOHY-Li/honor-watch-hub/master/dist/latest.json\"")
    }

    signingConfigs {
        create("release") {
            // CI 通过环境变量注入；本地从 .keystore/ 读
            val ksPath = System.getenv("WATCHHUB_KEYSTORE")
                ?: rootProject.file("../.keystore/watchhub.jks").absolutePath
            val ksFile = file(ksPath)
            if (ksFile.exists()) {
                storeFile = ksFile
                storePassword = System.getenv("WATCHHUB_STORE_PASS") ?: "watchhub2026"
                keyAlias = System.getenv("WATCHHUB_KEY_ALIAS") ?: "watchhub"
                keyPassword = System.getenv("WATCHHUB_KEY_PASS") ?: "watchhub2026"
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // minSdk 24 上用 java.time 需要 desugaring（重复规则全部依赖 LocalTime/DayOfWeek）
        isCoreLibraryDesugaringEnabled = true
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

    lint {
        // CI 里 lint 仍会跑，但新代码的系统 API 使用（精确闹钟/日历/前台服务）
        // 属于误报重灾区，不因此阻断发布；关键问题看单测和人工验证。
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.fragment:fragment-ktx:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    testImplementation("junit:junit:4.13.2")
    // 单测里用真实的 org.json 实现（Android 的 org.json 在 JVM 单测中是空壳）
    testImplementation("org.json:json:20240303")
}
