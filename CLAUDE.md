# Vana-Android

Vana 的 Android 客户端：**日常助手**——一条永远的对话（一件事要单独聊就自己开一条侧聊），agent 通过工具使用
记忆、提醒与目标、网页、笔记、OCR 等能力；**健康只是其中一个可以整个关掉的插件**（用药、测量、动作库、化验单解读）。

**iOS 版的 `CLAUDE.md` 是设计决策的主文档**（`../Vana-iOS/CLAUDE.md`）。那份东西里写的每一条
「不要破坏的边界」都是踩过之后写下来的，绝大多数和平台无关——记忆里不存易腐的数字、压缩要在
发请求之前做而不是撞墙之后、插话在轮边界接入、召回精度重于召回广度、隐私会话按写入路径定义
而不按名字。**动到对应的东西之前先去读那一份**，这里只记 Android 这一侧真正不一样的地方。

用户看到的名字是 **Vana**。工程名 `Vana-Android`，包名 `com.pinapia.vana`（pinapia.com 是
自己的域名）。

**当前状态：日常助手 + 健康插件（有意保留平台差异）。** 一条永远的对话加侧聊、通用记忆、提醒/目标/今天、
读网页、笔记与清单、OCR、check-in 和「问 Vana」App Shortcut 已落地。后台子 agent 2026-09-30 撤掉了（见「撤掉的」）。设备健康数据不属于 Android 产品能力。

**从「健康聊天」转向「日常 agent」的重构已做完（P0–P8）**，整体方案、决策记录、与原方案的偏差见
`docs/architecture/daily-agent-plan.md`，给 iOS 的对应清单见 `docs/architecture/ios-parity.md`。
动到对应的东西先看那份方案，别照着旧的「会话」「抽屉」「会话列表」「健康是核心」的思路加新的耦合：
**核心不认识任何领域**，领域知识只能是插件贡献的。

## Android 不接入设备健康数据

目标 Android 手机没有可靠、统一、可连接的标准健康数据协议。这个限制决定了产品边界：

- 不依赖任何设备健康数据 SDK，不声明相关权限。
- 不提供安装、授权、同步、健康摘要、设备数据话题或快捷入口。
- 不在提示词里暗示能够读取手机、手表或第三方健康平台。
- 趋势分析只基于用户主动提供的 OCR / 文档、用药表、记忆和手工测量卡片。

## 构建与测试

```bash
./gradlew :app:assembleDebug
./gradlew :agent-runtime:test           # agent core，秒级，不需要模拟器
./gradlew :app:testPlayDebugUnitTest    # 有 play / github 两个 flavor，没有 testDebugUnitTest
```

`local.properties` 不进仓库，所以新机器上第一次跑要给 SDK 路径，否则报
`SDK location not found`：

```bash
ANDROID_HOME=~/Library/Android/sdk ./gradlew ...
```

## 模块边界：`:agent-runtime` 是纯 Kotlin/JVM，不是 Android library

这是**故意的**，不是省事。iOS 那边 `AgentRuntime` 是个本地 SwiftPM 包，不 import 任何模型 SDK
也不认识 HealthKit；那条边界在这里由编译器守着——拿不到 `android.*`，就不会把平台能力塞进
工具循环。改成 `com.android.library` 的那一天，这条边界就只剩自觉了。

它只该认两个抽象：「一个能估 token、能流式跑一轮的模型」和「一组 JSON Schema 加一个执行闭包」。
工具循环、四档上下文降级、预算记账都在这一层，测试是秒级的。

## 一条永远的对话（`thread/`）

没有用户可见的「会话」了：打开就接着上次，一条时间线。**旧的「一个会话一个文件」存储在新存储上线时整个
清掉、不迁移**（`LegacySessions`，用户确认过）；记忆、用药、测量没换存储，不动。

- **盘上**：`<tenant>/thread/seg-NNNNNN.jsonl` 追加写（一段 ≤400 条记录），加小小的 `meta.json`。记录是
  put（一条消息的最新样子，带位置 `p`）或 del；**同一条消息可以 put 很多次，最后一次为准**，所以重试、
  补答案都还是追加。顺序由位置 `p`（浮点数）定，不由写入先后定——助手还在回答时用户补了一句，那条回复要
  排在补的话**前面**。读是**从新往旧**：先加载最新几段，滑到顶再往前翻，读旧段时按「已见过的 id」跳过被盖过或已删的。
