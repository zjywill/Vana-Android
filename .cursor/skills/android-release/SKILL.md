---
name: android-release
description: >-
  Cut a signed Vana Android GitHub Release (version bump, assembleRelease,
  upload Vana-x.y.z.apk) and optionally adb-install it. Use when the user asks
  to 发版, 发 release, push a signed APK, 签名包, GitHub Release, or install the
  release build on a phone.
---

# Android 签名发版

主仓库 **不进密钥**。正式 keystore 在私有仓库 [zjywill/Vana-Android-keys](https://github.com/zjywill/Vana-Android-keys)。丢了这把钥匙就不能覆盖安装、也不能走同一条更新线。

## 本地签名文件

仓库根目录（均已 gitignore，**禁止 add/commit**）：

| 文件 | 作用 |
| --- | --- |
| `key/vana-release.keystore` | PKCS12，alias `vana` |
| `keystore.properties` | `storeFile` / `storePassword` / `keyAlias` / `keyPassword` |

模板：`keystore.properties.example`。`storeFile` 必须是相对本仓库根的 `key/vana-release.keystore`。

没有这两份时，从 keys 仓库拷过来：

```bash
# 若还没 clone
git clone git@github.com:zjywill/Vana-Android-keys.git ../Vana-Android-keys

mkdir -p key
cp ../Vana-Android-keys/vana-release.keystore key/vana-release.keystore
cp ../Vana-Android-keys/keystore.properties keystore.properties
chmod 600 key/vana-release.keystore keystore.properties
```

**不要**在聊天里打印 `keystore.properties` 或密码。

确认 Gradle 会签 release：`keystore.properties` 存在时 `app/build.gradle.kts` 的 `signingConfigs.release` 才会挂上。

## 切一版 Release

用户说「发个 release」时按这个清单做，不要只打 tag 不传 APK。

1. **确认工作区**：该进这版的改动先 commit。密钥文件绝不能进暂存区（`git check-ignore -v keystore.properties key/vana-release.keystore`）。
2. **升版本**（`app/build.gradle.kts` `defaultConfig`）：
   - `versionCode` 整数 **+1**（覆盖安装靠它）
   - `versionName` 跟 tag，例如 `1.0.1` → tag `v1.0.1`
3. **commit + push `main`**。提交说明写这版用户能感知的变化，不要写「bump version」当唯一内容。
4. **打签名包**（必须在升版本的 commit 之后，APK 里的 versionName 才对得上）：

```bash
./gradlew :app:assembleRelease
```

产物：`app/build/outputs/apk/release/app-release.apk`。用 `apksigner verify --print-certs` 确认 signer DN 是 `CN=Vana, OU=Pinapia, O=Pinapia, C=CN`。

5. **建 GitHub Release 并挂 APK**（文件名 `Vana-{versionName}.apk`）：

```bash
cp app/build/outputs/apk/release/app-release.apk /tmp/Vana-1.0.1.apk
gh release create v1.0.1 /tmp/Vana-1.0.1.apk \
  --title "Vana 1.0.1" \
  --target main \
  --notes "$(cat <<'EOF'
- 用户能感知的变化
EOF
)"
```

把版本号换成这一步的 `versionName`。发布后 `.github/workflows/release.yml` 也会再打一份并 `--clobber` 上传，本地先挂上是为了 Release 页立刻能下。

6. 用 `gh release view v1.0.1` 确认 asset 是 `Vana-1.0.1.apk`，把 Release URL 回给用户。

## 装到手机

```bash
adb devices -l
adb install -r /tmp/Vana-1.0.1.apk
```

若报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`（debug 签名和 release 不是一把钥匙）：

```bash
adb uninstall com.pinapia.vana
adb install /tmp/Vana-1.0.1.apk
adb shell am start -n com.pinapia.vana/.MainActivity
```

卸载会清掉本机会话和 API key。装完告诉用户要去设置里重填 key。包名：`com.pinapia.vana`。

## GitHub Actions 密钥（CI 打同一把包）

主仓库 Settings → Secrets 需要这四个（已有就不要覆盖，除非用户明确要轮换）：

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`（`vana`）
- `ANDROID_KEY_PASSWORD`

触发：`release: published`。workflow 用 secrets 写出 `key/` + `keystore.properties` 再 `assembleRelease`。

## 禁止

- 把 `.jks` / `.keystore` / `keystore.properties` / `key/` 推进 `Vana-Android`
- 把 keys 仓库改成 public
- 为了「方便」新生成一把 keystore 覆盖现网那把（旧包将无法覆盖安装）
- `git commit --no-verify` 或把密钥塞进 commit message

## 首次生成（仅 keys 仓库还不存在时）

现网钥匙已经在 `Vana-Android-keys`。只有用户明确说「重新做一把、旧包作废」才走下面。

```bash
mkdir -p key
PASS=$(python3 -c "import secrets; print(secrets.token_urlsafe(32))")
keytool -genkeypair \
  -keystore key/vana-release.keystore \
  -alias vana \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storetype PKCS12 \
  -storepass "$PASS" -keypass "$PASS" \
  -dname "CN=Vana, OU=Pinapia, O=Pinapia, C=CN"
printf '%s\n' \
  "storeFile=key/vana-release.keystore" \
  "storePassword=${PASS}" \
  "keyAlias=vana" \
  "keyPassword=${PASS}" \
  > keystore.properties
chmod 600 key/vana-release.keystore keystore.properties
unset PASS
```

把 `vana-release.keystore` + `keystore.properties` 推进 **私有** `Vana-Android-keys`，并更新上面四个 GitHub secrets。不要写进本仓库。
