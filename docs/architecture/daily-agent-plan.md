# Vana：从「健康聊天」到「日常 agent」整体方案

> 范围：仅 Android（iOS 之后照做）。参照物：Meta Muse（2026-09-08 发布的个人 agent）。
> 依据：4 个并行代码勘察（提示词/UI 耦合清单、记忆/工具/插件架构、会话与延续线、Muse/Letta/OpenClaw 调研）。
> 2026-09-30 追加 §16「侧聊与撤掉子 agent」：**这一节 iOS 先行**（S1 从 iOS 开始），Android 照这份跟。

## 进度

| 期 | 状态 |
|---|---|
| P0 安全网 | **已完成**（2026-09-29）：`MemoryStore` 逐条宽容解码 + 坏文件备份 + 加锁 + 原子写；记忆页逐条删除；删会话/清空全部时清照片；`SessionStore` 原子保存；记忆/召回/存储的第一批单元测试。`DerivedTurn` 的测试没补——它要一个可注入的模型客户端，留到 P6 把它改成 `SubagentRunner` 时一起做 |
| P1 内核去健康化 | **已完成**（2026-09-29）：`CoreInstructions` 与健康块拆开；`VanaPlugin`/`PluginEnvironment`/`PluginRegistry` 取代 `VanaPlugins`；`PromptOrder` 改成「静态在前、易变在后」的区间制；`memoryExclusions`/`memoryGuidance` 被抽取器和 `remember` 消费；通用工具描述清掉健康词；`healthChat` 命名与压缩提示词通用化；`Tenant.instructionBlock` 搬进健康插件；设置里加了健康总开关；旧的逐字等价测试换成 `PromptAssemblyTest`。**没做**：`ToolCallRecord.payload`（推迟到 P4，见 §5.1） |
| P2 界面与文案通用化 | **已完成**（2026-09-29）：顶栏去掉心形/烧瓶，换成「⋯」菜单（记忆 · 插件 · 设置）；新增**插件页**（健康总开关 + 用药/测量子开关 + 入口 + 免责声明），健康的入口和「家人档案」都收进去，抽屉里的家庭成员行去掉；欢迎语由开着的插件拼、去掉「普通对话」chip 和欢迎卡上的医疗免责；首屏建议由插件贡献、通用优先；工具调用的标签由插件提供；「记入用药与补剂」按钮随用药表开关；隐私说明中英、告知屏的数据清单与实际行为对齐（补上了测量卡片、加了「插件」一节）；README/CLAUDE.md 改口径。**与原方案不同的两处**见 §10。**没做**：设置页整体重排（只把健康开关和入口移走，其余分区留到 P4 重写「对话」一节时一起动）、商店类目/描述/截图 |
| P3 记忆 v2 | **已完成**（2026-09-29）：新增 `episode`（近况，默认 14 天、上限 60 天，到点即消失不留宽限期，最多 10 条，超了先淡掉最旧的、用户自己写的不动）；`interpretation` 归健康插件（`VanaPlugin.memoryKinds`），健康关掉后这类记忆不再进对话、抽取器也看不到，记忆页标「暂不使用」；对话里加 `forget_memory` / `revise_memory`（按记忆块里的 M1、M2… 编号，用户当面纠正过的抽取条目转为受保护）；记忆块带编号、按整行截、到期的待跟进优先留；抽取器提示词把 `interpretation` 从核心移进健康补充、加入 `episode`；抽取去重忽略空白和标点；记忆页加搜索（≥8 条时出现）和近况的「N 天后淡出」；`remember` 限一句话（120 字）。**与原方案不同的三处**见 §6。顺带修了两个老 bug：记忆块整段带 16 个前导空格发给模型；块里的种类标签跟着界面语言变（英文界面下渲染成 `[Communication preferences]`） |
| P4 一条对话 | **已完成**（2026-09-29）：分段追加式 `ThreadStore`（JSONL，put/del 最后一次为准，位置用浮点数以便把回复夹在插话之前）+ 单写者 `ThreadWriter`；首次启动清掉旧 `sessions/` 及其照片（不迁移，记忆/用药/测量不动）；`ChatViewModel` 改成一条线程（冷启动直接落在末尾、向上滑往前翻页、日期分隔、删除一问一答）；`WindowPolicy`（`:agent-runtime`）+ `ThreadWindow`：涨到高水位一次砍到低水位、只在轮边界切、固定开销先扣、撞上下文上限时强制留最近两轮重试；聊天路径不再主动摘要（`summarizer = null`）；相邻消息隔 ≥6 小时补确定性时间标记；记忆收割按**水位线**解耦（切后台 / 空闲半小时 / 有原文滑出窗口时触发，从最旧的开始按转写字符分块）；召回改读进程内存里的 `ThreadArchive`，只有真的有原文滑出窗口才挂；主动消息（`ChatMessage.Origin`）折进下一条用户消息发给模型；「不留痕」浮层（`ephemeral` VM）；设置 › 对话历史（占用空间、清 30 天前、清空全部）；`ToolCallRecord.exerciseIDs` → 通用 `metadata`；`DerivedTurn`/`GoalDigest` 删除，待跟进回访改成 `BackgroundTurn` + 往线程追加主动消息；旧会话相关类型（`SessionStore`/`SessionThread`/`SessionTitle`/抽屉）全部删除。**与原方案不同**见 §4。**没做**：快照重绑规则（见 §4.3，仍不需要）、隐私说明/告知屏里关于「会话列表」「左滑删除」的旧表述（留到 P8 合规一遍改，**发版前必须改**） |
| P5 任务、提醒、今天 | **已完成**（2026-09-29）：`Task`（`REMINDER`/`GOAL`/`JOB` 共用一组状态）+ `TaskStore`（`tasks.json`，逐条容错、原子写、`revision` 流）；`ReminderRules`（纯函数：解析本地时间、按**挂钟时间**推重复、过点补响）+ `ReminderScheduler`（`AlarmManager.setAndAllowWhileIdle`，**非精确、不申请精确闹钟权限**；到点只发通知并往线程追加 `Origin.REMINDER` 主动消息，**不调模型**；开机/每次打开时重排，过点很久的补响一次标「错过的」；迟到的旧闹钟不会让已推进的重复提醒提前响）；工具 `get_current_time`/`create_reminder`/`list_tasks`/`update_task`/`create_goal`/`update_goal`（写的声明 `WRITE_LOCAL`，隐私会话不挂；目标块常驻 system 段易变区）；`TodayCompute`/`TodayFeed`/`TodayStrip`（**零模型调用**，聊天顶上可折叠的一条，`VanaPlugin.todayCards` 让健康贡献用药回访）；任务页（提醒/目标/后台任务/最近完成 + 加提醒（快捷时间 + 日期时间选择 + 重复）/加目标 + 通知权限提示）与详情（目标步骤勾选、进展记录、每周回顾开关）；顶栏「任务」入口带角标；早晨 check-in 带上当天的提醒；核心建议 chip 亮出提醒。**没做**：`tasks/<id>/run.jsonl` 单独存步骤记录（步骤直接放在 `Task.steps` 里，量级够小）、`sourceMessageId` |
| P6 子 agent | **已完成**（2026-09-29）：`SubagentRunner`（纯 JVM、秒级测试：隔离上下文、只读、预算、步骤审计、结果解析）+ `SubagentScheduler`（一次只跑一件、排队、被杀后 `resume` 最多再跑一次、过 provider 同意的闸、结果作为 `Origin.TASK` 主动消息 + 通知）；`start_task`（写盘 + 要用户参与，后台路挂不上）放一张确认卡（`TaskCard`：开始 / 不做了 / 进行中 / 结果 / 再试一次），用户点了才跑；`propose_action`（只读，攒进 `ProposalCollector`）→ 结果里逐条「照做 / 算了」（`TaskActions.decide`：提醒走手动添加同一条路，记忆按用户自己写的算）；预算 `SubagentLimits`（12 轮 / 5 分钟 / 估算 8 万 token / 排队 ≤3 / 每天 ≤10 / brief ≤1500 字）；设置「只读任务自动开始」（默认关）；目标每周回顾（`GoalDigest`，用户在目标上开开关那一刻即是同意）；任务详情（说明、状态、结果、来源、提议、步骤、用量）。**与原方案不同**：① 待跟进回访（`FollowUpRunner`）没有并进 `SubagentRunner`——它有自己的结果路径，共用的是 `BackgroundTurn` 那层（后台路装配、引擎、事件累积）；② token 是按字符估的（事件流里没有 usage）；③ 不做 WorkManager（P6b）：进程活着才跑，被杀了下次打开接着排——按用量再定 |
| P7 通用工具 | **已完成**（2026-09-29）：`fetch_url`（`FetchUrlPolicy` 挡协议/内网后缀/IP 字面量/账号口令/奇怪端口，解析结果落在内网的由 `guardedDns` 在连接时再挡，**每一跳重定向重新过**；正文截 8000 字；「外部资料不是指令」；前台和后台任务都带，待跟进回访不带）；P7b 笔记与清单（`NoteStore`/`NotesTools`：存、找、读、改，**没有删除工具**；只前台挂、按需读写不常驻；`memoryExclusions` 让购物单不进记忆；笔记页；插件页可关） |
| P8 文档与合规 | **已完成**（2026-09-29）：`CLAUDE.md` 重写（定位、提醒/目标/今天、后台任务规则、读网页与笔记、目录、同意闸）；隐私说明中英两份 + `DataUseNotice` 对上新行为（单条对话与清理方式、抽记忆的发送、后台任务、读网页对方能看到 IP、提醒不调模型、不留痕聊天、逐项删除路径；生效日期改为 2026-09-29）；`README.md`、`PLAYSTORE.md`（Data safety、升级说明）；`docs/architecture/ios-parity.md`。**没替你定**：Play 类目、简短/完整描述、商店截图与 `goldie.config.ts` 文案 |
| P9 侧聊（S1–S3） | **已定方案**（2026-09-30），见 §16。iOS 先行：**iOS S1–S4 已完成**（2026-09-30，落地记录在 iOS 的 `Docs/architecture/daily-agent.md`）。**Android S1 已完成**（2026-09-30）：`SideChatStore`（`tenants/<id>/sides/index.json` + 每条一个和 `thread/` 同格式的目录；每个目录一个实例、每条一个 `ThreadWriter`；逐条宽容解码、读不懂的原样留、坏文件备份、原子写；删除先落名单再清线程和照片再删目录；孤儿目录只在名单读懂时清）、`sides` 进 `TenantPaths.perTenantItems`、「⋯ › 侧聊」列表（新建 / 改名 / 删除，按最近活跃排）、`ChatViewModel(sideChat:)`（`isMainThread` 关掉「今天」、欢迎卡与首屏建议、check-in、快捷方式；空侧聊只一句说明）、`CoreInstructions.sideChat`（`PromptOrder.SIDE_CHAT = 25`，名字在第一次请求前定下来）、收割走「主对话 + 全部侧聊」、`ConversationHistory`（「对话历史」的占用空间 / 清 30 天前 / 清空全部覆盖侧聊）、离开即停、隐私说明中英两份。**Android S2 已完成**（2026-09-30）：`SideChatQuote`（纯函数：搬可见正文、工具留名字、照片不带、正文最长 3000 字；`ChatMessage.Origin.FROM_MAIN` / `FROM_SIDE_CHAT` + `Provenance`；`HistoryMarkers` 把来历说明折进下一条用户消息开头，不混进「主动说过」）、主对话回复上「在侧聊里接着聊」（名字取提问、开头是那段回答）、侧聊回复上「带回主对话」（`postProactive` 追加到主对话末尾，按完变「已带回主对话」）、`SideChatHost`（离开时还在写的留着写完，写完亮未读点：主对话「⋯」上一个点、菜单里「侧聊 · 有新回复」、列表那一行一个点和「正在回复…」；回来接上同一个对象；删侧聊、换成员、清空全部时停下）。**Android S3 已完成**（2026-09-30）：召回每轮现算够得着哪些线（`SideChatRecall.gather`：主对话够得着有内容的侧聊，侧聊够得着主对话和别的侧聊；自己只翻滑出窗口的那段，别的线整条都算看不见；删掉的侧聊下一轮就翻不到），`HistoryRecallTools.Source` 给搜出来、读回来的每一处标上在哪条线上；只有这条对话自己时工具说明和召回那段话逐字不变；主对话易变区最后多一块侧聊名单（`PromptOrder.SIDE_CHATS = 380`，最多 5 条，末尾「他没提起时不要主动说起它们」，跟着召回走、归记忆开关）。与方案 / iOS 不同的几处见 §16.10 |
| P10 撤掉子 agent（S4） | **已定方案**（2026-09-30），见 §16.7。**iOS 已完成**（2026-09-30）。**Android 已完成**（2026-09-30）：删掉 `SubagentRunner` / `SubagentScheduler` / `SubagentTools`（`start_task`、`propose_action`）/ `SubagentPlugin` / `SubagentResult` / `SubagentLimits` / `JobControls` / `AppJobControls` / `TaskCard` / `GoalDigest`、`TaskActions.decide` / `setDigest`、`PromptOrder.SUBAGENT` / `GUIDE_JOBS`、设置「只读任务自动开始」、任务页「后台任务」一节、「今天」里的任务卡、`BackgroundModelWork.runExclusive`；`Task` 去掉 `JOB` 这一类和它的字段（旧条目认不出 kind，由 `TaskStore` 原样留着、不再显示）以及 `PROPOSED` / `NEEDS_YOU` / `FAILED` 三个状态；`list_tasks` / `update_task` 不再提任务；以前存下来的 `start_task` 调用和 `Origin.TASK` 消息照样读得出来。目标的「每周回顾」换成目标详情里「在侧聊里聊这个目标」。告知对齐：隐私说明中英两份、`DataUseNotice`、`PLAYSTORE.md` 的 Data safety 与提交前真实路径、README；`DataUseNoticeLogicTest` 逐字盯着 |