- **`ThreadStore.sync(messages, dirty, known)` 只写变了的**，并且**只删界面自己同步过（`known`）、这次列表里没了的**——
  后台追加、界面还没读到的主动消息不在 `known` 里，不会被误删。所有写入走单写者 `ThreadWriter`，后台追加之后拨
  `revision`，界面在**一轮回复结束之后**才把新消息并进列表（沿用「插话在轮边界接入」）。
- **窗口**（`WindowPolicy` 在 `:agent-runtime`，`ThreadWindow` 接到消息上）：请求里只带窗口内的原文。**批量淘汰，
  不逐条丢**——涨到高水位（预算 = 上下文的三成半，夹在 12k–32k）才动，一次砍到 40%；逐条丢会让请求前缀每轮都变，
  prompt 缓存整个失效。只在**轮边界**切，最近几轮不动，system 段和工具定义占的位子先从预算里扣。游标存在
  `meta.json`，淘汰只前移游标，**一条消息都不删**。撞上模型上下文上限时强制留最近两轮再试一次，不再说「开新对话」。
  **估 token 只有一把尺子**（`TokenEstimate`，在 `:agent-runtime`）：窗口和上下文规划器共用，中文一字一 token、其余四字符一个，
  宁可高估。别在别处再写一份 `chars / 4`——规划器以前就是这么对中文低估到四分之一的。窗口算一条消息占多少时，**历史里助手的
  思考也要计入**（`ThreadWindow.estimate`）：OpenAI 兼容和 Gemini 协议会把它原样发回去，Anthropic 不发
  （`OpenAICompatibleModelClient.replaysReasoning`，改任何一条协议的回放口径都要同步它）。固定开销（system 段 + 工具定义）
  用 `CloudEngine.requestOverheadTokens()`，工具的参数说明按**发出去的 JSON** 算——`inputSchema.toString()` 是 Kotlin 调试输出，
  比真实大一截，用它会把窗口白白挤窄。当前全开时这份开销约 8k token（关掉健康约 4k），所以 32k 上下文的模型窗口基本就是保底的 6 轮。
- **聊天路径不主动摘要**（`CloudEngine` 里 `summarizer = null`）：历史 = 窗口 + 记忆 + 可检索的档案。递归摘要会漂，
  还多花一路钱。相邻两条隔 ≥6 小时，`HistoryMarkers` 在后一条用户消息前补一行确定性的时间标记；
  Vana 主动说的话（`ChatMessage.Origin` 非 NORMAL）不作为独立助手消息发出去，折进**下一条用户消息**开头——
  请求里助手/用户严格交替，有的协议不接受连着两条助手消息。
- **档案**（`ThreadArchive`）：进程内存里的索引，启动时后台扫一遍，之后靠 `ThreadStore.ChangeListener` 增量更新，
  不落盘。召回（`search_sessions` / `read_session`，工具名沿用旧的）**只在真的有原文滑出窗口时才挂**，只搜窗口之外的、
  只搜用户说过的话（精度重于广度）；短编号是消息 id 的散列，不是「第几条」。
- **收割和窗口解耦**（`MemoryHarvester`）：每条线程 meta 里一个**水位线**（主对话那一路连全部侧聊一起收，记忆只有一份），只喂水位线之后的消息，从最旧的开始按转写
  字符数分块，抽完才推水位线；切到后台、空闲半小时、有原文滑出窗口时触发，走 `BackgroundModelWork` 那把锁，
  过 provider 同意的闸。失败即放弃、水位线不动。
- **「不留痕」是浮层**（`ChatViewModel(ephemeral = true)`）：内存里聊，不读盘不写盘、不抽记忆，离开那一页就没了。
  隐私会话按写入路径定义的机制没变，只是换了载体。

## 侧聊（`SideChatStore` / `ChatViewModel(sideChat = …)` / `SideChatListScreen`）

设计和边界在方案 §16 和 iOS `CLAUDE.md` 的「侧聊」一节（**只有用户手动开**；主对话永远是家；窗口各管各的，
记忆和档案共享）。Android 的落地差异记在 `daily-agent-plan.md` §16.10。改的时候容易踩的几条：

