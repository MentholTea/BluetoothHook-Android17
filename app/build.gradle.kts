plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.jingyu233.bluetoothhook"
    // libxposed:service 102.0.0 的 aar-metadata 要求 minCompileSdk=37
    compileSdk = 37

    defaultConfig {
        applicationId = "com.jingyu233.bluetoothhook"
        minSdk = 29
        targetSdk = 36
        versionCode = 3
        versionName = "2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        // 必须 >= Java 17：AGP 的 ComponentImpl.isGlobalSyntheticsEnabled() 逻辑是
        //   ENABLE_API_MODELING_AND_GLOBAL_SYNTHETICS ?: (debuggable ? javaLevel > 14 : true)
        // debug 变体 debuggable=true，若语言级别只有 11 则 global synthetics 被关掉，
        // dex 外部库（libxposed:service 含 Java record）时报
        // "Attempt to create a global synthetic for 'Record desugaring'".
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // libxposed:service 102.0.0 内部使用 Java record，D8 需要开启 core library
        // desugaring 才能生成 record 的 global synthetic。
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    // core library desugaring（处理 libxposed:service 里的 Java record）
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    // libxposed modern API（API 102）。compileOnly：运行时由框架提供。
    compileOnly(libs.libxposed.api)
    // service：模块 App 侧与框架通信（远程偏好 / 作用域），需要打进 APK。
    implementation(libs.libxposed.service)

    // AndroidX Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // ViewModel
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Room Database
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Serialization
    implementation(libs.kotlinx.serialization.json)

    // WebDAV
    implementation(libs.sardine.android)

    // WorkManager
    implementation(libs.androidx.work.runtime.ktx)

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}