---

## 0. Context：为什么改、改成什么

**现状**：Vana 的身份写死在「健康助手」上。BASE 提示词是 `HealthAssistantInstructions`，
首屏、顶栏图标（心形=测量、烧瓶=用药）、记忆分类、抽取器提示词、压缩提示词、隐私说明全是健康口径；
会话是用户可见的列表（抽屉 + 新建 + 目标 + 隐私对话）。插件化只做了第一步（"搬家"，模型看到的一字不变），
第二步「拆提示词」一直没做，而且脚手架只搭了一半：`memoryExclusions`/`dataScope`/`permissions` 声明了但没人读。

**要变成**：一个以「日常」为中心的本机 agent——
1. **一条永远的对话**（像给一个人发消息），没有新建/切换会话。
2. **健康只是插件**（默认开，可关，可独立入口），核心不认识「药」「化验单」。
3. **记忆通用化**：记人、偏好、近况、约定，而不是「指标基线」。
4. **能替你办独立的事**：提醒、目标、任务；耗时的活交给子 agent 在隔离上下文里跑，只把结论带回来。
5. ~~**首页是聊天 + 「今天」卡片**，任务在单独的「任务」页（对应 Muse 的 Goals 标签）~~ → **首页只是聊天；「今天」和任务合成
   单独一页，入口是顶栏那颗带角标的按钮**（2026-09-30 改，iOS 先行，见 §7.4）。

**不变的底线**：本机优先、自带 API key、没有中间服务器；发给第三方前必须点名同意（每条出设备的路都过闸，
子 agent 也不例外）；隐私会话按「写入路径」定义；`:agent-runtime` 保持纯 Kotlin/JVM。

---

## 1. 已定决策

| 决策 | 结论 |
|---|---|
| 能力范围 | **Vana 内部自足**：不读写系统日历/联系人，不碰其他 app；提醒用本地通知 |
| 健康默认 | **所有人默认开**（可关）；核心保留一段极短的通用安全规则，关掉健康后急症/自伤底线仍在 |
| 首页 | ~~聊天为主 + 「今天」卡片区；另有「任务」页~~ → **聊天；「今天」+ 任务是单独一页，顶栏按钮进**（2026-09-30，§7.4） |
| iOS | **Android 先行**，验证后 iOS 跟；架构沉淀成文档 |
| 会话 | **永远只有一个**；历史上下文 = 记忆 + 滑动窗口 + 可搜的原始档案 |
| 隐私对话 | **不留痕浮层**（内存里聊，关掉即没，不进时间线，不启动任务） |
| 家庭成员 | **归入健康插件**，主界面不露；目录隔离机制保留，每个成员各自一条对话 |
| 子任务确认 | ~~**每个任务先弹确认卡**；设置里可改成「只读任务自动开始」~~ → **2026-09-30 撤掉子 agent**，独立的活由用户开侧聊来做（§16） |
| 侧聊 | **一条主对话 + 用户手动开的侧聊**（2026-09-30）：窗口各管各的，记忆/档案共享；模型不开侧聊（§16） |
| 数据迁移 | **不迁移**（2026-09-29 用户确认）：旧的会话/线程存储首次启动时直接清掉；记忆、用药、测量仍在使用，原样保留 |

---

## 2. 对标 Muse：抄什么、不抄什么

| Muse | Vana 里的对应 | 说明 |
|---|---|---|
| 一条长期主对话 | 单线程时间线 | 上下文怎么滑 Meta 没公开；我们按 Letta 的三层做（见 §4） |
| Goals 标签放长任务 | 「任务」页（目标/任务/提醒） | 复用现有「目标线」概念，但目标变成一等对象，不再是一段会话 |
| 记忆可查看、可编辑、可「忘掉」 | 记忆页逐条删改 + 对话里 `forget` 工具 | 补上现在缺的逐条删除 |
| 区分「长期偏好」与「一次性例外」 | 抽取器规则 + 近况(episode)带 TTL | 临时要求不写成偏好 |
| 一条主对话 + 按话题开的 side chats（「stays aware across」） | 主对话 + 用户手动开的侧聊（§16） | 窗口分开，记忆和档案共享；主动消息只进主对话 |
| 后台继续干，需要审批时回来找你 | ~~子 agent + 确认卡 + 通知~~ → 侧聊里的回复离开后接着写完（§16.6） | 2026-09-30 撤掉子 agent，见 §16.7 |
| 敏感操作先问 + 完整审计轨迹 | 任务详情里的步骤记录；子 agent 只读，写操作只能「提议」 | 迷你版 Sentinel |
| 主动建议、只在有意义的变化时打扰 | 「今天」卡片（零模型调用）+ 现有 check-in | 通知的闸要严 |
| 连接器（邮件/日历/支付/购物） | **不做** | 与「内部自足」冲突 |
| Secure VM / 浏览器 agent / 数字分身 / 眼镜 | **不做** | 云端形态，不适用本机方案 |

---