- **盘上**：`<tenant>/sides/index.json`（名单）加每条一个 `<uuid>/`，和 `thread/` **同一格式**。`sides` 在
  `TenantPaths.perTenantItems` 里——那份清单就是「隔离」的定义。
- **一个 `sides/` 目录一个 `SideChatStore`（`instance`），一条侧聊一个 `ThreadWriter`（`writer(id)`）**：两个实例就是两个写者。
  侧聊就是 `ChatViewModel(sideChat = …)` 接 `writer(id)`，插话、窗口、重试、同意闸全部原样；主对话专属的
  「今天」、欢迎卡、首屏建议、check-in、快捷方式按 `isMainThread` 关。
- **view model 没给 `sides` 时从主对话线程的目录推**（`SideChatStore.beside`），不指着 `TenantScope`：
  「清空全部对话」连侧聊一起清，默认值指着真的那份的话，一条测试就能把真的侧聊全删了。
- **照片仓库是成员名下所有线程共用的**：`ThreadStore.deleteAll()` 只删这条线程自己引用的照片；
  要一张不留走 `ConversationHistory.clearAll()`（「对话历史」的三样都经它，范围是主对话加全部侧聊）。
- **删的顺序是先落名单、再清线程（连照片）、最后删目录**；孤儿目录只在名单读懂、且没有 `index.json.bak` 时清。
- **侧聊的 view model 归 `SideChatHost`**（主对话那一项返回栈上的 view model），不归侧聊那一页：离开时还在写的留着写完、
  写完亮未读点，回来接上的是**同一个对象**（两个对象写一条线，位置和已删的记账会对不上）。每个侧聊 view model 一格自己的
  `ViewModelStore`（`HostedSideChat`），放掉就清那一格。「离开那一页」= 侧聊那一项被弹出返回栈（`SideChatVisit`），
  推出设置页不算。放掉时的落盘和收割在 `SideChatStore.launch` 里做——`onCleared` 时 `viewModelScope` 已经取消了。
- **互通靠记忆和召回，不靠往窗口里塞别处的原文**：召回每轮现算够得着哪些线（`SideChatRecall.gather`），别的线整条都算
  看不见，搜出来的每一处标上在哪条线上；只有这条对话自己时召回的说明**逐字不变**。主对话易变区最后那块侧聊名单末尾的
  「他没提起时不要主动说起它们」是产出不是免责：不写的话模型每答一句都先扯一句侧聊。
- **两条线之间搬的是文字，不是 transcript**（`SideChatQuote`）：`tool_call` 配对、思考、原图断一样就是 400；照片不带
  （照片归原来那条线）。给模型的来历说明由 `HistoryMarkers` 折进下一条用户消息开头。
- **侧聊说明块**（`CoreInstructions.sideChat`，`PromptOrder.SIDE_CHAT = 25`）在静态区，名字在第一次请求**之前**定下来，
  不带领域词（`PromptAssemblyTest` 盯着）。

## 插件（`AgentPlugin`，`:agent-runtime`）

方向是「通用手机 agent + 健康插件」，两端共用同一套形状。第一步（搬家）和第二步（拆提示词）都做完了：
核心 system 段（`CoreInstructions`）不认识任何领域，只留「危及人身安全先说急救」「自伤先停下分析」两条
不分话题的底线；用药、化验单、急症症状清单、动作库用法、对核心工具的健康补充都是健康插件
（`HealthPlugin.kt` / `HealthInstructions`）贡献的，健康关掉之后整段 system 加全部工具定义里**不含健康词**。
`PromptAssemblyTest` 盯着这一条（连同检测器自己不是空转），改核心工具的描述时别把领域词写回去——
某个领域在这个工具上要多小心什么，用那个插件的「门控块」补。

- **工具自己声明副作用**（`ToolEffect`），隐私会话和后台派生由 `PluginContext` 统一过滤：
  隐私会话丢掉全部 `WRITE_LOCAL`，后台派生再丢掉 `NEEDS_USER`。别再加 `allowsXWrites` 这类参数——
  「隐私会话按写入路径定义」从此是机制，加一个会写盘的工具只要声明对。
- **常驻段和按需挂载是两回事**。用药名单、急症规则只能是常驻段（`PromptBlock`），不能做成要
  模型自己想起来去查的东西。
