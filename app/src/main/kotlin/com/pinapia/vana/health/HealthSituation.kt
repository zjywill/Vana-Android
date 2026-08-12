package com.pinapia.vana.health

import com.pinapia.vana.memory.InterestProfile
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 用户现在打开这个 app 的可能原因。
 */
sealed class HealthTrigger {
    data class JustTrained(val name: String, val minutes: Int, val endedMinutesAgo: Int) : HealthTrigger()
    data class ShortSleep(val hours: Double, val deficitMinutes: Int) : HealthTrigger()
    data class LongSleepStillLow(val hours: Double) : HealthTrigger()
    data object MissingLastNight : HealthTrigger()
    data class ElevatedRestingHR(val latest: Int, val baseline: Int) : HealthTrigger()
    data class SuppressedHRV(val dropPercent: Int) : HealthTrigger()
    data class BigActivityDay(val steps: Int) : HealthTrigger()
    data class SedentaryStreak(val days: Int) : HealthTrigger()
    data class NoWorkouts(val days: Int) : HealthTrigger()
    data class WeightShift(val deltaKg: Double, val days: Int) : HealthTrigger()
    data class LateBedtimeDrift(val minutes: Int) : HealthTrigger()
    data object NoStepsToday : HealthTrigger()
    data object WeeklyReview : HealthTrigger()

    /** 越靠前越可能是此刻打开 app 的原因。 */
    fun rank(inPeriod: DayPeriod): Int = when (this) {
        is JustTrained -> 0
        is MissingLastNight -> if (inPeriod == DayPeriod.MORNING) 1 else 7
        is ShortSleep -> if (inPeriod == DayPeriod.MORNING) 1 else 5
        is LongSleepStillLow -> if (inPeriod == DayPeriod.MORNING) 2 else 8
        is ElevatedRestingHR -> 2
        is SuppressedHRV -> 3
        is BigActivityDay -> if (inPeriod == DayPeriod.EVENING) 2 else 4
        is SedentaryStreak -> if (inPeriod == DayPeriod.EVENING) 3 else 6
        is NoStepsToday -> if (inPeriod == DayPeriod.EVENING) 3 else 9
        is NoWorkouts -> 6
        is LateBedtimeDrift -> 7
        is WeightShift -> 8
        is WeeklyReview -> 9
    }

    val question: String
        get() = when (this) {
            is JustTrained -> "刚练完$name，强度合适吗？"
            is ShortSleep -> "昨晚只睡 ${oneDecimal(hours)} 小时，要紧吗？"
            is LongSleepStillLow -> "睡够了还是累，为什么？"
            is MissingLastNight -> "昨晚没有睡眠记录？"
            is ElevatedRestingHR -> "静息心率怎么比平时高？"
            is SuppressedHRV -> "HRV 掉了，今天该练吗？"
            is BigActivityDay -> "今天走得多，要注意什么？"
            is SedentaryStreak -> "这几天怎么动得这么少？"
            is NoStepsToday -> "今天怎么一步都没走？"
            is NoWorkouts -> "$days 天没练，怎么捡起来？"
            is WeightShift -> if (deltaKg < 0) "体重降了，正常吗？" else "体重涨了，正常吗？"
            is LateBedtimeDrift -> "越睡越晚，有影响吗？"
            is WeeklyReview -> "上周整体怎么样？"
        }

    val relatedTool: String?
        get() = when (this) {
            is JustTrained, is NoWorkouts -> "workouts"
            is ShortSleep, is LongSleepStillLow, is MissingLastNight, is LateBedtimeDrift -> "sleep_summary"
            is ElevatedRestingHR, is SuppressedHRV -> "heart_rate_summary"
            is BigActivityDay, is SedentaryStreak, is NoStepsToday -> "daily_steps"
            is WeightShift -> "body_metrics"
            is WeeklyReview -> null
        }

