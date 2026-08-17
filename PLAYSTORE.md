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

上面的 `rg` 应该没有任何输出。当前版本没有启用 Health Connect，也不在 Play 包里声明健康权限；
GitHub APK 才包含从 Release 下载并安装更新的权限。

## 审核访问说明

- 不要填写测试账号：Vana 没有账号和登录。
- 在 App access / 审核说明里提供一把新建的、额度足够的测试 API key。
- 测试 key、默认 Provider 和默认模型必须配套。当前全新安装默认是：
  `DeepSeek` / `deepseek-chat`。
- 上架通过后作废审核 key。

可直接填写：

```text
Vana has no account system and does not require sign-in.

The conversational feature connects directly to an AI provider chosen by the user. Vana does
not operate a proxy server and does not sell API access. For review, use the temporary
credential below:

1. Open Vana and tap Settings.
2. Under Cloud model, keep Provider set to DeepSeek and model set to deepseek-chat.
3. Paste the API key below and tap Test connection.
4. Return to the chat and ask: “How should I understand a routine blood test?”

Temporary review API key: <INSERT A WORKING KEY>

The current Play build does not request or access Health Connect data. Photo OCR runs on device.
Original photos are not uploaded unless the reviewer explicitly enables that choice for a photo.
Vana is not a medical device and does not provide diagnoses, treatment plans, or dosage advice.
```

## 商店声明

- App category: Health & Fitness，不选 Medical。
- Ads: No。
- Account creation: No。
- Data deletion URL: 无账号；商店说明应写明卸载会删除全部本地数据。
- Privacy Policy URL 发布自 `app/src/main/assets/PrivacyPolicy.html`，线上和包内必须是同一份。
- Data safety 要按真实传输填写：用户输入、附件 OCR 文字、用户主动选择的原图、城市名、
  记忆和用药内容会发送给用户选择的模型 Provider；API key 只用于鉴权，不发送给 Vana。
- 当前没有 Health Connect 权限，不要在 Health apps declaration 里声称正在读取 Health Connect。

## 提交前真实路径

1. 全新安装，未配置 key，输入一句问题并发送：问题应留在输入框，应用直接打开设置。
2. 粘贴错误 key，点“测试连接”：不得显示原始 401 payload。
3. 粘贴审核 key，点“测试连接”：显示“连接正常”。
4. 切换 Provider 或模型：旧的成功状态立即消失。
5. 断网、额度耗尽、上下文过长分别显示可执行的中文提示。
6. 在手机、平板、横屏、分屏和大字体下走完首启、设置、拍照、聊天、删除数据。
7. Play 包里不显示 GitHub 自更新入口；GitHub APK 仍可检查并安装 Release。

## 尚未完成

Google Play 对生成式 AI 应用要求应用内的内容举报能力。当前仓库没有接收举报的服务端，
不能用一个只在本地变化的按钮假装已经送达。提交公开 Play 版本前，需要确定接收端并实现：

- 每条助手回复的“举报”入口；
- 应用内原因选择和可选说明；
- 明确的发送确认；
- 隐私政策披露“只有用户主动举报时，所选回复和说明会发给开发者”。