## 3. 目标架构

```
┌──────────────────────────── UI (Compose) ────────────────────────────┐
│ 聊天(单线程) │ 今天卡片 │ 任务页 │ 记忆页 │ 插件页 │ 设置 │ 不留痕浮层 │
└───────────────┬──────────────────────────────────────────────────────┘
                │
┌───────────────▼─────────────── app 内核 ─────────────────────────────┐
│ thread/   ThreadStore(分段 JSONL) · ThreadWriter(单写者) · Archive索引 │
│ context/  窗口构建 · WindowPolicy 落地 · 快照重绑规则                  │
│ memory/   通用记忆 v2 · 抽取器(按插件拼提示词) · 水位线收割             │
│ tasks/    TaskStore · ReminderScheduler · SubagentRunner · Today聚合   │
│ plugins/  PluginRegistry · VanaPlugin(清单+UI贡献) · PromptOrder v2    │
└───────────────┬──────────────────────────────────────────────────────┘
                │ AgentPlugin（工具 + 提示词块 + memoryExclusions）
┌───────────────▼──────────── :agent-runtime（纯 JVM）─────────────────┐
│ AgentLoop · PluginHost · ContextPolicy/Planner · WindowPolicy(新)      │
│ SubagentSpec/Result/Budget(新，只有类型)                               │
└───────────────────────────────────────────────────────────────────────┘

插件：core(记忆·召回·ask_user·搜索·fetch_url·位置·时间·提醒/任务·文档识别)
      health(用药·测量·动作库·急症规则·医疗免责·化验单指引·家人档案)
```

**边界规则**（沿用现有精神）：`:agent-runtime` 不认识 `android.*`；UI 贡献（清单、卡片、图标、工具渲染）放在
app 层的 `VanaPlugin` 里，`AgentPlugin` 只管工具和提示词；工具自己声明副作用（`ToolEffect`），
隐私/后台/子 agent 的过滤统一由 `PluginContext` 做——**不要**再加 `allowsXWrites` 参数。

---

## 4. 核心：一条永远的对话

### 4.1 用户看到的

- 打开即接着上次；冷启动自动加载末尾（现在是每次冷启动都落在空会话）。
- 一条时间线，按天出分隔线；向上滑加载更早的分段。**没有抽屉会话列表、没有「新对话」、没有目标/隐私入口在抽屉里。**
- 找旧内容：菜单里「搜索对话」（复用档案索引）；清理：设置 › 对话历史（清全部 / 清 N 天前 / 长按单条删除）。
- 上下文太长不再报「请开始新对话」——改为强制淘汰到最小尾巴后重试一次，仍失败才提示缩短这条消息/附件。

### 4.2 上下文三层（Letta 模型，落到 Vana）

| 层 | 内容 | 在哪 | 怎么进模型 |
|---|---|---|---|
| **窗口** | 最近的消息，按 token 水位线滑动 | 时间线末尾 | 每轮全量发送 |
| **记忆** | 长期成立的事实 + 近况（≤40 条/2000 字） | `memory.json` | 常驻 system 段（沿用「没有索引层」的判断） |
| **档案** | 全部原始历史，逐字保留 | `thread/` 分段文件 | 模型按需 `search_sessions`/`read_session` |

**不生成滚动摘要**：调研结论是「保留原文并检索」优于「抽成事实/摘要」，递归摘要会漂（iOS 文档也写过）。
现有 `ModelSummarizer` 降级为**溢出兜底**，聊天路径默认不主动触发（它落盘的摘要绑在某条消息上，
与「窗口滑走原文」语义冲突）。

> **P4 落地时与原方案不同的几处**：
> - 召回的短编号是**消息 id 的散列**（`H3F2A…`），不是「第几条」：删一条消息不会让编号全错位。
> - 召回**只在真的有原文滑出窗口时才挂**（原方案是「游标 >0 常挂 + 原触发词仍可解锁」），触发词那套机制整个删了。
> - 档案索引不落盘，靠 `ThreadStore.ChangeListener` 增量更新，每条只存截断后的文字（用户 800 字、助手 320 字）。
> - 后台派生（待跟进回访）的「跑过没有」记在线程 `meta.json` 的 `derived` 里，不再是一个 `isDerived` 会话文件。
> - 「快照重绑规则」仍未实现：每轮读盘、内容不变时提示词逐字相同，缓存不受影响；没有出现需要它的场景。

### 4.3 滑动窗口（`WindowPolicy`，放 `:agent-runtime`，纯逻辑、秒级测试）

- **批量淘汰，不逐条丢**：涨到高水位一次砍到低水位。逐条丢会让前缀每轮都变，prompt 缓存整个失效——
  用户用自己的 key，这是真钱。初值：`budget = clamp(contextWindow × 0.35, 12k, 32k)` token；
  高水位 = budget，低水位 = 0.4 × budget；`contextWindow` 未知按 16k。数字在 P4b 用模拟测试调。
- **只在轮边界切**：一个「轮」= 一条用户消息 + 其后的助手/工具消息；绝不切在 tool_call 与 tool_result 之间；
  至少保留最后 6 个用户轮；进行中的这一轮不动。
- 游标 `windowStartMessageId` 存 `thread/meta.json`，**只在淘汰时前移**。两次淘汰之间「system + 工具定义 + 窗口」
  是纯追加 → 缓存前缀稳定。
- **时间间隔标记**：相邻消息间隔 >6h 时，在模型视图里插一行「—— 6月3日 21:10（距上条 14 小时）——」
  （由存储的 `createdAt` 确定性生成，不破坏前缀）。单线程里消息跨天，模型必须知道「昨天说的」是哪次。
- **system 段前缀稳定性**：静态内容（身份、安全、已挂工具的用法）排前面，易变快照（位置、记忆、用药、测量、目标）
  排后面——现在快照夹在中间，一次记忆变化会打掉后面所有 guide。精确时间不进 system，走 `get_current_time` 工具；
  日期只到天。
- **快照重绑规则**（替代「快照绑在会话上」）：记忆/位置/用药快照只在 (a) 发生淘汰，或 (b) 距上次请求 >10 分钟
  （缓存大概率已冷）时重读；其余时候沿用。这就是「没有会话边界也保住缓存」的办法。

### 4.4 存储（不迁移）

```
tenants/<id>/thread/
  meta.json          {schema, windowStartMessageId, harvestedUpToMessageId, lastRequestAt}
  seg-000001.jsonl   追加写，一行一条消息，每段 ≤200 条
  (档案索引：进程内存里建，后台线程启动时扫，追加时增量更新，不落盘)
```

- **追加写**替代「每轮整文件重写」：修掉 O(历史) 的 IO 和「一次坏写丢全部」（现在 `writeText` 非原子 + `runCatching` 吞解码失败）。
- 现在 `assistant` 消息里 `toolCalls`/`reasoning` 与 `storedTurn.exactTranscript` 存了两份——P4a 先**量真实文件大小**再决定存一份、读时派生。
- **UI 分页**：`LazyColumn(reverseLayout = true)`，只加载最新 1–2 段，滑到顶再载更早；流式中的那条消息单独持有，
  不再每个 delta 重映射整个列表（`mutateMessage` 现在是 O(n)）。
- **不做迁移**：新存储上线的首次启动，把旧的 `sessions/`（和其中的附件）整个删掉，从空线程开始。
  旧目标线、用药焦点线、派生会话随之一起清掉，**不转换成任务**。记忆、用药、测量不动。
  这把 `sessions.legacy/`、迁移校验、回退路径、`ThreadMigrationTest` 全部省了。
  代价是升级的用户丢掉全部旧对话历史——已由用户确认可以接受；R2 发版说明里要写明。

### 4.5 记忆收割与窗口解耦（`harvestedUpToMessageId`）

现状：抽取只在「切会话」时触发（`startNewSession/openSession/branch/...`），单线程下没有这些事件；
`onCleared` 里那一发大概率是空操作（`viewModelScope` 已取消）；抽取器每次重发整段并只留末尾 6000 字，
会重复看已抽过的、漏掉更早未抽的。新做法：

- 水位线记「已收割到哪条」；抽取输入 = **水位线之后**的消息（从头取、按 6000 字分块、一次一块，旧的先抽）。
- 触发：App 进后台（`ON_STOP`）且有 ≥2 条用户新消息；App 在前台但空闲 ≥30 分钟；发生淘汰时顺手排一次。
  全部走 `BackgroundModelWork` 那把「同时只准跑一件」的锁；拿不到位子不排队。
- 失败即放弃、水位线不前移；隐私浮层的内容永不进档案，所以也永不收割；被删的消息收割前先剔除。
- 不阻塞淘汰：档案逐字保留，没抽到的以后仍抽得到。

### 4.6 档案检索（改造 `recall/`）

- 挂载：从「用户提到『上次』才解锁」改为 **窗口游标 >0（已经有看不见的历史）时常挂** + 原触发词仍可解锁。
  这是单线程的必然：窗口不含旧内容是设计，模型得知道能去翻。
- 索引：现在每次调用解码全部会话文件、每会话只索引前 800 字、目标线排除在外。改为进程内的
  「每条用户消息 → CJK 二元组 + ASCII 词」索引，后台建、增量更；**排除当前窗口内的消息**（本来就看得见）。
  保留精度优先规则（≥ 最高分 2/3、最多 6 条）。
- `read_session` 改为以消息为锚点读前后各 K 条（仍 ≤2500 字，仍带「数值可能过期」页脚）。
  线上工具名先不改（`search_sessions`/`read_session`），描述里叫「对话历史」，避免动到历史 transcript 里的旧调用。