    val brief: String
        get() = when (this) {
            is JustTrained -> "$endedMinutesAgo 分钟前刚结束一次 $minutes 分钟的$name"
            is ShortSleep -> "昨晚只睡了 ${oneDecimal(hours)} 小时，比最近常态少 $deficitMinutes 分钟"
            is LongSleepStillLow -> "昨晚睡了 ${oneDecimal(hours)} 小时，时长够但恢复指标不好看"
            is MissingLastNight -> "昨晚没有任何睡眠记录（多半是没戴设备）"
            is ElevatedRestingHR -> "静息心率 $latest 次/分，比最近基线 $baseline 高"
            is SuppressedHRV -> "HRV 比最近基线低约 $dropPercent%"
            is BigActivityDay -> "今天已经走了 $steps 步，明显高于平常"
            is SedentaryStreak -> "最近 $days 天步数只有平常的一半左右"
            is NoStepsToday -> "今天到现在 0 步"
            is NoWorkouts -> "已经 $days 天没有锻炼记录"
            is WeightShift -> "体重在 $days 天里变化了 ${oneDecimal(deltaKg)} 公斤"
            is LateBedtimeDrift -> "入睡时间比上周平均晚了约 $minutes 分钟"
            is WeeklyReview -> "周一早上，适合回顾上一周"
        }

    val topicId: String?
        get() = when (this) {
            is JustTrained -> "running"
            is ShortSleep, is LongSleepStillLow, is MissingLastNight, is LateBedtimeDrift -> "sleep"
            is ElevatedRestingHR, is SuppressedHRV -> "heart"
            is BigActivityDay, is SedentaryStreak, is NoStepsToday -> "activity"
            is NoWorkouts, is WeeklyReview -> "overall"
            is WeightShift -> "body"
        }
}

/**
 * 此刻的处境:时段 + 从数据里读出来的触发点。
 */
