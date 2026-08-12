package com.pinapia.vana.health

import java.time.LocalDate
import kotlin.math.abs

/**
 * 个人基线:拿 60 天的中位数当「平常」。
 *
 * 中位数而不是均值——一次通宵、一次感冒不该改写什么叫正常。样本太少时干脆不给。
 */
data class Baseline(
    val median: Double,
    val sampleCount: Int,
) {
    fun deviation(of: Double): Double? {
        if (median == 0.0) return null
        return (of - median) / median * 100.0
    }

    companion object {
        fun of(values: List<Double>, minimumSamples: Int = 10): Baseline? {
            if (values.size < minimumSamples) return null
            val sorted = values.sorted()
            val middle = sorted.size / 2
            val median = if (sorted.size % 2 == 0) {
                (sorted[middle - 1] + sorted[middle]) / 2.0
            } else {
                sorted[middle]
            }
            return Baseline(median = median, sampleCount = values.size)
        }
    }
}

/**
 * 两组之间的一次比较。没有 p 值也没有相关系数:报的是「分成两组、各多少天、差多少」。
 */
data class Comparison(
    val label: String,
    val withCondition: Double,
    val withoutCondition: Double,
    val withCount: Int,
    val withoutCount: Int,
) {
    val difference: Double get() = withCondition - withoutCondition

    companion object {
        fun of(
            label: String,
            with: List<Double>,
            without: List<Double>,
            minimumPerGroup: Int = 3,
        ): Comparison? {
            if (with.size < minimumPerGroup || without.size < minimumPerGroup) return null
            return Comparison(
                label = label,
                withCondition = with.average(),
                withoutCondition = without.average(),
                withCount = with.size,
                withoutCount = without.size,
            )
        }
    }
}

object HealthAnalysis {
    /**
     * 找几组成对关系。全部本地计算,不联网。
     *
     * 相关不等于因果,输出里也这么写。
     */
    fun comparisons(
        steps: List<DayValue>,
        nights: List<NightSleep>,
        hearts: List<DayHeart>,
        sessions: List<WorkoutItem>,
    ): List<Comparison> {
        val found = mutableListOf<Comparison>()

        // 1. 有锻炼的那天晚上,睡得更长还是更短?
        val workoutDays = sessions.map { it.date }.toSet()
        val sleepAfterWorkout = nights.filter { it.night in workoutDays }
        val sleepOtherNights = nights.filter { it.night !in workoutDays }
        Comparison.of(
            label = "锻炼当晚的睡眠时长",
            with = sleepAfterWorkout.map { it.asleepSeconds / 60.0 },
            without = sleepOtherNights.map { it.asleepSeconds / 60.0 },
        )?.let { found += it }

        // 2. 睡得多的那天,第二天静息心率更低吗?
        Baseline.of(nights.map { it.asleepSeconds }, minimumSamples = 6)?.let { sleepBaseline ->
            val restingByDay = hearts.mapNotNull { day ->
                day.restingHR?.let { day.date to it }
            }.toMap()
            val afterLong = mutableListOf<Double>()
            val afterShort = mutableListOf<Double>()
            for (night in nights) {
                val nextDay = night.night.plusDays(1)
                val resting = restingByDay[nextDay] ?: continue
                if (night.asleepSeconds >= sleepBaseline.median) {
                    afterLong += resting
                } else {
                    afterShort += resting
                }
            }
            Comparison.of(
                label = "睡得比平常多之后的次日静息心率",
                with = afterLong,
                without = afterShort,
            )?.let { found += it }
        }

        // 3. 走得多的那天,HRV 更高吗?
        Baseline.of(steps.map { it.value }, minimumSamples = 6)?.let { stepBaseline ->
            val hrvByDay = hearts.mapNotNull { day ->
                day.hrv?.let { day.date to it }
            }.toMap()
            val active = mutableListOf<Double>()
            val quiet = mutableListOf<Double>()
            for (day in steps) {
                val hrv = hrvByDay[day.date] ?: continue
                if (day.value >= stepBaseline.median) {
                    active += hrv
                } else {
                    quiet += hrv
                }
            }
            Comparison.of(
                label = "走得比平常多的当天 HRV",
                with = active,
                without = quiet,
            )?.let { found += it }
        }

        // 4. 睡得晚的时候,总时长会被压缩吗?
        val bedtimes = nights.mapNotNull { night ->
            val bedtime = night.bedtime ?: return@mapNotNull null
            val local = bedtime.atZone(java.time.ZoneId.systemDefault()).toLocalTime()
            val hour = local.hour
            val minute = local.minute
            // 凌晨入睡记成 24 点之后,不然 00:30 会排在 22:00 前面。
            val minutes = (if (hour < 12) hour + 24 else hour) * 60.0 + minute
            minutes to (night.asleepSeconds / 60.0)
        }
        Baseline.of(bedtimes.map { it.first }, minimumSamples = 6)?.let { bedtimeBaseline ->
            Comparison.of(
                label = "入睡比平常晚时的睡眠时长",
                with = bedtimes.filter { it.first > bedtimeBaseline.median }.map { it.second },
                without = bedtimes.filter { it.first <= bedtimeBaseline.median }.map { it.second },
            )?.let { found += it }
        }

        return found
    }

    fun baselineLine(
        baseline: Baseline?,
        current: Double,
        format: (Double) -> String,
    ): String? {
        val b = baseline ?: return null
        var line = "${HealthStore.BASELINE_DAYS} 天基线 ${format(b.median)}" +
            "（中位数，${b.sampleCount} 天有记录）"
        val deviation = b.deviation(current)
        if (deviation != null && abs(deviation) >= 5) {
            val direction = if (deviation > 0) "高" else "低"
            line += "，本段比基线$direction ${kotlin.math.abs(deviation).toInt()}%"
        }
        return line
    }
}