- 记忆条目新增 `sourceMessageId`（现在 `sourceSessionId` 从没人写过）：删一条消息时问「同时忘掉由此记下的 N 条记忆？」。

### 4.7 主动消息与单写者

- 新消息来源：check-in、到点提醒、任务结果、目标周报。统一成一种「主动消息」（助手角色，带来源标记），
  所以模型知道自己说过什么。
- **`ThreadWriter`**：每个 tenant 一个单写者（actor/Mutex），所有落盘（追加、删除、meta）串行。
  前台在流式生成时，后台来的主动消息**排队到轮边界再追加**（沿用「插话在轮边界接入」的规则）。
- 通知点开：extras 带 `messageId`，打开后滚到那条消息（现在只预填输入框，`followUpId/tenantId/period` 写了没人读）。
- 入口统一：Assistant `vana://action/ask?q=` 把问题**追加进当前线程并发送**；若正在回复则走现有排队/插话，
  **不再 `stopReply()` 砍掉进行中的回复**。

### 4.8 不留痕浮层

- 菜单进入；独立的内存态 `ChatViewModel(mode = EPHEMERAL)`，`PluginContext(isPrivate = true)`；不挂 `ThreadWriter`；
  关闭即丢弃，进程被杀也没有。
- 读路径保持现状（能读记忆/档案/搜索），写路径全堵（`WRITE_LOCAL` 由 `PluginHost` 统一丢掉，`start_task` 属于
  `WRITE_LOCAL` 所以自然不可用）；附件只留内存。机制一字不改，只换载体。

### 4.9 现有东西怎么处置

| 现有 | 处置 |
|---|---|
| `SessionStore`/`ChatSession` | 由 `ThreadStore` 直接取代，旧类型删除（不迁移，见 §4.4） |
| `SessionThread`（CheckIn/FollowUp/Goal/Medication）+ `SessionThreadPolicy` | 删除。旧目标线不转换，随旧会话清掉；以后的目标走 `Task(kind=GOAL)`；FollowUp→记忆 `followUp`/提醒；Medication 焦点→下一条消息的一次性上下文 chip；CheckIn（从没被创建过）直接删 |
| `isDerived` 与 `DerivedTurn` | `DerivedTurn` 演进为 `SubagentRunner`（§8），三个调用方（FollowUpRunner/GoalDigest/收割）迁移过去 |
| `SessionTitle`、抽屉、`SessionTimeSection` | 删除（搜索对话取代列表） |
| `ModelSummarizer`/`TranscriptCompactor` | 保留作溢出兜底；把 `healthChat` 命名与健康措辞（"身体情况与偏好"、"步数…"）改通用 |
| 「回到底部」按钮 | 保留 |

---

## 5. 插件化 v2（补完「第二步：拆提示词」）

### 5.1 接口

- **runtime 层**（`AgentPlugin`，已有）：`tools` / `promptBlocks` / `memoryExclusions`；本次**真正消费** `memoryExclusions`：
  抽取器提示词由「通用规则 + 每个已启用插件的排除项」拼成；`remember` 工具描述同理。
- **app 层新增 `VanaPlugin`**（包一个 `AgentPlugin`，带 UI 贡献）：
  `manifest`（id、中英名、一句话、图标、`defaultEnabled`、子开关）、`suggestions()`（空态/追问 chip）、
  `todayCards()`、`surfaces()`（入口页：用药、测量、家人档案）、`toolRenderer`（工具调用 chip 与卡片）。
- **`PluginRegistry`**：取代 `VanaPlugins.foreground(...)` 那串位置参数；设置里 `enabledPlugins: Set<String>`，
  健康的 `medicationsEnabled/measurementsEnabled` 变成它的子开关。「关掉=不挂」的语义不变。
- `ToolCallRecord.exerciseIDs`（通用消息模型里的动作库专用字段）→ 通用 `payload: JsonObject?`。**留到 P4 重写线程存储时直接替换**（旧会话反正清掉，不需要兼容旧字段；P1 先不动，免得 R1 里老对话的动作卡凭空消失）；
  聊天里写死的 `toolCallLabel` 与动作卡渲染搬进各插件的 `toolRenderer`。

### 5.2 提示词拆分

**Core（`CoreInstructions`，替换 `HealthAssistantInstructions`）**
身份「你是 Vana，用户的日常助手」；今天（只到天）；回复语言；**通用安全底线**（迫在眉睫的危险 → 请他联系当地急救；
自伤 → 关切并转介、不给方法）；诚实约束（只引用用户给的或工具返回的、数据不足直说、先结论）；
附件规则（「照片 N/文件 N」机制，措辞通用：票据、说明书、截图；默认看不见图；用户同意发原图的例外）；语气。

**Health（`HealthPlugin` 的块，只在健康开时进）**
急症优先与「别滥用」、不做诊断、异常趋势建议就医、化验单/药盒识别核对指引、影像不做诊断（皮疹/伤口）、
「Android 不连接设备健康数据」、`suggest_exercises` 用法（现在**没有按工具是否挂载来门控**，要补）、医疗免责。
用药/测量/焦点药/家人身份块，各自的 guide 只在工具挂载时发。

**各通用插件里的健康措辞清掉**，需要的健康提醒改由健康插件用「门控块」补：
`web_search` 的「常识性健康知识…身体数值」、`ask_user` 的「测量卡片里已有的不要重复问」、
`recall` 的 `list_measurements`、`remember` 里点名用药/测量工具（且现在不看这些工具是否挂载）、`LocationSnapshot`
的「就医方式」、`AssistantPersona` 的「不要下诊断或剂量建议」。

**`PromptOrder` 重排**：区间制——`0–99` 核心静态；`100–299` 各插件 guide（按挂载门控）；`300+` 易变快照
（位置、记忆、用药、测量、目标）；尾部插话与人格。**有意打破**「与旧装配逐字相同」。

**测试替换**：删 `LegacyHealthChatAssembly.kt` 与 `PluginAssemblyEquivalenceTest`（CLAUDE.md 早写好了这一步），换成
① `PromptAssemblyContractTest`（顺序、门控、隐私/后台/子 agent 过滤）；
② 健康开时旧规则关键句清单仍在（子串断言，不是逐字相等）；
③ **健康关时整段 system + 全部工具定义里不含健康词表**（用药/化验/诊断/剂量/血压…）；
④ `HealthAssistantInstructionsTest` 挪进健康插件测试。

### 5.3 家庭成员

`Tenant.instructionBlock`、`AgeBand`、`TenantOpening` 挪进健康插件，仅当前 tenant 为 MANAGED 时贡献；
入口从抽屉挪到「插件 › 健康 › 家人档案」；没建过成员的用户看不到这个概念。`TenantStores`/`perTenantItems`
改成由插件声明各自的每-tenant 文件，不再写死 `medications.json/measurements.json`。

---

## 6. 记忆 v2（通用化）

**类型**（保持序列化名不变，先做宽容解码，见 P0）：

| Kind | 含义 | 变化 |
|---|---|---|
| `profile` | 长期情况：作息、工作、限制、关系、进行中的事 | 提示与示例通用化 |
| `preference` | 长期偏好：怎么说话、看重什么 | 加规则：一次性要求不是偏好 |
| `episode`（新） | **近况/待续**：最近发生、还没完的事；默认 14 天过期，最多 10 条 | 补 Muse「一句话提过就能用上」的短期层，对应 OpenClaw 的按日笔记 |
| `followUp` | 说好回头看的事（天粒度） | 保持；精确时间的走提醒 |
| `interpretation` | 已有解释 | **归健康插件**（`scope = "health"`），核心不再提示 |

- **与原方案不同**（P3 落地时）：
  1. 不加 `scope` 字段。「哪个插件拥有这类记忆」由 `VanaPlugin.memoryKinds` 声明，按 Kind 判断——不动存储格式，
     `memory/` 包也不必认识插件。以后某个插件要自己的记忆种类，就加一个 Kind（P0 的宽容解码保证旧版本读不坏）。
  2. `sourceMessageId` 推迟到 P4：现在的消息 id 语义会在 P4 重写线程存储时重新定，现在填只会是错的关联。
  3. 「快照重绑规则」推迟到 P4b：现在每轮读盘，记忆不变时提示词本来就逐字相同、缓存不受影响；这条规则要到有
     窗口淘汰、后台收割的单线程里才有意义。
- **「不存易腐的东西」通用化**：不存查得到/会变的（价格、天气、日程时间、进度数字、单日数据、诊断结论）；
  存稳定偏好、关系、约束；近况走 `episode` 且带 TTL。结构性防线不变：抽取器只喂用户和助手说过的话，不喂工具输出。
- **抽取器提示词**：通用规则 + 各插件 `memoryExclusions`（健康贡献「用药走用药表、测量数字走测量卡片、不记诊断」）。
- **`remember` 之外加 `forget`/`revise`**（Muse 的「忘掉」）：记忆块每行带短编号（M1…），工具按编号操作；
  编号在快照内稳定（沿用现有排序稳定要求）。
- **记忆页**：补**逐条删除**（隐私说明已写「逐条删除」，界面只有「忘掉全部」——合规缺口）、搜索、
  按 Kind 分组含「近况」并显示到期、「来自哪句话」可跳到档案位置。