- 开关在 `PluginEnvironment` 里兑现成「给不给 store」，关掉就不挂，不是挂了返回空。`PluginRegistry`
  取代了原来 `VanaPlugins.foreground(...)` 那串位置参数；`VanaPlugin` = 名片 + 它在某条路上贡献的
  `AgentPlugin`，`:agent-runtime` 只认识后者。
- **system 段按「会不会变」分区，不按「谁的」分区**（`PromptOrder`）：静态的在前，今天/位置/记忆/用药/
  测量/目标这些易变的一律排最后。prompt 缓存认前缀，一变只该打掉尾巴。精确时间不进 system 段。
- **记忆抽取器的规则由插件拼**：插件声明 `memoryExclusions`（「我自己存着这个」）和 `memoryGuidance`，
  `PluginHost` 汇总，抽取器和 `remember` 的描述读同一份。只在插件**真的存着**的时候才声明——用药表关了，
  「我不能吃布洛芬」就该进记忆。
- **记忆种类的归属**：`interpretation`（已有解释）归健康插件（`VanaPlugin.memoryKinds`）。插件关掉，
  它拥有的种类不再进对话、抽取器也看不到；数据留在盘上。核心的种类（profile / preference / episode /
  followUp）不属于任何插件。新增种类先想清楚归谁。
- **给模型看的记忆块只用固定中文标签**（`Kind.promptLabel`），不用 `Kind.label`——后者跟界面语言走，
  用在提示词里会让同一份记忆渲染出不同长度的前缀。记忆块不要再用三引号模板加 `trimIndent()` 拼多行内容：
  插值进去的多行文字从第二行起没有缩进，`trimIndent` 什么都削不掉，前导空格就一起发给了模型。
- Android 不和其他 app 做连接，以后的插件只在 Vana 内部自足。
- 现有插件：核心（记忆、召回、反问、搜索、读网页、位置、提醒/目标）、`NotesVanaPlugin`（笔记与清单，可关）、
  `HealthVanaPlugin`（可关，用药与测量另有子开关）。加新插件：`VanaPlugin`（名片 + 在某条路上贡献的
  `AgentPlugin`）注册进 `PluginRegistry.all`；开关兑现成「给不给 store」；提示词块排进 `PromptOrder` 对应的区间；
  可选的还有 `todayCards`（「今天」页上的行）、`welcomeBlurb`、`suggestions`、`toolLabel`、`memoryExclusions`。

## 提醒、目标、「今天」（`tasks/`、`today/`）

- **一个 `Task`，两种 kind**（`TaskStore`，`tasks.json`，读法和记忆一样逐条容错）：`REMINDER`（到点发通知）、
  `GOAL`（用户长期在做的事，有步骤和进展记录）。取代了原来的「目标线」会话。以前的第三种 `JOB`（后台任务）撤掉之后，
  盘上那几条认不出 kind，原样留着、不再显示。
- **目标详情里「在侧聊里聊这个目标」**取代原来的「每周回顾」：开一条以目标命名的侧聊（`SideChatStore.named`，同名的有了
  就接着用），输入框里替他起个头（`SideChatHost.stageDraft`），他看一眼再发；「今天」页退到底下，返回回到主对话。
- **提醒到点不调模型**（`ReminderScheduler`）：`AlarmManager.setAndAllowWhileIdle`（**非精确**，不申请
  `SCHEDULE_EXACT_ALARM`——Play 对精确闹钟有类别限制，界面和工具回答都照实说「可能晚几分钟」）；响的时候发本地通知、
  往线程末尾追加一条 `Origin.REMINDER` 主动消息。重复提醒按**挂钟时间**推下一次（不是加 24 小时，夏令时不漂）。
  重启和每次打开 app 时 `rescheduleAll`；过点很久没响的补响一次并标明「错过的」。别让提醒去调模型。
- **精确时间不进 system 段**（每分钟都变，会打掉缓存）：要知道几点就调 `get_current_time`。进行中的目标
  （≤5 个）常驻 system 段，排在易变的那一片。
