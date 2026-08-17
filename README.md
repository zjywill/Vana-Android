<h1 align="center">Vana</h1>

<p align="center">
  Android 上的健康聊天助手。<br>
  打开就是对话，没有仪表盘，没有账号，没有订阅。
</p>

<p align="center">
  <a href="LICENSE">MIT</a>
  ·
  iOS 版：<a href="https://github.com/zjywill/Vana-iOS">Vana-iOS</a>
</p>

---

你问「我最近睡得怎么样」，它自己去查、再把结论讲给你听——不是把一堆折线图摆在面前让你自己看。

拍一张纸质化验单，它在**本机**把字认出来再讨论；你说「我不能吃布洛芬」，它记下来，以后建议都绕开；早晚那条通知写的是当天真有意思的事，不是「该打卡了」。

**它自带钥匙。** 模型调用都发给**你自己**配置的云端 provider——设置里填一把 API key，没有中间服务器，没有注册，没有订阅。

> 仓库叫 `Vana-Android`，应用显示名是 **Vana**，包名 `com.pinapia.vana`。和 [Vana-iOS](https://github.com/zjywill/Vana-iOS) 是同一个产品的两个客户端。

## 大概是这样用的

> **你**:我这周是不是练得有点少
>
> **Vana** · *查了锻炼、活动量*
> 这周三次、合计 96 分钟，上周是五次 180 分钟。少的主要是周中——不是强度掉了，是次数掉了。

> **你**:*(拍了一张化验单)* 帮我看看
>
> **Vana**
> 血红蛋白 132 g/L、白细胞 5.8、血小板 210，都在参考区间里。有两项标了箭头，我们逐个说——另外识别可能出错，关键数值麻烦你和纸上核一下。

> **你**:冬天要不要补点维 D
>
> **Vana**
> ……记下了，你从今天开始试维 D 2000 IU。两周后我来问一句有没有变化。

## 它能做什么

**聊天式解释健康相关的事。** 用药、症状、化验单、你交代过的习惯——用工具查、用记忆绕开禁忌，回复上方能看到这一轮调用了什么。

**打开就有一句话。** 第一屏先说现在是什么状况（本地先算一版，模型润色好了再换）；没配 key 也有本地那一版。

**拍照读化验单。** 纸质化验单、药瓶、成分表，或相册 / 相机、Word（docx）。**文字识别在本机（ML Kit）**；是否把原图发给视觉模型由你在设置里定。发送前每张都能点开改。

**记住关于你的事。** 「睡够 7 小时才算好」「跑步伤膝盖」这类会一直成立的话进开场；**不记易腐的数字**。设置里每条都能看、改、删。

**用药与补剂表。** 在吃什么、试过什么、什么不能吃；最值钱的是「效果」和回访，不是打卡本。

**记得上次聊过什么。** 你提起「上次你说的那个方法」才会去翻旧会话；平时不会主动翻。

**早晚 check-in。** 时间自己定；通知正文是当天数据里真有点意思的那件事。

**按住说话。** 识别在本机，松手只填输入框，发不发你说了算。

**家庭成员。** 每人一套隔离的会话、记忆、用药表；健康数据归机主那一侧（见下「Health Connect」）。

**隐私对话。** 不存会话、不抽记忆、不落附件；仍可读记忆，但不写盘。

**其余。** 可选网页搜索（Serper key，不填就不出现）；粗到城市的定位；多种说话风格；它还在写的时候你可以插下一句，它在下一个工具轮接住。云端目录里几十家 provider，只列支持工具调用的；能力标签会标「看图 / 思考」等。

## Health Connect（暂缓）

大陆发行版手机普遍没有 Google Health Connect，也很难装上依赖 Play 的独立 HC。当前 **`Features.HEALTH_CONNECT = false`**：不挂健康工具、不弹授权 / 安装引导。`HealthStore` 等代码还在，以后有稳定本机数据源再开。

没有 HC 时，化验与趋势主要靠 OCR、文档导入、用药表和你聊过的内容——和切到「家人」成员时的形态接近。

细节见 [`CLAUDE.md`](./CLAUDE.md)。

## 和 iOS 版差在哪

| 能力 | iOS | Android |
| --- | --- | --- |
| 本机健康数据 | HealthKit | **暂缓** Health Connect |
| 化验 FHIR | HealthKit clinical records | 未接；靠 OCR / 文档 |
| 文档扫描纠偏 | VisionKit | 未接；相册 / 相机 + OCR |
| 语音助手入口 | Siri App Intents（可后台念） | App Shortcuts / Assistant deep link（打开 app 展示） |

记忆规则、隐私会话定义、Tenant 隔离、插话与压缩等行为两边一致。

## 隐私

- **健康数据只读**（接回 HC 后也不会写回）。
- **API key 进加密存储**，不进普通 SharedPreferences、不进日志。
- **照片默认本机 OCR**；是否上传原图由照片策略 / 视觉模型能力决定。
- **没有自家后端**。除了你配的模型（和可选搜索），不连中间服务器，没有埋点 SDK。
- **位置只到城市**，坐标不进 prompt。
- 会话、记忆、用药等在应用私有目录。
- 但要说清楚：**问题和查到的聚合内容会发给你选的那家云端模型**。这是这类 app 的地基。

## 上手

需要 JDK 21、Android SDK（路径写在 `local.properties`，不进仓库）。

```bash
git clone https://github.com/zjywill/Vana-Android
cd Vana-Android
./gradlew :app:installDebug
```

签名发版（密钥不进仓库）：

```bash
./scripts/build-apk.sh   # GitHub / 侧载
./scripts/build-aab.sh   # Play Console
```

装好后：设置里选 provider 和模型，填那家的 API key，回到聊天即可。默认目录来自 `app/src/main/assets/catalog/providers/`。

## 开发

```bash
./gradlew :app:assembleDebug
./gradlew :agent-runtime:test      # agent core，秒级，不需要设备
./gradlew :app:testDebugUnitTest
./gradlew :app:installDebug
```

| 模块 | 是什么 | iOS 对应 |
| --- | --- | --- |
| `:agent-runtime` | 纯 Kotlin/JVM。工具循环、上下文预算、压缩。**不认识 Android，也不认识任何模型 SDK** | `AgentRuntime` |
| `:app` | Compose UI + 各项能力 +（预留的）Health Connect | app target |

`compileSdk` / `targetSdk` 36，`minSdk` 28。版本目录见 `gradle/libs.versions.toml`。

设计边界以本仓库 [`CLAUDE.md`](./CLAUDE.md) 和 iOS 的 `CLAUDE.md` 为准——后者是「为什么是这样」的主文档。

## 故意不做的

- **不做用药提醒打卡**——那是系统健康 / 用药 app 的事，第二套对不上，漏一次代价是真的。
- **不做本地相互作用数据库**——不给兜不住的结论背书。
- **不做多设备同步和家庭共享账号**——要后端，而且是另一量级的承诺。
- **不做端上模型**（体验没到可用标准）。

## 免责

Vana 不是医疗器械，说的话不构成诊断或治疗建议，尤其不给任何剂量建议。身体上的事该问医生还是要问医生。急症请直接就医。

## 许可

[MIT](LICENSE)。拿去用、改、发布都行，带上版权声明就好。