- 记忆块措辞去健康味（"不是健康数据"、"不要当成诊断" → 通用），末尾「具体数值以工具返回为准」保留。
- 顺手修：钉住的条目超上限时块被 `.take(2000)` 截在半行；`load()` 解码失败清空文件；读改写不原子。

---

## 7. 任务、提醒、「今天」

### 7.1 数据（`TaskStore`，每 tenant 一个 `tasks.json`，步骤记录另存 `tasks/<id>/run.jsonl`）

`Task { id, kind: JOB | GOAL | REMINDER, title, brief, status, createdAt, dueAt?, repeat?, result?, steps[], usage, sourceMessageId, goalId? }`
状态：`PROPOSED → QUEUED → RUNNING → NEEDS_YOU → DONE | FAILED | CANCELLED`。

- **REMINDER**：到点发本地通知，**到点不调模型**。
- **GOAL**：取代目标线。标题、为什么、计划清单、是否开周报。**进行中的目标（≤5 条，一行一个）常驻 system 段**，
  模型随时知道；周报是一个 JOB，沿用「一周一次、放下一个月不再问」的闸。
- **JOB**：子 agent 一次性任务（§8）。

### 7.2 工具（core `tasks` 插件）

`create_reminder` / `list_tasks` / `update_task`（完成、取消、改期）/ `create_goal` / `update_goal` / `start_task`，另有 `get_current_time`。
效果：读类 `READ`，写类 `WRITE_LOCAL`（所以隐私浮层与后台自动丢掉），`start_task` 另带 `NEEDS_USER`（要确认）。

### 7.3 提醒调度

把两个固定闹钟的 `CheckInScheduler` 泛化成 `ReminderScheduler`：N 个 `AlarmManager.setAndAllowWhileIdle`
（沿用现有的**非精确**方式），启动/开机/触发后重排。**v1 不申请精确闹钟权限**——`SCHEDULE_EXACT_ALARM` 在 Android 14
新装默认拒绝、Play 对 `USE_EXACT_ALARM` 有类别限制；文案照实写「可能晚几分钟」。精确提醒作为可选后续。

### 7.4 「今天」卡片（零模型调用）

由各 `VanaPlugin.todayCards()` 汇总并排序：到点 > 需要你确认 > 进行中 > 建议。核心卡：到点的提醒、待确认/进行中的任务、
目标周报、到期的 `followUp`（现在只在早晨 check-in 里露一下）。健康卡：用药回访到期（`MedicationStore.dueFollowUps`
现在写了没人用，正好接上）。UI：顶栏下一条可折叠的头，折叠时一行概览，展开最多 3 张；为空时整条不显示。
早晚 check-in 保留，正文改用「今天」概览，点开落到线程里对应的主动消息。

**2026-09-30 改：「今天」不在对话里了，和任务页合成单独一页**（iOS、Android 都已完成；Android 没有「现在」那一节——不读设备健康数据，没有状况那一行）。iOS 那边
2026-09-29 一天里把这张卡在对话里挪了四个位置（悬浮在顶上 → 横着一排 → 今天那段的段头 → 打开时排在最新那条下面），
哪个都不对：它说的是**现在**，对话那一列是**发生过的事**，线性的时间线上没有它的位置——放上面回头客被滚到底看不见，
放在最新那条下面它一直压在输入框上、新回复又排到它下面去。落地的形状：

- 一页「今天」：**现在**（健康状况那一行，点开是状况详情）· **今天要做**（今天到点/过点的提醒、到期的回头看、用药回访）·
  **之后**（还没轮到今天的提醒）· **目标** · **最近完成**。顶栏右上角那颗「任务」按钮换成「今天」，角标照旧（今天到点和已过点的提醒）。
- **入口不上 tab bar**（参照 Muse 的状态/Goals 标签，只抄「单独一处」这一半）：只有「对话」「今天」两个地方时撑不起一条，
  而且它会一直压在输入框底下。有第三个同级的地方再说。
- 目标不再出卡（「目标」一节整张列出来）；条数不设上限（不用再给对话腾地方）；「之后」按卡片指着的任务去重，不按时间再算一遍。
- 对话里为这张卡写的贴底特例整个删掉。代价是打开 app 不再自动看见它，急的靠角标兜住。

---

## 8. 子 agent（独立任务）

> **2026-09-30 撤掉了**（S4，两边都已删除），由用户手动开的侧聊取代，见 §16.7。下面保留原方案只作历史记录。

**形状**：现有 `DerivedTurn` 本来就是「非阻塞、独立上下文、失败即放弃」，把它泛化为 `SubagentRunner`。

**发起**：主对话里模型（或用户）提出独立任务 → 模型调 `start_task(title, brief, deliverable)` →
线程里出一张**「开始任务」确认卡**（复用 `ask_user` 那张不阻塞的卡：任务、预计步数、会用到的工具）→ 用户点了才 `QUEUED`。
设置里可改成「只读任务自动开始」。模型被告知：确认前不要说已经开始。

**执行**（`SubagentRunner`，app 层，内部用现有 `AgentLoop`）：
- **全新上下文**：system = 子 agent 基础提示（通用「你在完成一个独立任务」）+ 主 agent 写的 brief（必须自足）+
  所需插件块 + 记忆快照（只读）；**不带主窗口**。这也是主窗口不被工具输出撑满的原因。
- **工具**：`PluginContext(isBackground = true)` 已经会丢 `WRITE_LOCAL` 与 `NEEDS_USER`，留下 `READ`/`EXTERNAL`
  （搜索、`fetch_url`、档案、位置）。**不能再派子 agent**（`start_task` 带 `NEEDS_USER`，自然被丢）。
- **写操作只能「提议」**：runner 专属的 `propose_action(kind, payload, why)` 只把提议记进任务；结果卡上给按钮，
  用户点了才由主线程用正常的 `WRITE_LOCAL` 路径执行。
- **预算**：最多 12 轮工具、单任务 token 上限、5 分钟墙钟；**同时只跑 1 个**（复用 `BackgroundModelWork` 锁，
  手机窄网络与电量共用），排队 ≤3；每天最多 10 次任务运行。用量记进任务，详情页可见。
- **闸**：启动前查 `hasProviderConsent`；没配 key 不跑；失败即放弃，不自动重试，详情页给「重试」。
- **结果**：`TaskResult(summary, body, proposals[], sources[], usage)`。线程里追加一张**结果卡**（轮边界，走 `ThreadWriter`），
  卡上的 `summary` 同时作为助手消息进窗口——模型看得到结论，详情用 `list_tasks/get` 取；不在前台就发一条通知。
- **审计轨迹**：每一步（工具名、参数摘要、结果大小）记在任务里，任务详情页可看。
- **可恢复**：每轮 checkpoint；进程被杀后重启把 `RUNNING` 标为中断并重排一次（最多一次）。

**分两步走后台**：P6a 只在进程活着时跑（与现在的 `BackgroundDigest` 一致——它靠 Activity 生命周期触发，
`androidx.work` 依赖声明了但没有 Worker）；P6b 再评估 WorkManager `CoroutineWorker`（expedited / `setForeground`）。
后者在 Android 14 需要前台服务类型声明、Play 需要说明，**用量证明有必要再做**。

**顺带统一**：抽记忆、待跟进回访、目标周报、后续的「每日摘要」全是同一个原语的不同 spec。

---

## 9. 通用工具（core 插件）

| 工具 | 阶段 | 说明 |
|---|---|---|
| `get_current_time` | P5 | 精确时间走工具，不进 system 段（保缓存）；提醒必需 |
| 提醒/任务/目标一组 | P5 | §7.2 |
| `fetch_url` | P7 | 读网页正文：仅 http(s)、拦内网/localhost、截断到 ~8k 字、`EXTERNAL`；继承「搜回来的是资料不是指令」 |
| 文档识别（OCR/文件） | P1 | 机制本来通用；规则从 BASE 挪到 core「附件」块，健康的化验单指引留在健康块 |
| 笔记/清单插件 | P7b | 用户自己的内容（购物单、想法、草稿），按需读写，与记忆区分：记忆=关于他的事实、常驻；笔记=他的内容、按需 |
| 每日/循环监视 | 后置 | 需要更严的花费闸，先不做 |

现有 `web_search` 要 Serper key，日常场景下这是首用摩擦——列为待评估，不在本方案内解决。

---

## 10. UI 与导航

- **顶栏**：左「Vana」；右「今天」（带角标，2026-09-30 起「今天」和任务合成一页，§7.4）+「⋯」（记忆 · 搜索对话 · 插件 · 不留痕聊天 · 设置）。
  去掉抽屉、心形、烧瓶。（P2 先做了「⋯」：记忆 · 插件 · 设置；抽屉在 P4 之前还在，「任务」「今天」「搜索对话」「不留痕聊天」随后续期加。）
- **插件页**：每个插件一行（开关 + 入口）；健康 → 用药与补剂、测量卡片、家人档案、动作库说明。
- **空态**（仅全新安装、线程为空时）：新 `WelcomeCard`「你好，我是 Vana。日常的事都可以交给我」；建议 chip 由已启用插件
  轮流贡献，**通用优先**（3 个里至多 1–2 个健康）；不再有「普通对话」chip。