- **「今天」是本机数据拼出来的，一次模型调用都不发**（`TodayCompute`/`TodayFeed`）：核心贡献今天到点/过点的提醒、
  到期的待跟进；健康贡献到期的用药回访（`VanaPlugin.todayCards`，关掉的插件不被问）。
  天天打开天天付钱是不该的——别把它改成让模型写一段早间简报。
- **「今天」是单独一页，不在对话那一列里**（`TodayScreen`，主对话顶栏那颗带角标的 ☀，导航目的地）。它和原来的
  任务页合成了一页：「今天要做」（插件贡献的那几行，带类别小字）、「之后」（还没轮到今天的提醒）、「目标」、「最近完成」；
  一行一个样子（按类别上色的圆 + 标题 + 一行进展），提醒那几行右边是「完成」。iOS 最上面还有一节「现在」（健康状况那一行），
  Android 不读设备健康数据，没有这一节。为什么拿出来见 iOS `CLAUDE.md` 同名一节和方案 §7.4，这里记落地要守的几条：
  - **别再把它放回对话里**。它说的是现在，对话那一列是发生过的事，线性的时间线上没有它的位置；为它写的贴底特例
    （`todayAfterId`、贴底下标多挪一格）也一起删了。入口不上底部导航栏：只有两个地方时撑不起一条，还会一直压在输入框
    底下。代价是打开 app 不再自动看见它，急的（今天到点、已过点的提醒）靠角标（`TodayCompute.attention`）兜住。
  - **「之后」按卡片去重，不按时间再算一遍**（`TodayCard.taskId` → `TodayCompute.later`）：两处各算一次「今天结束在
    哪一刻」，过零点那一下就会一条出现两遍或者一条都不见。
  - **目标不出卡**（「目标」一节整张列出来，再出一行就是同一件事摆两遍）；**条数不设上限**（今天到点的提醒少列一条，
    就是那一页在说谎；回访那几类各插件自己限着）。
  - 数据是主对话 view model 手里那份 `TodayFeed`（角标数的是同一份），那一页从返回栈上主对话那一项取它。任务详情、
    用药、记忆页在那一页上面推；替他问一句（`TodayAction.Ask`）当场交给主对话发、再退回对话。打开那一页、回到前台
    各重拼一次（`refreshToday`），不等每分钟那一拍。页脚照实说提醒可能晚几分钟（非精确闹钟），这句 iOS 没有。
- 用户手动做的事和模型工具走同一批上限（`TaskActions` 对 `TasksTools`）：两条路进来的东西在盘上长得一样。

## 设置归哪儿（`SettingsScreen` / `PluginsScreen` / `PluginDetailScreen`）

判据和 iOS 一样（见 iOS `CLAUDE.md`「设置归哪儿」）：**关掉这个插件，这件设置还有没有意义**。没有意义的在插件
详情页里（用药、测量、家人档案），还有意义的留在设置。插件页每个插件一行（名字、一句话、开没开），点进去是
开关 → surfaces → 免责声明。

和 iOS 不一样的一处：**Android 的每日 check-in 留在设置**。它的正文是待跟进和当天的提醒，不读设备健康数据，
健康关掉照样有话说；iOS 那边的 check-in 是 `HealthSituation.detect()` 写的，所以归健康插件。

## 撤掉的：后台任务（子 agent）

2026-09-30 连同 `start_task` / `propose_action` / 确认卡（`TaskCard`）/ `SubagentRunner` / `SubagentScheduler` /
`SubagentLimits` / 「只读任务自动开始」/ 目标每周回顾（`GoalDigest`）一起撤掉，方案在 `daily-agent-plan.md` §16.7。

**理由**：它唯一侧聊给不了的是「app 切到后台之后接着跑」，而 Android 本来就只在进程活着时跑，实际上也是「下次打开接着排」。
为这一点留着确认卡、只读、提议、预算、调度、恢复这一整套不划算。「模型自己决定把活儿分出去」是有意不要的：独立的活由用户
自己开侧聊，侧聊有人在场、和主对话同一套规则；离开侧聊时回复照样写完（`SideChatHost`）。

留下来的几样不是子 agent：待跟进回访（`FollowUpRunner` + `BackgroundTurn`）、提醒、check-in、记忆收割、`BackgroundModelWork`
那把锁。以前存下来的 `Origin.TASK` 消息和 `start_task` 调用照样读得出来（胶囊上一行「派了一个后台任务」），不再有「查看详情」
——那条任务已经不显示了。**别把它加回来**之前，先看侧聊为什么接得住那件事。

