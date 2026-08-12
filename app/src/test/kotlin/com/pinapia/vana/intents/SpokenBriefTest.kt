package com.pinapia.vana.intents

import com.pinapia.vana.health.DayActivity
import com.pinapia.vana.health.DayPeriod
import com.pinapia.vana.health.HealthSituation
import com.pinapia.vana.health.HealthTrigger
import com.pinapia.vana.health.NightSleep
import com.pinapia.vana.health.WorkoutItem
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class SpokenBriefTest {
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val now: Instant = LocalDate.of(2026, 8, 9).atTime(8, 0).atZone(zone).toInstant()

    @Test
    fun todayCombinesActivityAndHeadline() {
        val line = SpokenBrief.todayLine(
            activity = DayActivity(date = LocalDate.of(2026, 8, 9), steps = 6240.0, exerciseMinutes = 32.0),
            situation = HealthSituation(
                period = DayPeriod.MORNING,
                triggers = listOf(HealthTrigger.ShortSleep(hours = 5.4, deficitMinutes = 92)),
            ),
        )
        assertEquals(
            "今天走了 6240 步，运动 32 分钟。昨晚只睡了 5.4 小时，比最近常态少 92 分钟。",
            line,
        )
    }

    @Test
    fun todayDoesNotRepeatStepTriggers() {
        val line = SpokenBrief.todayLine(
            activity = DayActivity(date = LocalDate.of(2026, 8, 9), steps = 15_200.0),
            situation = HealthSituation(
                period = DayPeriod.EVENING,
                triggers = listOf(
                    HealthTrigger.BigActivityDay(steps = 15_200),
                    HealthTrigger.SuppressedHRV(dropPercent = 18),
                ),
            ),
        )
        assertEquals("今天走了 15200 步，明显多于平常。HRV 比最近基线低约 18%。", line)
    }

    @Test
    fun todayWithoutTriggers() {
        val line = SpokenBrief.todayLine(
            activity = DayActivity(date = LocalDate.of(2026, 8, 9), steps = 8000.0),
            situation = HealthSituation(period = DayPeriod.AFTERNOON, triggers = emptyList()),
        )
        assertEquals("今天走了 8000 步。其他几项都在最近的常态范围里。", line)
    }

    @Test
    fun sleepReportsDeltaAgainstEarlierNights() {
        val nights = listOf(
            night(4, 7 * 3600.0),
            night(5, 7 * 3600.0),
            night(6, 7 * 3600.0),
            night(7, 7 * 3600.0),
            night(8, 6 * 3600.0 + 12 * 60, bedHour = 23, bedMinute = 40, wakeHour = 6),
        )
        val line = SpokenBrief.sleepLine(nights = nights, now = now, zone = zone)
        assertEquals(
            "昨晚睡了 6 小时 12 分，23 点 40 分 睡的，6 点 醒。比最近几晚平均少 48 分钟。",
            line,
        )
    }

    @Test
    fun sleepFallsBackWhenLastNightIsMissing() {
        val line = SpokenBrief.sleepLine(
            nights = listOf(night(5, 7 * 3600.0 + 30 * 60)),
            now = now,
            zone = zone,
        )
        assertEquals(
            "昨晚没有睡眠记录，多半是没戴设备。最近一次是 8 月 5 日那晚，睡了 7 小时 30 分。",
            line,
        )
    }

    @Test
    fun sleepSkipsComparisonWithoutBaseline() {
        val line = SpokenBrief.sleepLine(
            nights = listOf(night(7, 7 * 3600.0), night(8, 6 * 3600.0)),
            now = now,
            zone = zone,
        )
        assertEquals("昨晚睡了 6 小时。", line)
    }

    @Test
    fun sleepWithoutAnyRecord() {
        assertEquals(
            "最近两周都没有睡眠记录。",
            SpokenBrief.sleepLine(nights = emptyList(), now = now, zone = zone),
        )
    }

    @Test
    fun workoutComparesAgainstSameType() {
        val sessions = listOf(
            run(9, 6, 42, distance = 7.2, heartRate = 152.0, energy = 410.0),
            run(7, 7, 30),
            run(5, 7, 30),
            run(3, 7, 30),
        )
        val line = SpokenBrief.workoutLine(sessions = sessions, now = now)
        assertEquals(
            "1 小时前那次跑步，练了 42 分钟，7.2 公里，平均心率 152，消耗 410 千卡。" +
                "比最近两周同类训练的平均时长长 12 分钟。",
            line,
        )
    }

    @Test
    fun workoutSkipsComparisonWithoutPeers() {
        val sessions = listOf(
            run(9, 6, 42),
            yoga(7), yoga(5), yoga(3),
        )
        assertEquals(
            "1 小时前那次跑步，练了 42 分钟。",
            SpokenBrief.workoutLine(sessions = sessions, now = now),
        )
    }

    @Test
    fun workoutWithoutAnyRecord() {
        assertEquals(
            "最近两周没有锻炼记录。",
            SpokenBrief.workoutLine(sessions = emptyList(), now = now),
        )
    }

    private fun night(
        day: Int,
        asleep: Double,
        bedHour: Int? = null,
        bedMinute: Int = 0,
        wakeHour: Int? = null,
    ): NightSleep {
        val date = LocalDate.of(2026, 8, day)
        return NightSleep(
            night = date,
            asleepSeconds = asleep,
            bedtime = bedHour?.let { date.atTime(it, bedMinute).atZone(zone).toInstant() },
            wake = wakeHour?.let { date.plusDays(1).atTime(it, 0).atZone(zone).toInstant() },
        )
    }

    private fun run(
        day: Int,
        hour: Int,
        minutes: Int,
        distance: Double? = null,
        heartRate: Double? = null,
        energy: Double? = null,
    ): WorkoutItem {
        val start = LocalDate.of(2026, 8, day).atTime(hour, 0).atZone(zone).toInstant()
        return WorkoutItem(
            date = LocalDate.of(2026, 8, day),
            startTime = start,
            typeName = "跑步",
            durationSeconds = minutes * 60.0,
            activeEnergyKcal = energy,
            distanceKm = distance,
            averageHeartRate = heartRate,
        )
    }

    private fun yoga(day: Int): WorkoutItem {
        val start = LocalDate.of(2026, 8, day).atTime(7, 0).atZone(zone).toInstant()
        return WorkoutItem(
            date = LocalDate.of(2026, 8, day),
            startTime = start,
            typeName = "瑜伽",
            durationSeconds = 20 * 60.0,
        )
    }
}