- **免责声明**（P2 落地时**改了原方案**）：原方案是「仅当这一轮调用过健康工具才显示」，但一段谈症状、没调任何工具的回答
  就会失去免责，比现状更差。落地的是：**「AI 生成，可能有误」每条都有**；**医疗那半句**（不构成诊断或用药建议）在
  「这一轮调用过健康工具，或者用户的话/回答里出现健康词表里的词」时补上（`HealthTopics`，确定性、不再问模型、
  故意宽、**不看健康插件的开关**）。欢迎卡上的「健康分析仅供参考」去掉。
  **告知屏里的医疗免责保留**（原方案想挪走）：健康默认对所有人开，首次同意时就该读到；同时它也列在插件页的「健康」下面和「关于」里。
- **设置重组**：模型与密钥 · 插件 · 记忆 · 提醒与通知 · 对话历史 · 隐私与关于。
- **任务页**：进行中 / 需要你 / 已完成；详情看步骤、结果、提议按钮、用量、重试。
- **记忆页**：§6。
- 文案：约 500 处 `uiText/L10n.text` 里只动带健康口径的那几十处（清单在勘察报告里），不做全量改写。
  模型可见文本目前只有中文，本方案不解决多语言提示词，但统一集中到 `PromptText` 以便以后翻译。

---

## 11. 分期

> 每一期独立可发、独立可回滚。R = 对外发版。

### P0 安全网（R1，~2–3 天）——先修会被后面放大的缺陷
- `MemoryStore`：**宽容解码**（`coerceInputValues` + 未知枚举回退）+ 解码失败**不覆盖**、备份成 `.bak`；读改写加锁。**这是加任何新 `Kind` 的前置条件。**
- 记忆页补逐条删除（隐私说明与界面不一致）。
- 删除会话/清空全部时真的删附件文件（`AttachmentStore.remove` 定义了没人调；隐私说明承诺了）。
- `SessionStore.save` 改临时文件 + 原子改名（R1 仍走旧存储）。
- 给记忆、召回、`DerivedTurn` 补第一批单元测试（现在全没有；`ChatViewModel` 也没有）。
- 把本方案的架构部分落成 `docs/architecture/`（仓库里没找到 CLAUDE.md 提到的 `plugin-architecture/`）。

### P1 内核去健康化：拆提示词 + 插件 v2（R1，~1 周）
`CoreInstructions` + 健康块拆分；`VanaPlugin`/`PluginRegistry`；`PromptOrder` 区间制；消费 `memoryExclusions`；
通用插件里的健康措辞清理；`healthChat` 命名与压缩提示词通用化；`ToolCallRecord.payload`；`TENANT` 块进健康插件；
删 Legacy 测试，换成 §5.2 的四组测试。
**验收**：健康开时旧规则关键句都在；健康关时 system+工具定义里零健康词。

### P2 界面与文案通用化（R1，~1 周）
顶栏图标、欢迎卡、chip 由插件贡献、免责声明条件化、设置重组、插件页、告知屏/隐私说明/关于、
README/PLAYSTORE/site/商店素材（`goldie.config.ts`、演示数据）。**先于 P4**（都动 `ChatScreen.kt`，避免冲突）。

### P3 记忆 v2（R2，~1 周）
Kind/`scope`/`sourceMessageId`、`episode`、抽取器按插件拼、`forget`/`revise`、记忆块去健康味、记忆页升级、
快照重绑规则（先接在现有会话模型上）。

### P4 一条对话（R2，~2–3 周，最大的一块）
- **P4a** `ThreadStore`（分段 JSONL）+ `ThreadWriter` + 首次启动清掉旧 `sessions/` + UI 分页时间线 + 冷启动自动恢复 +
  `ToolCallRecord.payload` 替换 `exerciseIDs`；**此期窗口 = 沿用现有规则**，先把存储与界面换掉；先量文件大小。
- **P4b** `WindowPolicy` 批量淘汰 + 时间间隔标记 + system 前缀稳定化 + 溢出时强制淘汰重试（替换「开始新对话」文案）。
- **P4c** 收割解耦（水位线 + 三个触发）+ 档案索引与常挂载 + `read_session` 锚点化 + 主动消息类型 + 通知/快捷方式入口统一 +
  不留痕浮层 + 设置›对话历史。
- 删：抽屉、`SessionThread` 体系、`SessionTitle`、目标弹窗。

### P5 任务、提醒、今天（R3，~1.5 周）
`TaskStore`、`ReminderScheduler`（泛化 check-in）、工具组、`get_current_time`、Today 聚合与卡片、任务页、目标一等化、通知路由。

### P6 子 agent（R3，~1.5 周）
`DerivedTurn` → `SubagentRunner`（三个既有调用方迁移）；`start_task` + 确认卡 + 预算 + 提议 + 结果卡 + 审计 + 可恢复。
P6b（WorkManager）按用量再定。

### P7 通用工具（R4）
`fetch_url`；P7b 笔记/清单插件。

### P8 文档与合规（随各期滚动，R 前必须齐）
- `CLAUDE.md` 重写定位与新边界（单线程、三层上下文、插件 v2、子 agent 规则、Android 差异）。
- 隐私说明中英两份 + `DataUseNotice`：新数据类别（提醒/任务/笔记/对话历史保留与清理）、子任务的后台调用**同样会把内容发给你配置的模型**、
  「逐条删除记忆」与「删除即删照片」终于是真的、不留痕浮层的措辞。**「发给谁 + 发送以同意为前提」要和行为逐字对上。**
- Play：类目（现为健康与健身）、简短/完整描述、Data safety；**类目怎么选是产品决策，不在本方案里替你定**。
- 给 iOS 留一份对应清单。

---

## 12. 并行执行方式（子 agent 分工）

按文件归属切开，每个 workstream 一个 worktree agent，互不改对方的热点文件：

| 工作流 | 归属目录/文件 | 依赖 |
|---|---|---|
| A 内核/提示词/插件（P1） | `agent-runtime/AgentPlugin.kt`、`agent/`、`plugins/`、相关测试 | P0 |
| B 界面与文案（P2） | `chat/ChatScreen.kt`、`settings/`、`legal/`、`assets/*.html`、README/docs | A 的 `VanaPlugin` 接口 |
| C 记忆（P3） | `memory/`、`MemoryListScreen.kt` | A（`memoryExclusions`） |
| D 线程存储（P4a spike） | 新包 `thread/`（先做存储格式 + 文件大小测量，不接 UI） | 无，可与 A–C 并行 |
| E 任务/提醒（P5 的 store/scheduler/tools） | 新包 `tasks/`、`reminders/`、`today/` | A |
| F 子 agent（P6） | `tasks/subagent/`，改造 `recall/DerivedTurn.kt` | E |

**热点文件，同一时刻只允许一个 agent 改**：`ChatViewModel.kt`、`ChatScreen.kt`、`VanaApp.kt`、`EngineSettings.kt`、`PromptOrder`。
合并顺序：P0 → A → B → C →（D 已就绪）P4 → E → F。P4 接线阶段串行，不并行。

---

## 13. 风险与对策

| 风险 | 对策 |
|---|---|
| 升级丢旧对话 | 已确认接受；只清会话存储，记忆/用药/测量保留；R2 发版说明写明 |
| 单线程后单轮成本上升 | 窗口预算封顶 32k、批量淘汰保缓存、开发者页显示窗口与用量 |
| 缓存前缀被无意打断 | 契约测试：假模型客户端记录请求，断言无淘汰、无冷却时相邻请求前缀相同 |
| 记忆在永不结束的线程里漂 | 水位线增量抽取、去重前先 update、钉住的条目不被抽取器动（沿用） |
| 健康关掉后安全裸奔 | core 保留通用安全底线；测试 ③ 只禁健康词，不禁安全句 |
| 后台执行受限（Doze/进程被杀/前台服务） | 分两步：先进程内 + checkpoint，再评估 WorkManager |
| 子 agent 花钱失控 | 确认卡、只读、单并发、单任务与每日上限、用量可见 |
| 提示注入（网页/搜索内容） | 子 agent 只读；结果是数据不是指令；写操作必须用户点提议 |
| 合规不一致 | P8 逐字对照；每条出设备的路都过 `hasProviderConsent`（含任务与收割） |
| 多写者损坏线程 | `ThreadWriter` 单写者 + 追加写 |
| 范围蔓延 | §14 明确不做清单 |

---

## 14. 明确不做 / 后置

系统日历/联系人/邮件/支付连接器、云端 VM、浏览器 agent、数字分身、精确闹钟权限、循环监视、云同步、
WhatsApp 式多渠道、多语言提示词、`web_search` 免 key 方案。
侧聊那边故意不做的见 §16.9。

---

## 15. 验证

**命令**（首次需 `ANDROID_HOME=~/Library/Android/sdk`）：
```bash
./gradlew :agent-runtime:test           # WindowPolicy、Subagent 预算、PluginHost 扩展
./gradlew :app:testPlayDebugUnitTest    # 契约测试、记忆、档案索引、ThreadStore、ThreadWriter、Reminder
./gradlew :app:assembleDebug
```

**关键测试**
- `WindowPolicyTest`：水位线、轮边界、不切 tool_call/result、最小尾巴、进行中的轮不动。
- `PrefixStabilityTest`：假 `AgentModelClient` 跑 500 轮，断言相邻请求的「system+工具+窗口前缀」仅在淘汰或冷却时变化。
- `ThreadStoreTest`：追加写、分段滚动、崩溃后只丢最后一条不坏整份、首次启动清掉旧 `sessions/`（及其附件）但不碰记忆/用药/测量。
- `MemoryDecodeTest`：未知 Kind 不清空；`.bak` 生成。
- `PromptAssemblyContractTest` + 健康关词表测试（§5.2）。
- `HarvestWatermarkTest`：只喂水位线之后、失败不前移、隐私浮层内容永不进。
- `SubagentRunnerTest`：预算耗尽、取消、同意被拒、只读过滤、提议不直接落盘、中断后恢复一次。
- `NoTraceOverlayTest`：浮层前后 `filesDir` 快照无差异。

