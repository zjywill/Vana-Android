package com.pinapia.vana.today

import com.pinapia.vana.medications.MedicationSnapshot
import com.pinapia.vana.memory.MemoryItem
import com.pinapia.vana.tasks.Task
import java.time.ZoneId
import kotlinx.datetime.Instant

/** 点「今天」页上的一行该去哪儿/做什么。任务详情、插件入口在那一页上面推,替他问一句要回到对话去发。 */
sealed interface TodayAction {
    data class OpenTask(val id: String) : TodayAction
    data object OpenMemory : TodayAction

    /** 替他发一句话(比如说好回头看的那件事:「现在怎么样了？」)。 */
    data class Ask(val prompt: String) : TodayAction

    /** 打开某个插件的入口页(`PluginSurface.id`)。 */
    data class OpenSurface(val surfaceId: String) : TodayAction
}

/**
 * 「今天」页上的一行。**由本机数据拼出来,一次模型调用都不发**——这是它和「让模型写一段早间简报」
 * 的根本区别:天天打开天天付钱是不该的。谁贡献的([pluginId])、多重要([priority],大的在前)、
 * 点了去哪([action])。
 *
 * 目标不在这里:那一页的「目标」一节把进行中的整张列出来,这里再放两行就是同一件事摆两遍。
 */
data class TodayCard(
    val id: String,
    val pluginId: String,
    val priority: Int,
    val title: String,
    val body: String? = null,
    val action: TodayAction,
    val kind: TodayKind = TodayKind.REMINDER,
) {
    /** 这一行指着的那条任务(今天到点的提醒)。「之后」那一节靠它去重,「完成」那颗按钮也靠它。 */
    val taskId: String? get() = (action as? TodayAction.OpenTask)?.id
}

/**
 * 这一行是哪一类。决定那颗图标的颜色和那两个字,不影响排序(排序看 `priority`)。和 iOS 同一套。
 * iOS 那边还有一类「现在的状况」(健康那一行,排在那一页的「现在」一节);Android 不读设备健康数据,没有这一类。
 */
enum class TodayKind { REMINDER, OVERDUE, FOLLOW_UP, MEDICATION }

/** 各插件拼卡片要看的那点本机数据。 */
class TodayContext(
    val now: Instant,
    val zone: ZoneId,
    val tasks: List<Task>,
    val dueFollowUps: List<MemoryItem>,
    val medications: MedicationSnapshot = MedicationSnapshot.empty,
    val isEnabled: (String) -> Boolean = { true },
)

object TodayPriority {
    const val OVERDUE_REMINDER = 90
    const val DUE_TODAY_REMINDER = 75
    const val FOLLOW_UP_DUE = 70
}
