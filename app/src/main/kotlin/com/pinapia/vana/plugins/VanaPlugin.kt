package com.pinapia.vana.plugins

import com.pinapia.vana.recall.HistoryRecallTools

import com.pinapia.vana.agentruntime.AgentPlugin
import com.pinapia.vana.exercises.ExerciseLibrary
import com.pinapia.vana.location.LocationSnapshot
import com.pinapia.vana.measurements.MeasurementSnapshot
import com.pinapia.vana.measurements.MeasurementStore
import com.pinapia.vana.medications.MedicationItem
import com.pinapia.vana.medications.MedicationSnapshot
import com.pinapia.vana.medications.MedicationStore
import com.pinapia.vana.memory.MemoryItem
import com.pinapia.vana.memory.MemorySnapshot
import com.pinapia.vana.memory.MemoryStore
import com.pinapia.vana.notes.NoteStore
import com.pinapia.vana.search.WebFetchClient
import com.pinapia.vana.search.WebSearchClient
import com.pinapia.vana.thread.ThreadArchive
import com.pinapia.vana.session.ToolCallRecord
import com.pinapia.vana.tasks.TasksEnvironment
import com.pinapia.vana.today.TodayCard
import com.pinapia.vana.today.TodayContext
import com.pinapia.vana.tenant.Tenant
import com.pinapia.vana.ui.L10n

/** 设置里能开关的东西的 id。子开关的 id 挂在所属插件下面(`health.medications`)。 */
object PluginIds {
    const val CORE = "core"
    const val MEMORY = "memory"
    const val NOTES = "notes"
    const val HEALTH = HealthPlugin.ID
    const val HEALTH_MEDICATIONS = HealthPlugin.MEDICATIONS
    const val HEALTH_MEASUREMENTS = HealthPlugin.MEASUREMENTS
}

/** 一段中英文并排的文字。界面文案要在**取用的时候**才按当前语言选,不能在对象创建时定死。 */
data class Localized(val zh: String, val en: String) {
    val text: String get() = L10n.text(zh, en)
}

/**
 * 插件页上的一个入口:这个插件有哪些自己的页面。
 * 插件只说「是什么」(`id`),app 外壳决定「去哪儿」——路由表不该被插件包反过来认识。
 */
data class PluginSurface(
    val id: String,
    val title: Localized,
    val subtitle: Localized,
    /** 挂在哪个子开关下面(设置里能单独关);null 表示这个入口没有独立开关。 */
    val toggleId: String? = null,
) {
    companion object {
        const val MEDICATIONS = "medications"
        const val MEASUREMENTS = "measurements"
        const val FAMILY = "family"
        const val NOTES = "notes"
    }
}

/** 给插件页用的名片。[togglable] 为 false 的(核心)不出现在插件页,用户关不掉。 */
data class PluginManifest(
    val id: String,
    val name: Localized,
    val summary: Localized,
    val defaultEnabled: Boolean,
    val togglable: Boolean = true,
)

/** 首屏建议 chip 需要知道的那点上下文。完全本地拼,一次模型调用都不发。 */
class SuggestionContext(
    val isEnabled: (String) -> Boolean,
    val tenant: Tenant,
    val focusMedication: MedicationItem?,
    val medications: () -> MedicationSnapshot,
)

/**
 * 一个插件贡献的建议。[exclusive] 为 true 时说明它此刻有一个具体的上下文(正在聊某样药、正在替家人问),
 * 建议只该来自它——通用建议在这个时候是噪音。
 */
data class SuggestionSet(val items: List<String>, val exclusive: Boolean = false)

/** 哪条路在装配:前台聊天,还是用户不在场的后台派生。 */
enum class PluginRoute { FOREGROUND, BACKGROUND }

/**
 * 装配一条路要用的全部输入,取代原来 `VanaPlugins.foreground(...)` 那串位置参数。
 *
 * 开关在这里就兑现成「有没有 store」:某一项关着,对应插件整个不构造,不是构造了返回空——
 * 给模型一个只会报错的工具,它得先调一次才知道不行。快照用 lambda 给,关着的就不去读盘。
 * 后台派生用不到的(用药、测量、位置、搜索、动作库)保持默认的 null 即可。
 */
