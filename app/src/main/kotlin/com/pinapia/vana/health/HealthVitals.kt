package com.pinapia.vana.health

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 此刻的几个关键读数——「现在是多少」。
 *
 * 和 [HealthTrigger] 是一对:触发点回答「有什么变了」,现状回答「现在是多少」。
 */
data class HealthVitals(
    val items: List<VitalItem> = emptyList(),
) {
    val measured: List<VitalItem> get() = items.filter { it.value != null }
    val isEmpty: Boolean get() = measured.isEmpty()

    companion object {
        val EMPTY = HealthVitals()

        fun read(
            steps: List<DayValue>,
            nights: List<NightSleep>,
            hearts: List<DayHeart>,
            sessions: List<WorkoutItem>,
            body: List<DayBody>,
            now: Instant = Instant.now(),
            zone: ZoneId = ZoneId.systemDefault(),
        ): HealthVitals = HealthVitals(
            items = listOf(
                sleepItem(nights, now, zone),
                stepsItem(steps, now, zone),
                restingItem(hearts),
                hrvItem(hearts),
                workoutItem(sessions, now, zone),
                weightItem(body, zone),
            ),
        )

        private fun sleepItem(
            nights: List<NightSleep>,
            now: Instant,
            zone: ZoneId,
        ): VitalItem {
            val today = LocalDate.ofInstant(now, zone)
            val sorted = nights.sortedBy { it.night }
            val last = sorted.lastOrNull()
            if (last == null || ChronoUnit.DAYS.between(last.night, today) > 1) {
                return VitalItem(
                    kind = VitalItem.Kind.SLEEP,
                    title = "昨晚睡眠",
                    value = null,
                    note = "昨晚没有记录",
                )
            }
            val hours = last.asleepSeconds / 3600.0
            val baseline = sorted.dropLast(1).map { it.asleepSeconds }
            val note = if (baseline.isNotEmpty()) {
                val average = baseline.average()
                comparison(
                    delta = ((last.asleepSeconds - average) / 60.0).roundToInt(),
                    unit = "分钟",
                    tolerance = 15,
                    reference = "最近 ${baseline.size} 晚平均",
                )
            } else {
                null
            }
            return VitalItem(
                kind = VitalItem.Kind.SLEEP,
                title = "昨晚睡眠",
                value = "${oneDecimal(hours)} 小时",
                note = note,
            )
        }

        private fun stepsItem(
            days: List<DayValue>,
            now: Instant,
            zone: ZoneId,
        ): VitalItem {
            val today = LocalDate.ofInstant(now, zone)
            val todayValue = days.lastOrNull { it.date == today }
            val history = days.filter { it.date != today }
            val hasAnySteps = days.any { it.value > 0 }
            if (todayValue == null || !hasAnySteps) {
                return VitalItem(
                    kind = VitalItem.Kind.STEPS,
                    title = "今天步数",
                    value = null,
                    note = if (hasAnySteps) "今天还没有记录" else "最近没有记录",
                )
            }
            val note = if (history.any { it.value > 0 }) {
                val average = (history.map { it.value }.average()).roundToInt()
                "最近平常 ${average} 步"
            } else {
                null
            }
            return VitalItem(
                kind = VitalItem.Kind.STEPS,
                title = "今天步数",
                value = "${todayValue.value.roundToInt()} 步",
                note = note,
            )
        }

        private fun restingItem(days: List<DayHeart>): VitalItem {
            val resting = days.sortedBy { it.date }.mapNotNull { it.restingHR }
            val latest = resting.lastOrNull()
            if (latest == null) {
                return VitalItem(
                    kind = VitalItem.Kind.RESTING_HR,
                    title = "静息心率",
                    value = null,
                    note = "最近没有记录",
                )
            }
            val baseline = resting.dropLast(1)
            val note = if (baseline.size >= 3) {
                val average = baseline.average()
                comparison(
                    delta = (latest - average).roundToInt(),
                    unit = "次",
                    tolerance = 2,
                    reference = "最近基线 ${average.roundToInt()}",
                )
            } else {
                null
            }
            return VitalItem(
                kind = VitalItem.Kind.RESTING_HR,
                title = "静息心率",
                value = "${latest.roundToInt()} 次/分",
                note = note,
            )
        }

        private fun hrvItem(days: List<DayHeart>): VitalItem {
            val hrv = days.sortedBy { it.date }.mapNotNull { it.hrv }
            val latest = hrv.lastOrNull()
            if (latest == null) {
                return VitalItem(
                    kind = VitalItem.Kind.HRV,
                    title = "HRV",
                    value = null,
                    note = "最近没有记录",
                )
            }
            val baseline = hrv.dropLast(1)
            val note = if (baseline.size >= 3) {
                val average = baseline.average()
                comparison(
                    delta = (latest - average).roundToInt(),
                    unit = "ms",
                    tolerance = 3,
                    reference = "最近基线 ${average.roundToInt()}",
                )
            } else {
                null
            }
            return VitalItem(
                kind = VitalItem.Kind.HRV,
                title = "HRV",
                value = "${latest.roundToInt()} ms",
                note = note,
            )
        }

        private fun workoutItem(
            sessions: List<WorkoutItem>,
            now: Instant,
            zone: ZoneId,
        ): VitalItem {
            val latest = sessions.maxByOrNull { it.startTime }
            if (latest == null) {
                return VitalItem(
                    kind = VitalItem.Kind.WORKOUT,
                    title = "最近一次锻炼",
                    value = null,
                    note = "最近两周没有记录",
                )
            }
            val minutes = maxOf((latest.durationSeconds / 60.0).roundToInt(), 1)
            val today = LocalDate.ofInstant(now, zone)
            val endedDay = latest.endTime.atZone(zone).toLocalDate()
            val days = ChronoUnit.DAYS.between(endedDay, today).toInt()
            val whenText = when {
                days < 1 -> "今天"
                days == 1 -> "昨天"
                else -> "$days 天前"
            }
            return VitalItem(
                kind = VitalItem.Kind.WORKOUT,
                title = "最近一次锻炼",
                value = "${whenText}的${latest.typeName} $minutes 分钟",
                note = "最近两周 ${sessions.size} 次",
            )
        }

        private fun weightItem(days: List<DayBody>, zone: ZoneId): VitalItem {
            val weights = days.sortedBy { it.date }.mapNotNull { day ->
                day.weightKg?.let { day.date to it }
            }
            val last = weights.lastOrNull()
            if (last == null) {
                return VitalItem(
                    kind = VitalItem.Kind.WEIGHT,
                    title = "体重",
                    value = null,
                    note = "最近没有记录",
                )
            }
            val first = weights.firstOrNull()
            val note = if (first != null && weights.size >= 3) {
                val span = maxOf(ChronoUnit.DAYS.between(first.first, last.first).toInt(), 1)
                val delta = last.second - first.second
                if (abs(delta) < 0.3) {
                    "$span 天里基本没变"
                } else {
                    val direction = if (delta > 0) "涨" else "降"
                    "$span 天里${direction}了 ${oneDecimal(abs(delta))} 公斤"
                }
            } else {
                null
            }
            return VitalItem(
                kind = VitalItem.Kind.WEIGHT,
                title = "体重",
                value = "${oneDecimal(last.second)} 公斤",
                note = note,
            )
        }

        private fun comparison(
            delta: Int,
            unit: String,
            tolerance: Int,
            reference: String,
        ): String {
            val joint = if (reference.lastOrNull()?.isDigit() == true) " " else ""
            if (abs(delta) <= tolerance) return "和$reference${joint}差不多"
            val direction = if (delta > 0) "多" else "少"
            return "比$reference$joint$direction ${abs(delta)} $unit"
        }

        private fun oneDecimal(value: Double): String =
            if (value == value.roundToInt().toDouble()) value.roundToInt().toString()
            else String.format("%.1f", value)
    }
}

data class VitalItem(
    val kind: Kind,
    val title: String,
    val value: String?,
    val note: String?,
) {
    enum class Kind { SLEEP, STEPS, RESTING_HR, HRV, WORKOUT, WEIGHT }

    /** 写进一句话里的说法。 */
    val phrase: String?
        get() {
            val v = value ?: return null
            return when (kind) {
                Kind.SLEEP -> "昨晚睡了 $v"
                Kind.STEPS -> "今天走了 $v"
                Kind.RESTING_HR -> "静息心率 $v"
                Kind.HRV -> "HRV $v"
                Kind.WORKOUT -> "最近一次锻炼是$v"
                Kind.WEIGHT -> "体重 $v"
            }
        }

    /** 喂给模型的那一行。 */
    val brief: String?
        get() {
            val v = value ?: return null
            return if (note != null) "$title：$v（$note）" else "$title：$v"
        }
}
