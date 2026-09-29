# Google Play 提交清单

这份文件只管 Play 发行。GitHub / 侧载包走 `githubRelease`，Play Console 只能上传
`playRelease`，两者的权限和更新路径不同。

## 构建

```bash
./scripts/build-aab.sh
```

产物是 `app/build/outputs/bundle/playRelease/app-play-release.aab`。提交前必须检查最终 Manifest：

```bash
./gradlew :app:processPlayReleaseMainManifest
rg "REQUEST_INSTALL_PACKAGES|USE_EXACT_ALARM|SCHEDULE_EXACT_ALARM|permission.health" \
  app/build/intermediates/merged_manifest/playRelease/processPlayReleaseMainManifest/AndroidManifest.xml
```

上面的 `rg` 应该没有任何输出。Android 版不接入设备健康数据，也不声明相关权限；
GitHub APK 才包含从 Release 下载并安装更新的权限。

## 审核访问说明

- 不要填写测试账号：Vana 没有账号和登录。
- 在 App access / 审核说明里提供一把新建的、额度足够的测试 API key。
- 测试 key、默认 Provider 和默认模型必须配套。当前全新安装默认是：
  `DeepSeek` / `deepseek-v4-flash`。
- 上架通过后作废审核 key。

可直接填写：

```text
Vana has no account system and does not require sign-in.

The conversational feature connects directly to an AI provider chosen by the user. Vana does
not operate a proxy server and does not sell API access. For review, use the temporary
credential below:

1. Open Vana and tap Settings.
2. Under Cloud model, keep Provider set to DeepSeek and model set to deepseek-v4-flash.
3. Paste the API key below and tap Test connection.
4. Return to the chat and ask: “How should I understand a routine blood test?”

Temporary review API key: <INSERT A WORKING KEY>

The Android app does not request or access device health data. Photo OCR runs on device.
Original photos are not uploaded unless the reviewer explicitly enables that choice for a photo.
Vana is not a medical device and does not provide diagnoses, treatment plans, or dosage advice.
```

## 商店声明

- App category: Health & Fitness，不选 Medical。
  （2026-09-29 备注：应用定位正在从「健康聊天」转向「日常助手，健康是插件」，见 `docs/architecture/daily-agent-plan.md`。
  **类目、简短/完整描述、商店截图和 `goldie.config.ts` 里的文案还没跟着改**——发版前一起定，不在这一步替你定。）
- Ads: No。
- Account creation: No。
- Data deletion URL: 无账号；商店说明应写明卸载会删除全部本地数据。
- Privacy Policy URL 发布自 `app/src/main/assets/PrivacyPolicy.html`，线上和包内必须是同一份。
- Data safety 要按真实传输填写：用户输入、附件 OCR 文字、用户主动选择的原图、城市名、
  记忆、用药内容、目标的名称与进展、用户让 Vana 去读的笔记，以及**用户确认过的后台任务**用到的说明/记忆/搜到的网页内容，
  会发送给用户选择的模型 Provider；离开 app 或空闲后，还没处理过的对话片段也会发去抽取记忆；
  API key 只用于鉴权，不发送给 Vana。
- **读网页是设备直连目标网站**（不经过任何中转），对方网站能看到用户的 IP 和请求的网址——隐私说明里已写明，
  Data safety 里按「网络请求」如实填。提醒是本机闹钟，到点不联网、不调用模型。
- 权限：提醒用**非精确**闹钟（`setAndAllowWhileIdle`），不申请 `SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM`；
  上面那条 `rg` 检查必须仍然没有输出。`POST_NOTIFICATIONS` 和 `RECEIVE_BOOT_COMPLETED`（重启后重排提醒）已声明。
- 发送以明确同意为前提（同 iOS 2026-08-29 那次 5.1.1(i)/5.1.2(i) 的修法）：首启告知屏
  是「同意并继续」，首次向某家 Provider 发送前还有点名确认弹窗。审核走真实路径时会先
  撞上这两步，说明文案里不要绕开。
- 不申请设备健康数据权限，不要在 Health apps declaration 里声称正在读取设备健康数据。

## 提交前真实路径

1. 全新安装，未配置 key，输入一句问题并发送：问题应留在输入框，应用直接打开设置。
2. 粘贴错误 key，点“测试连接”：不得显示原始 401 payload。
3. 粘贴审核 key，点“测试连接”：显示“连接正常”。
4. 切换 Provider 或模型：旧的成功状态立即消失。
5. 断网、额度耗尽、上下文过长分别显示可执行的中文提示。
6. 在手机、平板、横屏、分屏和大字体下走完首启、设置、拍照、聊天、删除数据。
7. Play 包里不显示 GitHub 自更新入口；GitHub APK 仍可检查并安装 Release。
8. 设一条 2 分钟后的提醒：到点收到通知、对话末尾多一条「Vana 提醒」；设一条明天的提醒后重启手机，闹钟仍在（`adb shell dumpsys alarm | rg vana`）。
9. 让 Vana 派一个后台任务：先出现确认卡；点「开始」后（首次会点名确认 provider）变成进行中，做完对话里多一条结果并有通知；
   点「停止」能停；结果里的建议「照做 / 算了」各点一次。
10. 从菜单进「不留痕聊天」：说要设提醒时它设不了（没有写盘工具），退出后主对话里没有这段。

## 升级说明（发版时用）

- 这一版把「会话列表」改成了**一条永远的对话**。**旧的会话记录不会迁移，首次启动会清掉**（连同其中的照片）；
  记忆、用药表、测量卡片不受影响。发版说明里要写清这一点，不要让老用户在打开之后才发现。
- 新增：提醒、目标、聊天顶上的「今天」、后台任务（先给确认卡再跑）、读网页、笔记与清单、不留痕聊天。健康变成可以整个关掉的插件。
- 隐私说明生效日期已改为 2026-09-29；上架前如果发布日期不同，一并改（中英两份）。

## 尚未完成

Google Play 对生成式 AI 应用要求应用内的内容举报能力。当前仓库没有接收举报的服务端，
不能用一个只在本地变化的按钮假装已经送达。提交公开 Play 版本前，需要确定接收端并实现：

- 每条助手回复的“举报”入口；
- 应用内原因选择和可选说明；
- 明确的发送确认；
- 隐私政策披露“只有用户主动举报时，所选回复和说明会发给开发者”。
