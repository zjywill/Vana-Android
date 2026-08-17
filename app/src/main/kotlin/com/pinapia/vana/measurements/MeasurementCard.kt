package com.pinapia.vana.measurements

import java.util.UUID
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable

/**
 * 一张测量卡片：自由名称 + 值 + 观测时间。
 * 名称不走枚举——用户说「ALT」「腰围」「静息心率」都照记。
 */
@Serializable
data class MeasurementCard(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val value: String,
    val unit: String = "",
    val observedAt: Instant,
    val note: String = "",
    val recordedAt: Instant = Clock.System.now(),
) {
    val displayValue: String
        get() = if (unit.isBlank()) value else "$value $unit"

    val observedLabel: String
        get() = formatObserved(observedAt)

    companion object {
        fun normalize(name: String): String =
            name.trim().lowercase().replace(Regex("\\s+"), "")

        fun formatObserved(at: Instant, zone: TimeZone = TimeZone.currentSystemDefault()): String {
            val local = at.toLocalDateTime(zone)
            val hasTime = local.hour != 0 || local.minute != 0 || local.second != 0
            return if (hasTime) {
                "%04d-%02d-%02d %02d:%02d".format(
                    local.year, local.monthNumber, local.dayOfMonth, local.hour, local.minute,
                )
            } else {
                "%04d-%02d-%02d".format(local.year, local.monthNumber, local.dayOfMonth)
            }
        }
    }
}

/**
 * 展示优先级：数字越小越靠前。陌生指标一律靠后，截断时先被丢掉。
 * 只影响排序，不限制能不能记。
 */
object MeasurementPriority {
    const val UNKNOWN = 100

    /** order → 别名（normalize 后做包含匹配） */
    private val ranks: List<Pair<Int, List<String>>> = listOf(
        0 to listOf("身高", "height"),
        1 to listOf("体重", "weight", "公斤"),
        2 to listOf("血压", "收缩压", "舒张压", "bloodpressure", "bp"),
        3 to listOf("心率", "静息心率", "脉搏", "heartrate", "hr"),
        4 to listOf("体温", "腋温", "temperature"),
        5 to listOf("血氧", "血氧饱和度", "spo2"),
        6 to listOf("体脂", "体脂率", "bodyfat"),
        7 to listOf("腰围", "waist"),
        8 to listOf("bmi"),
        9 to listOf("血糖", "空腹血糖", "glucose"),
    )

    fun rank(name: String): Int {
        val key = MeasurementCard.normalize(name)
        if (key.isEmpty()) return UNKNOWN
        var best = UNKNOWN
        for ((order, aliases) in ranks) {
            for (alias in aliases) {
                val a = MeasurementCard.normalize(alias)
                if (a.isEmpty()) continue
                val hit = key == a ||
                    key.contains(a) ||
                    (key.length >= 2 && a.contains(key))
                if (hit) best = minOf(best, order)
            }
        }
        return best
    }

    fun isFamiliar(name: String): Boolean = rank(name) < UNKNOWN
}

/**
 * 注入系统提示的精简表：每种名称只留观测时间最新的一张；
 * 常见指标在前，陌生项靠后（超长时先砍陌生项）。
 */
data class MeasurementSnapshot(
    val cards: List<MeasurementCard> = emptyList(),
) {
    val instructionBlock: String?
        get() {
            if (cards.isEmpty()) return null
            val latest = latestPerName(cards)
            val kept = trimmed(latest)
            val lines = mutableListOf(
                "他自己口述记下的测量卡片（不是设备自动同步；每次都是新记录，旧的还在）：",
            )
            for (card in kept) {
                val note = card.note.takeIf { it.isNotBlank() }?.let { "；$it" }.orEmpty()
                val previous = previousOf(card, cards)
                val trend = previous?.let {
                    "；前次 ${it.displayValue}（${it.observedLabel}）"
                }.orEmpty()
                lines += "- ${card.name}：${card.displayValue}（${card.observedLabel}）$trend$note"
            }
            lines += "常见指标排在前面；同名历史都还在，要看更多条用 list_measurements。"
            lines += "这些数字只代表他说的那几次，不要当成持续监测；不要用 remember 再记一遍。"
            return lines.joinToString("\n")
        }

    fun latest(named: String): MeasurementCard? {
        val key = MeasurementCard.normalize(named)
        if (key.isEmpty()) return null
        return cards
            .filter { MeasurementCard.normalize(it.name) == key }
            .maxByOrNull { it.observedAt }
    }

    fun history(named: String): List<MeasurementCard> {
        val key = MeasurementCard.normalize(named)
        if (key.isEmpty()) return emptyList()
        return cards
            .filter { MeasurementCard.normalize(it.name) == key }
            .sortedByDescending { it.observedAt }
    }

    companion object {
        val empty = MeasurementSnapshot()
        private const val MAX_LINES = 24
        private const val MAX_CHARS = 1100

        fun latestPerName(cards: List<MeasurementCard>): List<MeasurementCard> {
            return cards
                .groupBy { MeasurementCard.normalize(it.name) }
                .mapNotNull { (_, group) -> group.maxByOrNull { it.observedAt } }
                .sortedWith(
                    compareBy<MeasurementCard> { MeasurementPriority.rank(it.name) }
                        .thenByDescending { it.observedAt },
                )
        }

        /** 同名、比当前更早的最近一条，用于提示趋势。 */
        fun previousOf(card: MeasurementCard, all: List<MeasurementCard>): MeasurementCard? {
            val key = MeasurementCard.normalize(card.name)
            return all
                .filter {
                    MeasurementCard.normalize(it.name) == key &&
                        it.id != card.id &&
                        it.observedAt < card.observedAt
                }
                .maxByOrNull { it.observedAt }
        }

        private fun trimmed(cards: List<MeasurementCard>): List<MeasurementCard> {
            val out = mutableListOf<MeasurementCard>()
            var chars = 0
            for (card in cards) {
                if (out.size >= MAX_LINES) break
                val line = "${card.name}：${card.displayValue}（${card.observedLabel}）"
                if (chars + line.length > MAX_CHARS && out.isNotEmpty()) break
                out += card
                chars += line.length
            }
            return out
        }
    }
}
