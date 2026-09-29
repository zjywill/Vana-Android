package com.pinapia.vana.today

import com.pinapia.vana.medications.MedicationSnapshot
import com.pinapia.vana.memory.MemoryItem
import com.pinapia.vana.tasks.Task
import java.time.ZoneId
import kotlinx.datetime.Instant

/** 点一张「今天」卡片该去哪儿/做什么。 */
sealed interface TodayAction {
    data object OpenTasks : TodayAction
    data class OpenTask(val id: String) : TodayAction
    data object OpenMemory : TodayAction

    /** 替他发一句话(比如说好回头看的那件事:「现在怎么样了？」)。 */
    data class Ask(val prompt: String) : TodayAction

    /** 打开某个插件的入口页(`PluginSurface.id`)。 */
    data class OpenSurface(val surfaceId: String) : TodayAction
}

/**
 * 「今天」头上的一张卡。**由本机数据拼出来,一次模型调用都不发**——这是它和「让模型写一段早间简报」
 * 的根本区别:天天打开天天付钱是不该的。谁贡献的([pluginId])、多重要([priority],大的在前)、
 * 点了去哪([action])。
 */
data class TodayCard(
    val id: String,
    val pluginId: String,
    val priority: Int,
    val title: String,
    val body: String? = null,
    val action: TodayAction = TodayAction.OpenTasks,
)

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
    const val NEEDS_YOU = 85
    const val DUE_TODAY_REMINDER = 75
    const val FOLLOW_UP_DUE = 70
    const val RUNNING = 60
    const val GOAL = 40
}

/** 折叠时那一行:「今天 · 2 件待办 · 1 项进行中」。 */
object TodaySummary {
    fun line(cards: List<TodayCard>): String? {
        if (cards.isEmpty()) return null
        val reminders = cards.count { it.priority == TodayPriority.OVERDUE_REMINDER || it.priority == TodayPriority.DUE_TODAY_REMINDER }
        val needsYou = cards.count { it.priority == TodayPriority.NEEDS_YOU }
        val rest = cards.size - reminders - needsYou
        val parts = buildList {
            if (needsYou > 0) add("$needsYou 件等你确认")
            if (reminders > 0) add("$reminders 条提醒")
            if (rest > 0) add("$rest 件其他")
        }
        return parts.joinToString(" · ")
    }
}
