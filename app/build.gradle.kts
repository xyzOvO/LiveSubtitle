plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.wo.livesub"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.wo.livesub"
        // AudioPlaybackCaptureConfiguration 自 Android Q(API 29) 起可用。
        minSdk = 29
        // 故意压在 34：避免 Android 15 (API 35) 对「后台启动 FGS 前必须已有可见 overlay」的新限制。
        // 这不等同于绕过合规要求，只是减少第一版的平台差异；后续适配 API 35 再调整。
        targetSdk = 34
        // versionCode 必须随每次装机变化：MIUI 桌面按 versionCode 缓存图标，
        // 版本号不变时换了图标也不会刷新（换图标后仍显示旧图的直接原因）。
        versionCode = 108
        versionName = "1.0.8"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
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

    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets {
        getByName("main") {
            // 模型文件由 tools/prepare_models.py 放入 assets；运行时只从本地 assets 读取。
            assets.srcDirs("src/main/assets")
        }
    }

    // 大模型走 assets 不压缩：避免 compressDebugAssets 把上百 MB 文件读进 JVM 堆导致 OOM，
    // 也让 sherpa-onnx 原生层经 AssetManager 读模型时更稳定。
    androidResources {
        noCompress("onnx", "tsv", "txt", "json", "md")
    }

    packaging {
        jniLibs {
            // 静态链接 sherpa AAR 的 arm64-v8a 不带 libonnxruntime.so，但其它 ABI 仍带；
            // ORT AAR 也带同名库，mergeDebugNativeLibs 会先撞 duplicate，故对同名库取第一份。
            pickFirsts += "**/libonnxruntime.so"
            // 静态链接 sherpa AAR 与 onnxruntime-android 只保留 arm64-v8a；
            // 若两者都带 libc++_shared.so，取第一份避免 duplicate .so。
            pickFirsts += "**/libc++_shared.so"
        }
        resources {
            excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE", "META-INF/LICENSE.txt", "META-INF/NOTICE", "META-INF/NOTICE.txt")
        }
    }
}

dependencies {
    // sherpa-onnx v1.12.33 官方 release AAR；tools/install_sherpa_aar.py 校验 SHA-256 后放入 local-maven。
    implementation("com.k2fsa.sherpa.onnx:sherpa-onnx:1.12.33")
    // 离线 MT：仅使用 ONNX Runtime Java API，不引入任何网络翻译 SDK。
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.29.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
