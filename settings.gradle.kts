pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // 这里**不能**用 FAIL_ON_PROJECT_REPOS:本机 `~/.gradle/init.d/aliyun-google-mirror.gradle`
    // 会在 beforeProject 里往 project.repositories 塞一个阿里云镜像(dl.google.com 在这个网络
    // 下连不上),FAIL 那一档会直接把构建打掉。PREFER_SETTINGS 一样把仓库收在这一处,
    // 只是遇到 project 级仓库时警告而不是报错。
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Vana-Android"

include(":app")

// iOS 那边 `AgentRuntime` 是一个本地 SwiftPM 包,不 import 任何模型 SDK、也不认识 HealthKit。
// 这里对应成一个纯 Kotlin/JVM 模块——**故意不是 Android library**:
// 拿不到 android.* 就写不出「顺手在这里查一下 Health Connect」这种代码,
// 那条边界由编译器守着,不靠自觉。
include(":agent-runtime")