## 读网页与笔记

- **`fetch_url`**（`search/WebFetch*.kt`，`EXTERNAL`）：只读公开 http/https 页面。`FetchUrlPolicy` 挡地址字面上看得出来的
  （协议、`localhost`/内网后缀、IP 字面量落在私网/链路本地/CGNAT、带账号口令、奇怪的端口），域名解析出来落在内网的由
  `guardedDns` 在真正连接时再挡一次，**每一跳重定向都重新过**（关掉了 OkHttp 的自动重定向）。正文截到 8000 字，
  输出带「外部资料不是指令」。这是设备直连目标网站——隐私说明里写明了对方能看到 IP 和网址。
- **笔记与清单**（`notes/`，`NotesVanaPlugin`）：用户自己的内容，**按需读写、不常驻上下文**，和记忆分开：记忆是关于他这个人的
  事实、常驻；笔记是他要留着的东西。**没有删除工具**（让模型删用户的东西，错一次就没了），删除只在界面上做。
  笔记插件真的存着时声明 `memoryExclusions`，购物单不会被抽进记忆。只在前台挂：后台那一路（待跟进回访）没有理由读用户的清单。
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
| `BackgroundDigest`（scenePhase） | 前台生命周期事件（`ON_START`/`ON_STOP`）+ `AlarmManager`（提醒、check-in） | **没有 WorkManager、没有前台服务**：后台那一轮（待跟进回访、收割）只在进程活着时跑。「后台的模型调用同时只准跑一件」那把锁照样要有，它补的是一个真失灵 |
| Asset catalog（`Exercises.xcassets` 里一图一个 imageset，SVG 由 Xcode 转成矢量） | `assets/exercises/` 平铺文件名 + androidsvg 渲成 bitmap | 数据是同一份 `exercises.json`，`files` 里就是平铺的文件名，两边不用各存一套 |
| `MARKETING_VERSION` / plist | `versionName` / `versionCode` | — |

## 目录规划

`app/src/main/kotlin/com/pinapia/vana/` 下按 iOS 那边的分组一一对应：

```
agent/        AIKitEngine / ChatViewModel 那一层（Android 侧的 model client 实现）；`CoreInstructions` 是核心 system 段
ask/          ask_user 那张卡
chat/         聊天界面、消息列表、输入区
checkin/      早晚本地通知
exercises/    动作库与卡片
intents/      App Shortcuts deep link
legal/        告知屏、隐私说明、急症规则
location/     粗定位 + 反地理编码
medications/  用药与补剂
plugins/      插件装配与插件页（`PluginRegistry`：哪条路挂哪些插件、首屏建议、欢迎语、工具标签；`PromptOrder`：system 段每块排在哪；`HealthTopics`：哪条回答要补医疗免责）
memory/       长期记忆
recall/       召回（读线程档案，`SideChatRecall` 算跨线程够得着哪些）与后台一轮（`BackgroundTurn`：待跟进回访）、`BackgroundModelWork` 那把锁
search/       网页搜索、读网页（`FetchUrlPolicy`、`HtmlText`）
tasks/        提醒/目标：`TaskStore`、`ReminderScheduler`、`TasksTools`、任务详情、添加提醒/目标的对话框（`TaskEditors`）
today/        「今天」：`TodayCompute`（纯函数）、`TodayFeed`、那一页 `TodayScreen`（合并了原来的任务页）
notes/        笔记与清单
session/      消息模型（`ChatMessage`；`ChatSession` 只是内存里那条线程末尾的一段视图）
thread/       一条永远的对话：`ThreadStore` / `ThreadWriter` / `ThreadArchive` / `ThreadWindow`；侧聊 `SideChatStore`；`ConversationHistory`
settings/     设置页、ModelCapabilityTags、DeveloperScreen
tenant/       家庭成员（数据目录隔离）
vision/       拍照 / 选文件 / OCR / RecognizedTextLayout
voice/        按住说话
ui/theme/     配色与字阶
```

## 动作库这一侧的三处不同(`exercises/`)

