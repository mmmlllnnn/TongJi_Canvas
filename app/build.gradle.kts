

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.mln.tongji_canvas"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mln.tongji_canvas"
        minSdk = 28
        targetSdk = 36
        // versionCode 必须单调递增（否则无法覆盖安装）。
        // 4.0 相对 3.2.1(20260520) 是一次大版本：签到引擎重写为 HTTP 直发、
        // 新增签到历史与诊断日志、页内重新扫码。取当日日期保证大于旧值。
        versionCode = 20261008
        versionName = "4.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    }

    // 启用 ABI 分包
    splits {
        abi {
            isEnable = true
            reset()
            // 仅为下列 ABI 生成分包 APK
            include("arm64-v8a", "armeabi-v7a", "x86")
            // 同时额外产出一个包含全部 ABI 的通用 APK（便于用户直接安装，无需挑选架构）
            // 注意：这与上面「分包」并存，发布时会同时得到分包 APK 和通用 APK。
            isUniversalApk = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlin {
        compilerOptions {
            // 与 compileOptions 的 Java 21 保持一致。
            // 若不显式指定，Kotlin 会跟随当前 JDK（如 JDK 25 会回退到 JVM_22），
            // 从而触发 "Inconsistent JVM-target compatibility" 构建失败。
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Core Android libraries
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    
    // Compose BOM and core UI
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.accompanist.systemuicontroller)
    implementation(libs.material)
    
    // Navigation
    implementation(libs.androidx.navigation.compose)
    
    // CameraX + ML Kit for QR scanning
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.camera.mlkit.vision)
    implementation(libs.mlkit.barcode.scanning)
    
    // Permissions helper
    implementation(libs.accompanist.permissions)
    
    // OkHttp for network requests
    implementation(libs.okhttp)
    
    // Debug tools (only in debug builds)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    
    // Test dependencies
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}