**手动（模拟器 + debug 演示数据）**
1. 升级路径：带旧会话/用药/记忆数据启动 → 旧对话被清空、线程从空开始；用药表、测量、记忆原样。
2. 全新安装：欢迎卡通用、chip 混合、告知屏 → 同意 → 发一条消息 → 之后不再出现欢迎卡。
3. 关健康插件：用开发者页导出 system 段，确认无健康词；说「胸口疼」仍得到急救建议。
4. 造一条 500 条消息的线程：滑到顶加载、发消息不卡、淘汰后模型仍能用 `search_sessions` 翻到旧内容。
5. 提醒：设 2 分钟后 → 通知 → 点开滚到那条主动消息。
6. 子任务：让它「查 X 并整理成清单」→ 确认卡 → 结果卡 → 详情里有步骤；断网/杀进程验证失败与恢复。
7. 快捷方式在回复进行中触发：不再砍掉当前回复。

**发版前**：隐私说明中英与行为逐字对照；Play 材料更新。

---

## 16. 侧聊（side chat）与撤掉子 agent（2026-09-30 定）

> 参照物：Muse 的「一条主对话 + side chats」。按 Meta 自己的说法，测试里项目一变复杂，就有人想给某些话题单独
> 一份上下文，于是加了侧聊；Muse 在主对话和侧聊之间是互通的（「stays aware across」）。
> **这一节 iOS 先行**：S1 从 iOS 开始，Android 照这份跟，两边的差异照旧记在各自那份落地记录里。

### 16.1 为什么要

- **主对话的窗口很小**（§4.3：12k–32k）。一次深聊（一份化验单、一趟行程、一次装修比价）几轮就把日常的上下文
  挤出窗口，还提前触发淘汰；事后想找回来只能往上翻，或者指望召回。
- **子 agent 解决的是这件事的一半**（隔离上下文），但它的形状是「模型发起、没人在场」，于是要一整套
  确认卡、只读、提议、预算、调度、恢复。侧聊的形状是「用户发起、有人在场」：同一件事，那一套全都用不着。
- 和 §4 不冲突：**主对话仍然只有一条**，没有「新对话」，也不回到会话列表。侧聊是从主对话旁边岔出去的一件事，
  主对话照常往下走。

### 16.2 已定

| 决策 | 结论 |
|---|---|
| 谁开 | **只有用户手动开**。模型不开侧聊，也不建议「要不要挪到侧聊」 |
| 主对话的地位 | **永远是家**：冷启动、通知、快捷方式/Siri、提醒、check-in、「今天」都只落主对话 |
| 上下文 | **窗口各管各的，长期层共享**（§16.4） |
| 子 agent | **撤掉**（§16.7） |
| 层级 | 一层：侧聊里不能再开侧聊；不分叉、不合并 |
| 不留痕 | 保持原样。它承诺的是不留痕，侧聊是要存下来的——两件事；不留痕里开不了侧聊 |

### 16.3 用户看到的

- **入口**：「⋯ › 侧聊」→ 列表（按最近活跃排，有未读的亮一个点）→「新侧聊」：起个名字，可以留空——留空就拿
  第一句话自动起名，之后能改。**名字两个上限**：自动起名最多 20 字带省略号；他自己打的最多 40 字符——同一个名字
  英文比中文长两三倍，按中文的上限截会把英文名切掉半个词（iOS 上踩过）。
- **从主对话岔出去**（S2）：长按一问一答 →「在侧聊里接着聊」，新侧聊带着这段话当开头。
- **目标**（S4）：目标详情里「在侧聊里推进 / 回顾」，取代每周回顾（§16.7）。
- **一直认得出是侧聊**：标题栏写侧聊的名字，副标题「侧聊」（家人成员再加名字）。同「当前是谁要一直挂在视线里」。
- **空的侧聊不出主对话的首屏**：欢迎卡、首屏建议、「今天」、健康状况卡都不出，只一句「这里说的不挤主对话，
  Vana 记得的事两边都用得上」。
- 改名、删除（连同照片）。「设置 › 对话历史」的占用空间、清 30 天前、清空全部都覆盖侧聊。

### 16.4 上下文：窗口各管各的，长期层共享

| 层 | 主对话 | 侧聊 |
|---|---|---|
| system 段（记忆、用药、目标、笔记、位置、成员身份、人格） | ✓ | ✓ 同一份，每轮现取 |
| 侧聊说明块（静态区，`PromptOrder.sideChat = 25`） | — | ✓ 名字 + 「专心聊这件事；主对话里最近说的这里看不到；提醒到点出现在主对话」 |
| 窗口 | 自己的 | 自己的（各自 `meta.json` 里的窗口游标），同一套 `WindowPolicy` |
| 记忆收割 | 各记各的水位线，写进同一份 `memory.json` | 同左 |
| 召回档案 | S1 只搜自己；S3 起覆盖这位成员名下所有线程，结果标来源（主对话 / 侧聊「X」） | 同左 |
| 侧聊名单（易变区，S3） | 最多 5 个名字 + 最近活跃日期 | — |

- **带过去的开头不拷原始 transcript。** 原样拷的话，`tool_call` 和结果配对、DeepSeek 的 `reasoning_content`、
  随行原图，随便断一样就是一个 400，而且那条侧聊从此发不出去。拷成一段引用（日期 + 问句 + 回答的可见文字，
  工具只留名字，同 `read_session` 的读法），作为侧聊的第一条主动消息，按 §4 的规则折进第一条用户消息。
- 侧聊说明块在侧聊存在期间逐字不变（改名时变一次），不打缓存；不带任何领域词，健康关掉之后的契约照旧成立。
- 召回的挂载规则不变（窗口外真的有原文才挂）。S3 以后，侧聊里的「主对话」永远算窗口外，所以侧聊里基本常挂
  ——一直挂着反而不会让缓存来回抖。
- **Muse 说的「两边互通」就落在共享的这几层上**，不靠往窗口里塞别处的原文：记忆两边都写两边都读，档案两边都
  搜得到，主对话看得见有哪几条侧聊。

### 16.5 存储

```
tenants/<id>/sides/
  index.json          [{id, title, autoTitled, createdAt, lastActiveAt}]
  <uuid>/             和 thread/ 同一格式：seg-*.jsonl + meta.json（窗口游标、收割水位线）
```

- **线程格式一个字不改**：`ThreadStore` 换个目录就是侧聊。照片照旧放在成员的 `attachments/` 里。
- **`sides` 要进每个成员的隔离清单**（iOS 是 `TenantPaths.perTenantItems`）。那份清单就是「隔离」的定义：
  漏了它，侧聊就不进备份排除，删成员也删不干净。
- **删除顺序**：先从 `index.json` 摘掉，再清线程（连照片），最后删目录。反过来的话，删到一半崩了，列表里留着
  一个点进去是空的侧聊；按这个顺序，最坏只剩一个孤儿目录，启动时扫一遍 `sides/`，不在名单里的清掉。

### 16.6 运行

- **侧聊就是另一个聊天 view model 接另一个线程。不要新的执行器**：插话、排队、窗口、重试、hook、同意闸、
  没配 key 的引导、换模型迁移，全部原样。
- **离开侧聊时，正在写的回复不停**（S2）：一个宿主攥着那个 view model，等这一轮写完再放掉；列表和「⋯」上
  亮未读点。回来时接上的是同一个对象，不是重新读盘——两个对象同时写一条线会对不上。切成员时全部丢掉
  （同切成员时整个换掉聊天界面）。**S1 里离开就停**（等于按了停止，已经写出来的留着）。
- **主动消息只进主对话**。在侧聊里建的提醒，到点也进主对话：到点那一刻他不在那个话题里，而提醒那句话本身
  说得清是什么事。
- **「带回主对话」**（S2）：侧聊里长按一条回复 → 原样追加到主对话，一条带来源的主动消息
  （`Origin.SIDE_CHAT`）。**不花一次调用去总结**：总结会漂，还多付一次钱。

### 16.7 撤掉子 agent（S4）

**理由**：它唯一侧聊给不了的是「app 切到后台之后接着跑」，而 iOS 只给几十秒（`beginBackgroundTask`），Android
本来就只在进程活着时跑——两边实际上都是「下次回到前台接着排」。为这一点留着确认卡、只读、提议、预算、调度、
恢复这一整套，不划算。「模型自己决定把活儿分出去」是有意不要的。

| | 内容 |
|---|---|
| **删** | `SubagentRunner`、`SubagentScheduler`、`start_task`、`propose_action`、`ProposalCollector`、`SubagentLimits`、确认卡（`TaskCard`）、`PromptOrder.subagent` / `guideJobs`、设置「只读任务自动开始」、任务页「后台任务」一节、`JOB` 这一类（旧条目照宽容解码读进来，不再显示）、目标每周回顾（`GoalDigest` 挂在调度器上，改成目标详情里「在侧聊里回顾」） |
| **留** | `BackgroundTurn`、待跟进回访（`FollowUpRunner`）、提醒、check-in、记忆收割、`BackgroundModelWork` 那把锁——这些是主动提醒那一层，不是子 agent |
| **告知** | 隐私说明中英两份、`DataUseNotice`、Play Data safety 里关于后台任务的那几句；iOS 的 `ComplianceTests` 盯着。**声明和行为对不上是合规这一块唯一的失败模式** |

