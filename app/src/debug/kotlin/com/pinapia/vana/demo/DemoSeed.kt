package com.pinapia.vana.demo

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import com.pinapia.vana.settings.EngineSettings
import com.pinapia.vana.settings.SecureKeyStore
import com.pinapia.vana.tasks.PlanItem
import com.pinapia.vana.tasks.Task
import com.pinapia.vana.tasks.TaskKind
import com.pinapia.vana.tasks.TaskStatus
import com.pinapia.vana.tasks.TaskStore
import com.pinapia.vana.tenant.TenantPaths
import com.pinapia.vana.thread.ThreadStore
import java.io.File
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 商店截图用的演示数据补种。**只在 debug 包里存在**，release 侧是空实现。
 *
 * 为什么要在应用里做，而不是从外面 `adb push`：goldie 每次 capture 都无条件调
 * argent 的 `reinstall-app`，把 filesDir、SharedPreferences 和 per-app locale 一起冲掉。
 * 外面塞的东西活不过它开跑的第一秒,所以补种必须发生在重装之后的第一次启动里。
 *
 * 默认一行都不跑。要打开:
 *
 *     adb shell setprop debug.vana.demo 1
 *
 * 系统属性重装不丢、重启才清,正好覆盖「装一次截一轮图」这个节奏;不设的时候
 * 你自己装 debug 包不会被演示数据污染。
 *
 * 必须在 [com.pinapia.vana.tenant.TenantScope.bootstrap] **之前**调用:那一步会在
 * tenants.json 缺席时凭空建一个 owner,UUID 和演示数据里的对不上,后面全落空。
 */
object DemoSeed {
    private const val PROPERTY = "debug.vana.demo"
    private const val ASSET_ROOT = "demo"
    private const val MARKER = ".demo-seeded"

    /** 占位密钥,纯数字。只为了让「还没配置云端模型」那条横幅不出现在截图里。 */
    private const val PLACEHOLDER_API_KEY = "00000000000000000000000000000000"

    fun applyIfRequested(context: Context) {
        if (systemProperty(PROPERTY) != "1") return
        val marker = File(context.filesDir, MARKER)
        if (marker.exists()) return

        runCatching {
            seedFiles(context)
            seedSettings(context)
            pinChineseLocale(context)
            marker.writeText(ASSET_ROOT)
        }.onFailure {
            // 截图环境坏了就让它坏得明显一点,但别把应用带崩。
            android.util.Log.e("DemoSeed", "演示数据补种失败", it)
        }
    }

    private fun seedFiles(context: Context) {
        val files = context.filesDir
        File(files, TenantPaths.ROOT_NAME).deleteRecursively()

        copyAsset(context, "$ASSET_ROOT/${TenantPaths.TENANTS_FILE}", File(files, TenantPaths.TENANTS_FILE))

        val ownerId = ownerIdFrom(File(files, TenantPaths.TENANTS_FILE))
        val root = TenantPaths.root(forId = ownerId, parent = files)
        TenantPaths.ensureTenantLayout(root)

        for (name in listOf("memory.json", "medications.json", "measurements.json")) {
            copyAsset(context, "$ASSET_ROOT/tenant/$name", File(root, name))
        }
        seedThread(context, root)
        seedTasks(root)
    }

    /** 「今天」和「任务」页要有东西可看:一个进行中的目标、一条稍后的提醒、一条已经过点的。时间相对现在。 */
    private fun seedTasks(root: File) {
        val store = TaskStore(root)
        val now = Clock.System.now()
        store.add(
            Task(
                kind = TaskKind.GOAL,
                title = "备战秋季半马",
                status = TaskStatus.RUNNING,
                why = "想在 11 月跑进 2 小时",
                plan = listOf(
                    PlanItem(text = "每周三次慢跑", done = true),
                    PlanItem(text = "买一双新跑鞋"),
                    PlanItem(text = "报名比赛"),
                ),
            ),
        )
        store.add(
            Task(kind = TaskKind.REMINDER, title = "给妈妈回电话", status = TaskStatus.QUEUED, dueAt = now + 3.hours),
        )
        store.add(
            Task(kind = TaskKind.REMINDER, title = "带伞", status = TaskStatus.QUEUED, dueAt = now - 30.minutes),
        )
    }

    /**
     * 演示会话 → 线程记录。旧的「一个会话一个文件」存储在新线程上线时整个清掉,
     * 所以演示数据也得直接写成线程的样子:按创建时间把每个会话里的消息依次排进 `seg-000001.jsonl`。
     * 同时写下清理标记,免得第一次启动又把它当旧存储清掉。
     */
    private fun seedThread(context: Context, root: File) {
        val thread = File(root, "thread").also { it.mkdirs() }
        File(thread, ThreadStore.LEGACY_MARKER).writeText("demo")
        val json = Json { ignoreUnknownKeys = true }
        val sessions = context.assets.list("$ASSET_ROOT/tenant/sessions").orEmpty().mapNotNull { name ->
            val text = context.assets.open("$ASSET_ROOT/tenant/sessions/$name").bufferedReader().use { it.readText() }
            val obj = json.parseToJsonElement(text).jsonObject
            (obj["createdAt"]?.jsonPrimitive?.content ?: "") to (obj["messages"]?.jsonArray ?: return@mapNotNull null)
        }.sortedBy { it.first }
        var position = 1.0
        val lines = StringBuilder()
        for ((_, messages) in sessions) {
            for (message in messages) {
                lines.append("{\"p\":").append(position).append(",\"m\":").append(message.toString()).append("}\n")
                position += 1.0
            }
        }
        File(thread, "seg-000001.jsonl").writeText(lines.toString())
    }

    /**
     * 演示租户的 id 直接从 tenants.json 里读,不在这里再写死一份——两处各写一份,
     * 改了一处就会静默失配。
     */
    private fun ownerIdFrom(tenants: File): String {
        val text = tenants.readText()
        return Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1)
            ?: error("tenants.json 里没有 id")
    }

    private fun seedSettings(context: Context) {
        val settings = EngineSettings(context)
        settings.hasAcceptedDataUseNotice = true
        settings.recordProviderConsent(EngineSettings.DEFAULT_PROVIDER)
        SecureKeyStore(context).apiKey = PLACEHOLDER_API_KEY
    }

    /**
     * 中文界面。goldie 的 `prepare()` 对 Android 是早退的,根本不设语言;而重装会把
     * per-app locale 清掉,所以这一步也得自己来。
     */
    private fun pinChineseLocale(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        context.getSystemService(LocaleManager::class.java)
            ?.applicationLocales = LocaleList.forLanguageTags("zh-CN")
    }

    private fun copyAsset(context: Context, asset: String, target: File) {
        target.parentFile?.mkdirs()
        context.assets.open(asset).use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
    }

    private fun systemProperty(name: String): String? = runCatching {
        val clazz = Class.forName("android.os.SystemProperties")
        clazz.getMethod("get", String::class.java).invoke(null, name) as? String
    }.getOrNull()?.takeIf { it.isNotBlank() }
}
