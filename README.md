# Vana-Android

Vana 的 Android 版。iOS 版在 [`../Vana-iOS`](../Vana-iOS)（SwiftUI），两边是同一个产品的两个客户端：
一个通过工具调用读健康数据、对话式分析的 agent。

**当前状态：与 iOS 功能对等的健康聊天客户端。** 对话、记忆/用药、搜索、召回、OCR、check-in、首屏建议、快捷方式等均已落地。**Health Connect 暂缓**（大陆机普遍没有 Google HC），见下。

## 已实现

- 云端对话（OpenAI / Anthropic 兼容 SSE）、工具循环与上下文压缩（`:agent-runtime`）
- 记忆 / 用药表工具与列表；隐私会话不写盘
- 网页搜索（Serper，key 门控）、`ask_user` 选项卡、会话召回（提起过去才解锁）
- 追问 chip、粗定位城市、助手 persona、家庭成员切换
- 照片 OCR（ML Kit 中文 + 多列 `RecognizedTextLayout`）+ 可选原图（视觉模型 / 照片策略）
- Word（docx）文本导入；`suggest_exercises` 动作库与 SVG 卡片
- 早晚 check-in 本地通知；会话结束记忆抽取（非隐私）
- `SpokenBrief` 本地口播 + App Shortcuts / Assistant deep link（今天状态、睡眠、锻炼、问 Vana）
- 设置页模型能力标签（看图 / 思考 / 不支持工具）；Debug 开发页

## Health Connect 暂缓

大陆发行版手机通常没有 Health Connect。`Features.HEALTH_CONNECT = false`：不挂健康工具、不弹 HC 授权/安装。`HealthStore` 等代码保留。细节见 [`CLAUDE.md`](./CLAUDE.md)。

## 有意保留的平台差异

| 能力 | iOS | Android |
| --- | --- | --- |
| 本机健康数据 | HealthKit | **暂缓** Health Connect（大陆机无 HC） |
| 化验 FHIR / `health_records` | HealthKit clinical records | 未接；化验单靠 OCR / 文档导入 |
| 文档扫描纠偏 | VisionKit `DocumentScanner` | 未接；相册 / 相机拍照 + OCR |
| 语音助手入口 | Siri App Intents（后台念 SpokenBrief） | App Shortcuts / Assistant deep link 打开 app 展示 |

其余行为（辅助调用关思考、隐私会话定义、Tenant 隔离）两边一致。

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
- Android SDK：写在 `local.properties` 里（不进版本库）
- Gradle 走 wrapper；AGP / Kotlin / Compose 版本见 `gradle/libs.versions.toml`
- `compileSdk 36` / `targetSdk 36` / **`minSdk 28`**（API 34 以下会引导安装 Health Connect，见 `CLAUDE.md`）

## 模块

| 模块 | 是什么 | iOS 对应 |
| --- | --- | --- |
| `:agent-runtime` | 纯 Kotlin/JVM。工具循环、上下文预算、压缩降级。**不认识 Health Connect，也不认识任何模型 SDK** | `AgentRuntime`（本地 SwiftPM 包） |
| `:app` | Compose 界面 + 健康数据 + 各项能力 | `HealthChat` target |

设计边界以 [`CLAUDE.md`](./CLAUDE.md) 与 iOS 的 `CLAUDE.md` 为准。