### 16.8 分期（一次一个看得见的改动）

| 期 | 内容 | 测试 |
|---|---|---|
| **S1** | `sides/` 存储（名单 + 每条一个线程目录）、隔离清单、「⋯ › 侧聊」列表、新建 / 改名 / 删除、空白侧聊、各自的窗口、侧聊说明块、主对话专属的东西在侧聊里关掉、记忆收割覆盖侧聊、「对话历史」覆盖侧聊、离开即停 | 名单读写与删除顺序、孤儿目录清扫、隔离清单含 `sides`、侧聊说明块的契约（只在侧聊出现、无领域词）、侧聊 view model 不出「今天」和首屏、收割走遍所有线程 |
| **S2** | 长按「在侧聊里接着聊」（引用开头）、「带回主对话」、离开后回复接着写完 + 未读点 | 引用开头不含 tool_call / reasoning / 原图、回来接上的是同一个对象 |
| **S3** | 跨线程召回（结果标来源）、主对话里的侧聊名单块；更新装配的契约与黄金测试 | 召回来源标注、名单块只在有侧聊时出现 |
| **S4** | 撤掉子 agent（§16.7）+ 告知对齐 | 合规测试逐字对照 |

### 16.9 故意不做

- 模型开侧聊，或者建议「挪到侧聊」。
- 侧聊嵌套、分叉、合并；侧聊之间共享窗口。
- 自动摘要带回主对话（会漂，还多一次调用）；要带回的由他自己挑那一条。
- 置顶、归档、文件夹：列表真的长到翻不动了再说。一上来就给这些，等于把会话列表请回来了。

### 16.10 Android 落地记录（和方案、和 iOS 不一样的地方）

**S1（2026-09-30）**
- **照片的删除范围收窄了**。Android 的 `ThreadStore.deleteAll()` 以前是 `attachments.removeAll()`——整个成员的照片仓库一起清。
  侧聊和主对话共用 `attachments/`，这样删一条侧聊会把主对话的照片全带走。现在 `deleteAll()` 只删这条线程自己引用的
  （同 iOS），「清空全部对话」要的一张不留由 `ConversationHistory.clearAll()` 在清完每条线程之后再清仓库。
- **孤儿目录清得比 iOS 更保守**：除了「这个进程里第一次读名单时读懂了」，还要求**没有** `index.json.bak`。iOS 只看前一条：
  名单坏过一次、被新名单覆盖之后，下一次启动读到的是一份读得懂的新名单，旧名单上的那几条目录在它眼里全是孤儿，会被当垃圾清掉。
- **「离开」落在返回栈上**：侧聊是一个导航目的地（`side/{id}`），返回键 / 返回箭头 / 删除显式调 `leaveSideChat()`；
  兜底是 `ChatViewModel.onCleared()`——那一项被弹出返回栈，不管从哪条路（快捷方式把他顶回主对话、切成员）。从侧聊里推出设置页
  那一下不算离开（返回栈上那一项还在），和 iOS 把 `onDisappear` 挂在 `NavigationStack` 外面是同一个意思。
  离开时的落盘和收割在 `SideChatStore` 自己的作用域里做：`onCleared` 的时候 `viewModelScope` 已经取消了。
- **侧聊被删之后不再写回**：`persistNow()` 先看名单上还有没有它。「设置 › 对话历史 › 清空全部」时一条侧聊可能还压在返回栈上，
  它被弹出时的那次落盘会把刚删掉的内容作为孤儿目录写回去。`ThreadStore.append` 在目录不在时建回来而不是抛异常。
- **快捷方式 / check-in 只把侧聊那两页（列表和侧聊本身）弹回主对话**；iOS 连「⋯」里的记忆、插件、设置页也一起收掉。
  Android 这边那几页原来就是「等他自己退回来再发」，这一期不动。
- 设置里「对话历史」没有在侧聊里隐藏（iOS 在侧聊里不挂）：Android 侧聊里没有通往设置的菜单项，只有「没配 key → 去设置」这一条路，
  清空时由上面那条「删了就不写回」兜住。
- 英文隐私说明的生效日期原来停在 2026-08-30（中文是 09-29，P8 漏改），这次两份一起改成 2026-09-30。

**S2（2026-09-30）**
- **入口在回复底下那一排按钮里**（「重新回答 / 在侧聊里接着聊 / 删除」，侧聊里是「带回主对话」），不是方案写的长按，
  也不是 iOS 的「⋯」：Android 的回复本来就没有长按菜单和「⋯」，操作一直是那一排文字按钮。那一排改成了会换行的
  `FlowRow`——多了一颗按钮，字号调大的人一行放不下。
- **宿主是主对话那一项返回栈上的 view model**（`SideChatHost`），每条侧聊的 `ChatViewModel` 各自放在一格自己的
  `ViewModelStore` 里（`HostedSideChat`）：放掉时清那一格，view model 走正常的 `onCleared`，`viewModelScope` 里收着线程流
  的协程跟着取消，不会挂在一个永远不死的写者上。「离开那一页」由侧聊那一项返回栈上的 `SideChatVisit` 在被清掉时告诉宿主
  ——同 S1，从侧聊里推出设置页不算离开。换成员、清空全部对话时主对话那一项整个换掉，宿主跟着 `releaseAll()`。
- 搬过去的正文最长 3000 字（同 iOS）；「已带回主对话」只记在内存里（同 iOS）。

**S3（2026-09-30）**
- 和 iOS 同一套规则（每轮现算、线名固定中文、名单最多 5 条、归记忆开关）。算「够得着哪些线」的那一步单独成
  `SideChatRecall.gather`（iOS 在 `ChatViewModel.recallSetup` 里）：Android 的 view model 起不来单元测试，拆出来才测得到
  「删掉的侧聊下一轮就翻不到」。
- 名单和召回结果里的日期沿用 Android 召回一直以来的 `yyyy-MM-dd`（iOS 是「M月d日」）。
- 每条侧聊的档案索引（`ThreadArchive`）在第一次被召回够到时建起来，之后常驻进程内存，和主对话的一样只存截断后的文字。

**S4（2026-09-30）**
- 删的范围和 iOS 一样；Android 多删了两样 iOS 没有的：`JobControls` 接口（Android 用它把「开始 / 停止 / 决定提议」从
  界面接到调度器）和 `BackgroundTurn` 上只给子 agent 用的那几个参数（额外插件、工具轮数预算、搜索和读网页）。
- 「在侧聊里聊这个目标」：同名侧聊有了就接着用（`SideChatStore.named`），输入框里替他起个头（`SideChatHost.stageDraft`，
  不自动发）。任务页和目标详情是导航目的地而不是 iOS 的 sheet，所以不用等它退场：直接导航到侧聊并
  `popUpTo(CHAT)`，返回回到主对话（和 iOS 收掉 sheet 之后推侧聊是同一个结果）。
- 以前的任务结果消息（`Origin.TASK`）不再给「查看详情」（iOS 是点不开；Android 的按钮会进一页「这一项已经不在了」，干脆不给）。
- 告知屏那一条和 iOS 逐字同义：「侧聊里的往来同样如此。一条侧聊的请求不带主对话的原文；主对话的请求里会带上你开着的几条
  侧聊的名字」。隐私说明中英两份的「侧聊」一段和 iOS 同文。Android 没有 iOS 那套提示词黄金文件，契约由
  `PromptAssemblyTest.noRouteOffersToHandWorkOffToABackgroundHelper` 盯着（哪条路上都没有 `start_task` / `propose_action`，
  `list_tasks` 不再列 job）。

---

## 附：勘察中顺带发现的现存缺陷（已并入上面各期）

`memory.json` 解码失败即清空；隐私说明承诺的逐条删记忆/删会话即删照片并不存在；已删会话仍会被收割；
`onCleared` 收割大概率空转；记忆块钉住条目超限时被截在半行；抽取器重复看已抽内容而漏掉更早内容；
召回索引每次全量解码、目标线不可搜；通知 extras 写了没人读；快捷方式触发会 `stopReply()` 砍掉进行中的回复；
`switchToOwnerIfNeeded` 改了 `TenantScope` 却没改 `VanaApp` 的 tenant 状态；`MedicationStore.dueFollowUps` 写了没人用；
`TenantOpening.quickSummary` 是死代码；`androidx.work` 声明了没有 Worker。

## 来源

- Muse：[Meta 发布稿](https://about.fb.com/news/2026/09/introducing-muse-personal-ai-agent/)、[TechCrunch](https://techcrunch.com/2026/09/23/everything-new-coming-to-metas-ai-agent-muse/)、[Muse 记忆实测](https://hindsight.vectorize.io/blog/2026/09/28/meta-muse-agent-memory)
- Muse 侧聊：[How to get started with Muse](https://www.meta.com/help/artificial-intelligence/1331373868832401/)、[How We Designed Muse](https://introducing.muse.ai/)
- Letta 三层记忆与批量淘汰：[Agent Memory | Letta](https://www.letta.com/blog/agent-memory/)
- OpenClaw 主会话与压缩：[Session management](https://docs.openclaw.ai/concepts/session)
- 原文保留优于抽取：[Fidelity Before Structure](https://arxiv.org/pdf/2601.00821)
- 逐条淘汰破坏前缀缓存：[OpenExecutive#15](https://github.com/nemsoft-eu/OpenExecutive/issues/15)