class PluginEnvironment(
    val isEnabled: (String) -> Boolean,
    val tenant: Tenant,
    /** 档案(整条线程的原文索引)。null 就不挂召回。 */
    val archive: ThreadArchive?,
    /** 窗口起点的位置。在它之前的才是「已经滑出去」的历史;没有就说明还没有东西滑出去。 */
    val hiddenBeforePos: () -> Double? = { null },
    /**
     * 召回还够得着的别的线(主对话里是有内容的侧聊,侧聊里是主对话和别的侧聊),整条都算看不见。
     * 由聊天界面每轮现算:侧聊刚删掉的话,下一轮就翻不到它。
     */
    val otherThreads: List<HistoryRecallTools.Source> = emptyList(),
    /** 那几条线统称什么、主对话里要挂的侧聊名单。[otherThreads] 为空时不用。 */
    val otherThreadsScope: OtherThreadsScope? = null,
    val memoryStore: MemoryStore?,
    val memorySnapshot: () -> MemorySnapshot = { MemorySnapshot.empty },
    val location: LocationSnapshot = LocationSnapshot.unknown,
    /** 提醒、目标、现在几点。null 就不挂(后台派生、不留痕浮层里不带)。 */
    val tasks: TasksEnvironment? = null,
    /** 笔记与清单。null(关着、或这条路不该碰)就不挂。 */
    val noteStore: NoteStore? = null,
    val webSearch: WebSearchClient? = null,
    /** 读网页。前台聊天和后台任务带,其余后台路(待跟进回访)不带。 */
    val webFetch: WebFetchClient? = null,
    val exerciseLibrary: ExerciseLibrary? = null,
    val medicationStore: MedicationStore? = null,
    val medicationSnapshot: () -> MedicationSnapshot = { MedicationSnapshot.empty },
    val focusMedication: MedicationItem? = null,
    val measurementStore: MeasurementStore? = null,
    val measurementSnapshot: () -> MeasurementSnapshot = { MeasurementSnapshot.empty },
)

/** 召回够得着的别的线统称什么,以及主对话里要挂的侧聊名单(见 [RecallReach])。 */
data class OtherThreadsScope(
    val others: String,
    val sideChats: List<RecallReach.Listing> = emptyList(),
)

/**
 * app 层的插件:名片加它在某条路上贡献的那几个 `AgentPlugin`。
 * `:agent-runtime` 只认识 `AgentPlugin`(工具加提示词),清单、开关、以后的界面贡献都在这一层。
 */
interface VanaPlugin {
    val manifest: PluginManifest

    fun agentPlugins(env: PluginEnvironment, route: PluginRoute): List<AgentPlugin>

    /** 这个插件自己的页面,列在插件页里。 */
    val surfaces: List<PluginSurface> get() = emptyList()

    /**
     * 这个插件**拥有**的记忆种类(比如健康拥有「已有解释」)。插件关掉之后,这些种类的条目不再带进对话
     * (数据还在盘上,重新打开就回来),记忆页里也会标出「暂不使用」。核心的种类不在任何插件名下。
     */
    val memoryKinds: Set<MemoryItem.Kind> get() = emptySet()

    /** 这个插件自己的免责声明,列在插件页里它的名字下面。 */
    val disclaimer: String? get() = null

    /** 欢迎语里「我能帮你……」的那一小段。null 就不提。 */
    val welcomeBlurb: Localized? get() = null

    fun suggestions(context: SuggestionContext): SuggestionSet = SuggestionSet(emptyList())

    /** 这个插件想放进「今天」的卡片。只读本机数据,不发模型请求;关掉的插件不会被问到。 */
    fun todayCards(context: TodayContext): List<TodayCard> = emptyList()

    /** 聊天里这个插件的工具调用怎么写成一行字(「查看了用药表」)。不认识的返回 null。 */
    fun toolLabel(call: ToolCallRecord): String? = null
}