设计决策整个在 iOS 那份 `CLAUDE.md` 的「架构:动作库」里,数据也是同一份
`exercises.json`(295 个动作、879 张图,从 iOS 仓库同步过来)。这边只记真正不一样的三处:

- **图是平铺文件名,不是 asset catalog。** `files` 里存的就是 `wg-plank-1.svg` 这种名字,
  iOS 那边拿它去找同名 imageset,这边直接是 `assets/exercises/` 下的文件。所以**同步图的时候
  要把 iOS 的 imageset 目录摊平**,别把目录结构一起搬过来。
- **SVG 要自己渲成 bitmap 并且必须缓存**(`ExerciseSvg`)。iOS 那边 SVG 进 asset catalog 之后
  是系统在管;这边每次都要 androidsvg 解一遍。而**帧是循环的**——一张图三帧、一屏三张卡,
  按乒乓的节奏就是每秒解一次,不缓存等于每一圈都重解。缓存的键是文件名加尺寸(内容打在包里
  不会变),上限按「一屏撑死几张卡 × 每张几帧」定:不设上限的话,用户往回翻聊天记录会攒下
  几百张 256×256 的 bitmap。
- **`ExerciseLibrary.parse(raw)` 是为测试留的口子。** 这边的单元测试没有 Robolectric,
  拿不到 `Context` 也就读不到 assets;而这个库最要紧的那几条(关节排除、器械过滤、不给剂量)
  盯的正是数据本身,不是读文件那一步。测试直接读 `src/main/assets/exercises.json`,
  断言的口径要和 iOS 的 `VanaTests/ExerciseTests` 一致——两边对同一份数据说出两种结论,
  改的人只会更糊涂。

包体积:879 张 SVG 未压缩 22.8MB,进 APK 压到 10.2MB。「不联网、不按需下载」那条和 iOS 一样
成立,这个代价是认了的。

## provider catalog:从 AIKit 同步,来源是 models.dev

`assets/catalog/providers` 不是手写的,由 `python3 scripts/sync-catalog.py` 从
aikitswift(上游 2026-08-29 起改由 models.dev 生成)同步:只留 Android 实现了协议
(openai / anthropic / gemini)且可连的托管 provider,并剥掉 `CloudCatalog` 不读的字段
(cost、description 那些)——两条都是机械规则,不是手工挑名单。**不要手工编辑这些 JSON**,
要改就改脚本重跑;来源 commit 记在 `assets/catalog/PROVENANCE.md`。
目录涨到 ~2.4MB/179 家之后,解析挪到了后台线程(`CloudCatalog.bootstrap` 起
`FutureTask`,getter 阻塞到解析完)——别把它改回 onCreate 同步解析,也别改成非阻塞快照。

## 发给第三方之前先点名征得同意(同 iOS,5.1.1(i)/5.1.2(i))

iOS 2026-08-29 被判的那条,两边同一套修法,细节见 iOS `CLAUDE.md` 合规那节:
告知屏是明确同意(「同意并继续」+ `consentFootnote`,清单点名默认的 DeepSeek);
第一次真的要发给某家 provider 之前,聊天里弹点名确认,按 provider 记在
`EngineSettings.hasProviderConsent`(`consentedProviderIds`),换家再问。这道闸挡住
**每一条会出设备的路**:聊天和侧聊 `send()`、后台派生(`BackgroundTurn`)、抽记忆(`MemoryHarvester`,主对话连侧聊一起)、用药说明(`MedicationBriefer.fill`),同意之前全不跑。`ConnectionTest` 只发一句 "hi"
不含个人数据,不拦。隐私说明(两份 HTML)里「发给谁 + 发送以同意为前提」要和这套行为
逐字对上。

## 约定

- Compose only，不写 View/XML 界面（`themes.xml` 只管启动窗口到第一帧那一下；shortcuts / Manifest 除外）。
- **辅助调用一律显式关掉思考**（首屏建议、追问 chip、抽记忆——凡是「让模型写几行短句」的）。
  留空不等于关：DeepSeek、Qwen、GLM、Gemini Flash 的默认是思考，而思考算进 output，
  一个 120 token 上限的请求会在写出正文之前用光预算。
- Android 不连接设备健康数据；API key 只进 `EncryptedSharedPreferences`。
- 字号跟着系统缩放走，不写死 sp。用户里有相当一部分是把系统字号调大了的人。