data class HealthSituation(
    val period: DayPeriod,
    val triggers: List<HealthTrigger>,
    val vitals: HealthVitals = HealthVitals.EMPTY,
    val interests: InterestProfile = InterestProfile.EMPTY,
) {
    val notableTriggers: List<HealthTrigger>
        get() = triggers.filter { it !is HealthTrigger.WeeklyReview }

    val hasSummaryFacts: Boolean
        get() = notableTriggers.isNotEmpty() || !vitals.isEmpty

    val questions: List<String>
        get() {
            val picked = mutableListOf<String>()
            for (question in triggers.map { it.question } + period.defaultQuestions) {
                if (picked.any { it == question }) continue
                picked += question
                if (picked.size == 3) break
            }
            return picked
        }

    /**
     * 首屏那句话。只取排在最前面的两条触发点;没有就退到现状读数。
     */
    val quickSummary: String
        get() {
            val facts = notableTriggers.take(2).map { it.brief }
            if (facts.isNotEmpty()) return facts.joinToString("；") + "。"
            val readings = vitals.measured.take(3).mapNotNull { it.phrase }
            if (readings.isNotEmpty()) return readings.joinToString("，") + "。"
            return CALM_SUMMARY
        }

    val brief: String
        get() {
            var text = period.context
            if (triggers.isNotEmpty()) {
                val lines = triggers.take(4).joinToString("\n") { "- ${it.brief}" }
                text += "\n\n从数据里读到的情况：\n$lines"
            }
            interests.summary?.let { text += "\n\n他平时的关注点：$it" }
            return text
        }

    companion object {
        const val CALM_SUMMARY = "还没有读到最近的健康数据。"

        suspend fun detect(
            healthStore: HealthStore,
            interests: InterestProfile = InterestProfile.EMPTY,
            now: Instant = Instant.now(),
            zone: ZoneId = ZoneId.systemDefault(),
        ): HealthSituation {
            return try {
                val period = DayPeriod.now(LocalTime.ofInstant(now, zone))
                val steps14 = runCatching { healthStore.dailySteps(14) }.getOrDefault(emptyList())
                val nights14 = runCatching { healthStore.sleepSummary(14) }.getOrDefault(emptyList())
                // 心率用 60 天基线窗口,和工具层一致。
                val heartsBaseline = runCatching {
                    healthStore.heartRateSummary(HealthStore.BASELINE_DAYS)
                }.getOrDefault(emptyList())
                val sessions14 = runCatching { healthStore.workouts(14) }.getOrDefault(emptyList())
                val body14 = runCatching { healthStore.bodyMetrics(14) }.getOrDefault(emptyList())
                val hearts14 = heartsBaseline.takeLast(14)

                val triggers = mutableListOf<HealthTrigger>()
                triggers += workoutTriggers(sessions14, now, zone)
                triggers += sleepTriggers(nights14, now, zone)
                triggers += heartTriggers(heartsBaseline)
                triggers += stepTriggers(steps14, now, zone)
                triggers += bodyTriggers(body14, zone)

                // longSleepStillLow:时长够但恢复指标不好看。
                longSleepStillLow(nights14, hearts14, now, zone)?.let { triggers += it }

                val today = LocalDate.ofInstant(now, zone)
                if (period == DayPeriod.MORNING && today.dayOfWeek == DayOfWeek.MONDAY) {
                    triggers += HealthTrigger.WeeklyReview
                }

                HealthSituation(
                    period = period,
                    triggers = ordered(triggers, period, interests),
                    vitals = HealthVitals.read(
                        steps = steps14,
                        nights = nights14,
                        hearts = hearts14,
                        sessions = sessions14,
                        body = body14,
                        now = now,
                        zone = zone,
                    ),
                    interests = interests,
                )
            } catch (_: Throwable) {
                HealthSituation(
                    period = DayPeriod.now(),
                    triggers = emptyList(),
                    vitals = HealthVitals.EMPTY,
                    interests = interests,
                )
            }
        }

        /** 排序:先按「最可能是打开原因」,同名次之间才看他平时爱问什么。 */
        fun ordered(
            triggers: List<HealthTrigger>,
            period: DayPeriod,
            interests: InterestProfile,
        ): List<HealthTrigger> =
            triggers.withIndex()
                .sortedWith { lhs, rhs ->
                    val left = lhs.value.rank(period)
                    val right = rhs.value.rank(period)
                    if (left != right) return@sortedWith left.compareTo(right)
                    val leftWeight = interests.weight(lhs.value.relatedTool)
                    val rightWeight = interests.weight(rhs.value.relatedTool)
                    if (leftWeight != rightWeight) return@sortedWith rightWeight.compareTo(leftWeight)
                    lhs.index.compareTo(rhs.index)
                }
                .map { it.value }

        private fun workoutTriggers(
            sessions: List<WorkoutItem>,
            now: Instant,
            zone: ZoneId,
        ): List<HealthTrigger> {
            val latest = sessions.maxByOrNull { it.startTime }
                ?: return listOf(HealthTrigger.NoWorkouts(days = 14))
            val minutesAgo = ChronoUnit.MINUTES.between(latest.endTime, now).toInt()
            if (minutesAgo in 0..180) {
                return listOf(
                    HealthTrigger.JustTrained(
                        name = latest.typeName,
                        minutes = maxOf((latest.durationSeconds / 60.0).roundToInt(), 1),
                        endedMinutesAgo = minutesAgo,
                    ),
                )
            }
            val idleDays = ChronoUnit.DAYS.between(
                latest.endTime.atZone(zone).toLocalDate(),
                LocalDate.ofInstant(now, zone),
            ).toInt()
            return if (idleDays >= 5) listOf(HealthTrigger.NoWorkouts(days = idleDays)) else emptyList()
        }

        private fun sleepTriggers(
            nights: List<NightSleep>,
            now: Instant,
            zone: ZoneId,
        ): List<HealthTrigger> {
            val today = LocalDate.ofInstant(now, zone)
            val sorted = nights.sortedBy { it.night }
            val lastNight = sorted.lastOrNull() ?: return listOf(HealthTrigger.MissingLastNight)
            val nightsAgo = ChronoUnit.DAYS.between(lastNight.night, today).toInt()
            if (nightsAgo > 1) return listOf(HealthTrigger.MissingLastNight)

            val triggers = mutableListOf<HealthTrigger>()
            val hours = lastNight.asleepSeconds / 3600.0
            val baseline = sorted.dropLast(1).map { it.asleepSeconds }
            if (baseline.isNotEmpty()) {
                val average = baseline.average()
                val deficit = ((average - lastNight.asleepSeconds) / 60.0).roundToInt()
                if (deficit >= 45) {
                    triggers += HealthTrigger.ShortSleep(hours = hours, deficitMinutes = deficit)
                }
            } else if (hours < 6.5) {
                triggers += HealthTrigger.ShortSleep(hours = hours, deficitMinutes = 0)
            }

            val bedMinutes = sorted.mapNotNull { night ->
                val bedtime = night.bedtime ?: return@mapNotNull null
                val local = bedtime.atZone(zone).toLocalTime()
                val hour = local.hour
                (if (hour < 12) hour + 24 else hour) * 60.0 + local.minute
            }
            if (bedMinutes.size >= 6) {
                val recent = bedMinutes.takeLast(3)
                val earlier = bedMinutes.dropLast(3)
                val drift = (recent.average() - earlier.average()).roundToInt()
                if (drift >= 40) {
                    triggers += HealthTrigger.LateBedtimeDrift(minutes = drift)
                }
            }
            return triggers
        }

        /**
         * 时长够但恢复指标不好看。用最近窗口里的静息/HRV,和 shortSleep 互斥。
         */
        private fun longSleepStillLow(
            nights: List<NightSleep>,
            hearts: List<DayHeart>,
            now: Instant,
            zone: ZoneId,
        ): HealthTrigger? {
            val today = LocalDate.ofInstant(now, zone)
            val sorted = nights.sortedBy { it.night }
            val last = sorted.lastOrNull() ?: return null
            if (ChronoUnit.DAYS.between(last.night, today) > 1) return null
            val hours = last.asleepSeconds / 3600.0
            val baseline = sorted.dropLast(1).map { it.asleepSeconds }
            val longEnough = if (baseline.isNotEmpty()) {
                last.asleepSeconds >= baseline.average() && hours >= 7.0
            } else {
                hours >= 7.5
            }
            if (!longEnough) return null

            val resting = hearts.sortedBy { it.date }.mapNotNull { it.restingHR }
            val hrv = hearts.sortedBy { it.date }.mapNotNull { it.hrv }
            val elevated = resting.size >= 4 && resting.last() - resting.dropLast(1).average() >= 3
            val suppressed = hrv.size >= 4 && run {
                val avg = hrv.dropLast(1).average()
                avg > 0 && hrv.last() < avg * 0.85
            }
            return if (elevated || suppressed) HealthTrigger.LongSleepStillLow(hours = hours) else null
        }

        private fun heartTriggers(days: List<DayHeart>): List<HealthTrigger> {
            val triggers = mutableListOf<HealthTrigger>()
            val resting = days.sortedBy { it.date }.mapNotNull { it.restingHR }
            // 60 天基线中位数;样本不够时退到窗口均值(同 iOS 短期逻辑)。
            val restingBaseline = Baseline.of(resting.dropLast(1), minimumSamples = 10)
                ?: resting.dropLast(1).takeIf { it.size >= 3 }?.let {
                    Baseline(median = it.average(), sampleCount = it.size)
                }
            if (restingBaseline != null) {
                val latest = resting.lastOrNull()
                if (latest != null && latest - restingBaseline.median >= 3) {
                    triggers += HealthTrigger.ElevatedRestingHR(
                        latest = latest.roundToInt(),
                        baseline = restingBaseline.median.roundToInt(),
                    )
                }
            }

            val hrv = days.sortedBy { it.date }.mapNotNull { it.hrv }
            val hrvBaseline = Baseline.of(hrv.dropLast(1), minimumSamples = 10)
                ?: hrv.dropLast(1).takeIf { it.size >= 3 }?.let {
                    Baseline(median = it.average(), sampleCount = it.size)
                }
            if (hrvBaseline != null && hrvBaseline.median > 0) {
                val latest = hrv.lastOrNull()
                if (latest != null && latest < hrvBaseline.median * 0.85) {
                    triggers += HealthTrigger.SuppressedHRV(
                        dropPercent = ((1 - latest / hrvBaseline.median) * 100).roundToInt(),
                    )
                }
            }
            return triggers
        }

        private fun stepTriggers(
            days: List<DayValue>,
            now: Instant,
            zone: ZoneId,
        ): List<HealthTrigger> {
            val today = LocalDate.ofInstant(now, zone)
            val sorted = days.sortedBy { it.date }
            if (sorted.size < 4) return emptyList()
            val todayValue = sorted.lastOrNull { it.date == today }
            val history = sorted.filter { it.date != today }
            if (history.isEmpty()) return emptyList()
            val baseline = history.map { it.value }.average()
            if (baseline <= 0) return emptyList()

            if (todayValue != null && todayValue.value == 0.0 && DayPeriod.now(LocalTime.ofInstant(now, zone)) != DayPeriod.MORNING) {
                return listOf(HealthTrigger.NoStepsToday)
            }
            if (todayValue != null && todayValue.value >= baseline * 1.5 && todayValue.value >= 12_000) {
                return listOf(HealthTrigger.BigActivityDay(steps = todayValue.value.roundToInt()))
            }
            val recent = history.takeLast(3).map { it.value }
            if (recent.size == 3) {
                val average = recent.average()
                if (average < baseline * 0.6) {
                    return listOf(HealthTrigger.SedentaryStreak(days = 3))
                }
            }
            return emptyList()
        }

        private fun bodyTriggers(days: List<DayBody>, zone: ZoneId): List<HealthTrigger> {
            val weights = days.sortedBy { it.date }.mapNotNull { day ->
                day.weightKg?.let { day.date to it }
            }
            if (weights.size < 4) return emptyList()
            val first = weights.first()
            val last = weights.last()
            val delta = last.second - first.second
            if (abs(delta) < 1.0) return emptyList()
            val spanDays = maxOf(ChronoUnit.DAYS.between(first.first, last.first).toInt(), 1)
            return listOf(HealthTrigger.WeightShift(deltaKg = delta, days = spanDays))
        }
    }
}

