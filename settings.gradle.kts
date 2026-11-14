pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // 本地 sherpa-onnx AAR：tools/install_sherpa_aar.py 安装后从这里解析。
        maven { url = uri(rootDir.resolve("local-maven")) }
        // JitPack 仅作备选坐标；默认工程不依赖它，保证可离线重复构建。
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "LiveSubtitleAndroid"
include(":app")
