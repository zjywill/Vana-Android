# Vana-Android

Vana 的 Android 客户端：Compose UI，agent 通过工具调用读健康数据、对话式分析。

**iOS 版的 `CLAUDE.md` 是设计决策的主文档**（`../Vana-iOS/CLAUDE.md`）。那份东西里写的每一条
「不要破坏的边界」都是踩过之后写下来的，绝大多数和平台无关——记忆里不存易腐的数字、压缩要在
发请求之前做而不是撞墙之后、插话在轮边界接入、召回精度重于召回广度、隐私会话按写入路径定义
而不按名字。**动到对应的东西之前先去读那一份**，这里只记 Android 这一侧真正不一样的地方。

用户看到的名字是 **Vana**。工程名 `Vana-Android`，包名 `com.pinapia.vana`（pinapia.com 是
自己的域名）。

**当前状态：只有骨架。** 没有功能代码，跑起来是一屏占位文字。

## 构建与测试

```bash
./gradlew :app:assembleDebug
./gradlew :agent-runtime:test        # agent core，秒级，不需要模拟器
./gradlew :app:testDebugUnitTest
```

## 模块边界：`:agent-runtime` 是纯 Kotlin/JVM，不是 Android library

这是**故意的**，不是省事。iOS 那边 `AgentRuntime` 是个本地 SwiftPM 包，不 import 任何模型 SDK
也不认识 HealthKit；那条边界在这里由编译器守着——拿不到 `android.*`，就写不出「顺手在这里查一下
Health Connect」这种代码。改成 `com.android.library` 的那一天，这条边界就只剩自觉了。

它只该认两个抽象：「一个能估 token、能流式跑一轮的模型」和「一组 JSON Schema 加一个执行闭包」。
工具循环、四档上下文降级、预算记账都在这一层，测试是秒级的。

## `minSdk 34`

对应 iOS 那边「iOS 26 only，不写旧版本可用性分支」。

Health Connect 从 Android 14（API 34）起是系统的一部分。34 以下它是一个要单独安装的 APK，
于是多出一整条分支：判断装没装、版本够不够、引导去 Play 商店、装完回来再重试。砍掉那条分支
换来的是——**「读不到健康数据」在这个 app 里永远只有两个原因**：用户没授权，或者没有 app 往
Health Connect 里写过数据。真要往下放 minSdk 之前，先想清楚那条安装引导路径由谁来写、
空态那句话要怎么分辨这三种情况。

## 健康数据：Health Connect 不是 HealthKit 的平替，有两处真差别

`androidx.health.connect:connect-client` 是对等物：睡眠、步数、静息心率、HRV、锻炼、体重都有，
按项授权，**数据归本机这个用户所有**——所以 iOS 那条胜负手（「Apple Health 归机主那个成员所有」，
切到家人时一个健康工具都不挂）在 Android 上逐字成立，`Tenant` 那套整个照搬。

两处必须在设计上认下来的差别：

- **数据可能整个是空的，而且这是常态不是异常。** iPhone 自己就在记步，有 Watch 就有睡眠和 HRV；
  Health Connect 只是个中转站，用户没装三星健康 / Fitbit / 小米运动 / Google Fit 这类 app 就
  什么都没有。所以 iOS 那边 `HealthVitals` 里「这台设备上什么都没读到（没授权、刚装上、没戴表）」
  那句 `calmSummary`，在 Android 上会被读到的频率高一个数量级，**空态是主路径，要写得比 iOS 那边
  更认真**：得说清「去装一个能往 Health Connect 写数据的 app」，而不是只说「没有数据」。
- **`READ_HEALTH_DATA_HISTORY` 漏了会静默地只剩 30 天。** 不带这条权限，Health Connect 只返回
  最近 30 天，更早的一条都读不到，**而且不报错**。体重趋势、几个月前的锻炼、跨季度对比全靠它。
  已经在 Manifest 里声明了，不要顺手删掉。

权限声明的原则同 iOS 那边的用途字符串：**每一条都是用户在授权面板上会逐条读到的一行**，
所以只列真的会读的项目。多声明一项的代价不是多一个字段，是他在决定要不要交出健康数据的那一屏上
多读到一句用不着的话。全部是 `READ`，这个 app 一次都不写。

## 其它几处平台替换

| iOS | Android | 注意 |
| --- | --- | --- |
| HealthKit | Health Connect | 见上 |
| Keychain（API key） | `EncryptedSharedPreferences`（androidx.security-crypto） | Android 没有 Keychain；密钥由 Android Keystore 兜住，是最接近的一档 |
| `TenantPaths.excludeFromBackup` | `allowBackup="false"` + `data_extraction_rules.xml` | 代价是换新手机数据不跟着走，**隐私说明里要照实写**，别写反了 |
| Vision 文字识别 | ML Kit `text-recognition-chinese` | 同样全在本机跑，照片默认不出这台设备 |
| `SpeechAnalyzer`/`SpeechTranscriber` | `SpeechRecognizer` + `EXTRA_PREFER_OFFLINE` | 上下文偏置换成 `RecognizerIntent` 的 biasing；**偏置对中文没效果的话这个功能就该砍掉**，让用户用输入法自带的语音输入 |
| `NSLocationDefaultAccuracyReduced` | 只声明 `ACCESS_COARSE_LOCATION` | 不要加 `ACCESS_FINE_LOCATION`。城市决定气候、季节、时差和就医方式，那是要位置的全部理由 |
| App Intents / Siri | App Actions / Assistant shortcuts | iOS 那三条本地念的 intent 不联网、不看 API key，这条约束照搬 |
| `BackgroundDigest`（scenePhase） | WorkManager | 「后台的模型调用同时只准跑一件」那把锁照样要有，它补的是一个真失灵 |
| `MARKETING_VERSION` / plist | `versionName` / `versionCode` | — |

## 目录规划（还没建，落第一个功能时按这个来）

`app/src/main/kotlin/com/pinapia/vana/` 下按 iOS 那边的分组一一对应：

```
agent/        AIKitEngine / ChatViewModel 那一层（Android 侧的 model client 实现）
ask/          ask_user 那张卡
chat/         聊天界面、消息列表、输入区
health/       Health Connect 读取、HealthSituation、HealthVitals、工具注册
legal/        告知屏、隐私说明、急症规则
location/     粗定位 + 反地理编码
medications/  用药与补剂
memory/       长期记忆
recall/       跨会话召回
search/       网页搜索
session/      会话存储与索引
settings/     设置页
tenant/       家庭成员（数据目录隔离）
vision/       拍照 / 选文件 / OCR
voice/        按住说话
ui/theme/     配色与字阶
```

## 约定

- Compose only，不写 View/XML 界面（`themes.xml` 只管启动窗口到第一帧那一下）。
- **辅助调用一律显式关掉思考**（首屏建议、追问 chip、抽记忆——凡是「让模型写几行短句」的）。
  留空不等于关：DeepSeek、Qwen、GLM、Gemini Flash 的默认是思考，而思考算进 output，
  一个 120 token 上限的请求会在写出正文之前用光预算。
- 健康数据只读；API key 只进 `EncryptedSharedPreferences`。
- 工具只返回按天（或按晚）聚合值，不返回原始样本。逐小时序列只画在结果面板里，不进模型上下文。
- 字号跟着系统缩放走，不写死 sp。用户里有相当一部分是把系统字号调大了的人。
