# Vana-Android

Vana 的 Android 版。iOS 版在 [`../Vana-iOS`](../Vana-iOS)（SwiftUI），两边是同一个产品的两个客户端：
一个通过工具调用读健康数据、对话式分析的 agent。

**当前状态：只有工程骨架，没有功能代码。** 跑起来是一屏占位文字。

## 构建

```bash
./gradlew :app:assembleDebug
```

装到连着的设备上：

```bash
./gradlew :app:installDebug
```

## 测试

两套，都要过（和 iOS 版同构）：

```bash
./gradlew :agent-runtime:test   # agent core，秒级，不需要模拟器
./gradlew :app:testDebugUnitTest
```

## 环境

- JDK 21（`java -version` 确认），模块编译目标是 17
- Android SDK：`~/development/android_sdk`，写在 `local.properties` 里（不进版本库）
- Gradle 8.14.3 走 wrapper，不需要全局安装
- AGP 8.12.1 / Kotlin 2.2.10 / Compose BOM 2025.08.00
- `compileSdk 36` / `targetSdk 36` / **`minSdk 34`**（理由见 `CLAUDE.md`）

## 模块

| 模块 | 是什么 | iOS 对应 |
| --- | --- | --- |
| `:agent-runtime` | 纯 Kotlin/JVM。工具循环、上下文预算、压缩降级。**不认识 Health Connect，也不认识任何模型 SDK** | `AgentRuntime`（本地 SwiftPM 包） |
| `:app` | Compose 界面 + 健康数据 + 各项能力 | `HealthChat` target |