enum class DayPeriod {
    MORNING, AFTERNOON, EVENING;

    val label: String
        get() = when (this) {
            MORNING -> "早上"
            AFTERNOON -> "下午"
            EVENING -> "晚上"
        }

    val context: String
        get() = when (this) {
            MORNING -> "现在是早上，用户刚醒，最可能关心昨晚睡得怎么样、今天状态适不适合训练。"
            AFTERNOON -> "现在是下午，用户可能刚练完或者想知道今天活动量攒够了没有。"
            EVENING -> "现在是晚上，用户在收尾一天，最可能关心今天运动量够不够、这周练得怎么样。"
        }

    val defaultQuestions: List<String>
        get() = when (this) {
            MORNING -> listOf("昨晚睡得怎么样？", "最近睡眠稳定吗？", "今天适合训练吗？")
            AFTERNOON -> listOf("今天走够步数了吗？", "刚练完，恢复得如何？", "最近状态还行吗？")
            EVENING -> listOf("今天运动量够吗？", "这周锻炼够了吗？", "今天比昨天累吗？")
        }

    companion object {
        fun now(time: LocalTime = LocalTime.now()): DayPeriod = when (time.hour) {
            in 5 until 11 -> MORNING
            in 11 until 18 -> AFTERNOON
            else -> EVENING
        }
    }
}

private fun oneDecimal(value: Double): String =
    if (value == value.roundToInt().toDouble()) value.roundToInt().toString()
    else String.format("%.1f", value)
