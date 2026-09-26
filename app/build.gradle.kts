plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.panapods"
    compileSdk = 35

    signingConfigs {
        create("release") {
            storeFile = file("../keystore.jks")
            storePassword = "123456"
            keyAlias = "az100release"
            keyPassword = "123456"
        }
    }

    defaultConfig {
        applicationId = "com.panapods"
        minSdk = 35
        targetSdk = 35
        versionCode = 184
        versionName = "1.0.184"

        // minSdk >= 21 时系统原生支持 multidex，无需 multiDexEnabled / multiDexKeepProguard。
        // Xposed 入口类 (HookEntry) 由 proguard-rules.pro 的 -keep 规则保护，不会被 R8 裁剪。
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = "21"
    }

    buildFeatures {
        compose = true
        // v164：启用 BuildConfig，设置页「关于」直接读 BuildConfig.VERSION_NAME，
        // 从此版本号与实际构建版本永远一致，不再手写硬编码字符串。
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // AndroidX Core
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.activity.compose)

    // Jetpack Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    // Navigation
    implementation(libs.navigation.compose)

    // Serialization
    implementation(libs.kotlinx.serialization.json)

    // Xposed 传统 API 兼容层(项目内自带实现，桥接LSPosed 现代 API)

    // LSPosed modern API (io.github.libxposed)
    compileOnly("io.github.libxposed:api:102.0.0")

    // JVM 单元测试
    testImplementation(libs.junit)
}
