# Vana-Android

Vana 的 Android 客户端：Compose UI，agent 通过工具使用 OCR、用药、测量、记忆和召回能力进行对话式分析。

**iOS 版的 `CLAUDE.md` 是设计决策的主文档**（`../Vana-iOS/CLAUDE.md`）。那份东西里写的每一条
「不要破坏的边界」都是踩过之后写下来的，绝大多数和平台无关——记忆里不存易腐的数字、压缩要在
发请求之前做而不是撞墙之后、插话在轮边界接入、召回精度重于召回广度、隐私会话按写入路径定义
而不按名字。**动到对应的东西之前先去读那一份**，这里只记 Android 这一侧真正不一样的地方。

用户看到的名字是 **Vana**。工程名 `Vana-Android`，包名 `com.pinapia.vana`（pinapia.com 是
自己的域名）。

**当前状态：有意保留平台差异的健康聊天客户端。** 对话、记忆/用药、手工测量、搜索、召回、OCR、
check-in 和「问 Vana」App Shortcut 已落地。设备健康数据不属于 Android 产品能力。

## Android 不接入设备健康数据

目标 Android 手机没有可靠、统一、可连接的标准健康数据协议。这个限制决定了产品边界：

- 不依赖任何设备健康数据 SDK，不声明相关权限。
- 不提供安装、授权、同步、健康摘要、设备数据话题或快捷入口。
- 不在提示词里暗示能够读取手机、手表或第三方健康平台。
- 趋势分析只基于用户主动提供的 OCR / 文档、用药表、记忆和手工测量卡片。

## 构建与测试

```bash
./gradlew :app:assembleDebug
./gradlew :agent-runtime:test        # agent core，秒级，不需要模拟器
./gradlew :app:testDebugUnitTest
```

## 模块边界：`:agent-runtime` 是纯 Kotlin/JVM，不是 Android library

这是**故意的**，不是省事。iOS 那边 `AgentRuntime` 是个本地 SwiftPM 包，不 import 任何模型 SDK
也不认识 HealthKit；那条边界在这里由编译器守着——拿不到 `android.*`，就不会把平台能力塞进
工具循环。改成 `com.android.library` 的那一天，这条边界就只剩自觉了。

它只该认两个抽象：「一个能估 token、能流式跑一轮的模型」和「一组 JSON Schema 加一个执行闭包」。
工具循环、四档上下文降级、预算记账都在这一层，测试是秒级的。

## `minSdk 28`

`minSdk` 当前为 28。它不代表设备健康协议下限，只是当前应用兼容性基线。

## 其它几处平台替换

| iOS | Android | 注意 |
| --- | --- | --- |
| HealthKit | 不接入设备健康数据 | 使用 OCR、文档、用药、记忆和手工测量 |
| Keychain（API key） | `EncryptedSharedPreferences`（androidx.security-crypto） | Android 没有 Keychain；密钥由 Android Keystore 兜住，是最接近的一档 |
| `TenantPaths.excludeFromBackup` | `allowBackup="false"` + `data_extraction_rules.xml` | 代价是换新手机数据不跟着走，**隐私说明里要照实写**，别写反了 |
| Vision 文字识别 | ML Kit `text-recognition-chinese` + `RecognizedTextLayout` | 同样全在本机跑，照片默认不出这台设备；多列化验单按几何重建 |
| VisionKit DocumentScanner | 未接 | 相册 / 相机 + OCR；不假装有纠偏扫描仪 |
| `SpeechAnalyzer`/`SpeechTranscriber` | `SpeechRecognizer` + `EXTRA_PREFER_OFFLINE` | 上下文偏置换成 `RecognizerIntent` 的 biasing；**偏置对中文没效果的话这个功能就该砍掉**，让用户用输入法自带的语音输入 |
| `NSLocationDefaultAccuracyReduced` | 只声明 `ACCESS_COARSE_LOCATION` | 不要加 `ACCESS_FINE_LOCATION`。城市决定气候、季节、时差和就医方式，那是要位置的全部理由 |
| App Intents / Siri | App Shortcuts / Assistant deep link（`vana://action/ask`） | 「问 Vana」打开聊天自动发送 |
| `BackgroundDigest`（scenePhase） | WorkManager | 「后台的模型调用同时只准跑一件」那把锁照样要有，它补的是一个真失灵 |
| `MARKETING_VERSION` / plist | `versionName` / `versionCode` | — |

## 目录规划

`app/src/main/kotlin/com/pinapia/vana/` 下按 iOS 那边的分组一一对应：

```
agent/        AIKitEngine / ChatViewModel 那一层（Android 侧的 model client 实现）
ask/          ask_user 那张卡
chat/         聊天界面、消息列表、输入区
checkin/      早晚本地通知
exercises/    动作库与卡片
intents/      App Shortcuts deep link
legal/        告知屏、隐私说明、急症规则
location/     粗定位 + 反地理编码
medications/  用药与补剂
memory/       长期记忆
recall/       跨会话召回
search/       网页搜索
session/      会话存储与索引、SessionTitle
settings/     设置页、ModelCapabilityTags、DeveloperScreen
tenant/       家庭成员（数据目录隔离）
vision/       拍照 / 选文件 / OCR / RecognizedTextLayout
voice/        按住说话
ui/theme/     配色与字阶
```

## 约定

- Compose only，不写 View/XML 界面（`themes.xml` 只管启动窗口到第一帧那一下；shortcuts / Manifest 除外）。
- **辅助调用一律显式关掉思考**（首屏建议、追问 chip、抽记忆——凡是「让模型写几行短句」的）。
  留空不等于关：DeepSeek、Qwen、GLM、Gemini Flash 的默认是思考，而思考算进 output，
  一个 120 token 上限的请求会在写出正文之前用光预算。
- Android 不连接设备健康数据；API key 只进 `EncryptedSharedPreferences`。
- 字号跟着系统缩放走，不写死 sp。用户里有相当一部分是把系统字号调大了的人。
- 会话标题只走 `SessionTitle.make`（界面与索引共用），别各写一份。
