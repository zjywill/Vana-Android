package com.pinapia.vana.tasks

import java.time.ZoneId
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderRulesTest {
    private val shanghai = ZoneId.of("Asia/Shanghai")
    private val newYork = ZoneId.of("America/New_York")

    private fun at(text: String) = Instant.parse(text)

    @Test
    fun parsesTheLocalTimeFormatsAModelWillGive() {
        val expected = at("2026-10-01T12:00:00Z") // 上海 20:00
        assertEquals(expected, ReminderRules.parseLocal("2026-10-01T20:00", shanghai))
        assertEquals(expected, ReminderRules.parseLocal("2026-10-01 20:00", shanghai))
        assertEquals(expected, ReminderRules.parseLocal("2026-10-01T20:00:00", shanghai))
        assertNull(ReminderRules.parseLocal("明天晚上八点", shanghai))
        assertNull(ReminderRules.parseLocal("", shanghai))
    }

    @Test
    fun theSameWallClockTimeLandsOnDifferentInstantsInDifferentZones() {
        assertTrue(ReminderRules.parseLocal("2026-10-01T20:00", shanghai) != ReminderRules.parseLocal("2026-10-01T20:00", newYork))
    }

    @Test
    fun aDailyReminderNextFiresTomorrowAtTheSameWallTime() {
        val due = at("2026-10-01T12:00:00Z") // 上海 20:00
        val next = ReminderRules.nextOccurrence(due, Repeat.DAILY, after = at("2026-10-01T12:00:05Z"), zone = shanghai)
        assertEquals(at("2026-10-02T12:00:00Z"), next)
    }

    @Test
    fun aDailyReminderMissedForSeveralDaysSkipsToTheNextFutureOccurrence() {
        val due = at("2026-10-01T12:00:00Z")
        val next = ReminderRules.nextOccurrence(due, Repeat.DAILY, after = at("2026-10-05T13:00:00Z"), zone = shanghai)
        assertEquals(at("2026-10-06T12:00:00Z"), next)
    }

    @Test
    fun aWeeklyReminderKeepsItsWeekday() {
        val due = at("2026-10-01T12:00:00Z") // 周四
        val next = ReminderRules.nextOccurrence(due, Repeat.WEEKLY, after = at("2026-10-01T12:00:01Z"), zone = shanghai)
        assertEquals(at("2026-10-08T12:00:00Z"), next)
    }

    @Test
    fun aDailyReminderKeepsItsWallClockTimeAcrossADaylightSavingChange() {
        // 纽约 2026-03-08 凌晨进入夏令时。每天 09:00 的提醒,墙上时间不能漂成 08:00 或 10:00。
        val due = ReminderRules.parseLocal("2026-03-07T09:00", newYork)!!
        val next = ReminderRules.nextOccurrence(due, Repeat.DAILY, after = due, zone = newYork)!!
        assertEquals(ReminderRules.parseLocal("2026-03-08T09:00", newYork), next)
    }

    @Test
    fun aReminderThatDoesNotRepeatHasNoNextOccurrence() {
        assertNull(ReminderRules.nextOccurrence(at("2026-10-01T12:00:00Z"), Repeat.NONE, at("2026-10-02T00:00:00Z"), shanghai))
    }

    private fun reminder(due: String, repeat: Repeat = Repeat.NONE) =
        Task(kind = TaskKind.REMINDER, title = "x", status = TaskStatus.QUEUED, dueAt = at(due), repeat = repeat)

    @Test
    fun aFutureReminderIsScheduledForItsOwnTime() {
        val task = reminder("2026-10-01T12:00:00Z")
        assertEquals(task.dueAt, ReminderRules.scheduledFor(task, now = at("2026-09-30T00:00:00Z"), zone = shanghai))
    }

    @Test
    fun aMissedReminderThatDoesNotRepeatHasNothingToSchedule() {
        assertNull(ReminderRules.scheduledFor(reminder("2026-10-01T12:00:00Z"), now = at("2026-10-02T00:00:00Z"), zone = shanghai))
    }

    @Test
    fun aMissedRepeatingReminderIsScheduledForItsNextOccurrence() {
        val next = ReminderRules.scheduledFor(reminder("2026-10-01T12:00:00Z", Repeat.DAILY), now = at("2026-10-03T00:00:00Z"), zone = shanghai)
        assertEquals(at("2026-10-03T12:00:00Z"), next)
    }

    @Test
    fun afterFiringARepeatingReminderMovesForwardAndAOneOffOneIsDone() {
        val repeating = ReminderRules.afterFiring(reminder("2026-10-01T12:00:00Z", Repeat.DAILY), at("2026-10-01T12:00:03Z"), shanghai)
        assertEquals(TaskStatus.QUEUED, repeating.status)
        assertEquals(at("2026-10-02T12:00:00Z"), repeating.dueAt)

        val oneOff = ReminderRules.afterFiring(reminder("2026-10-01T12:00:00Z"), at("2026-10-01T12:00:03Z"), shanghai)
        assertEquals(TaskStatus.DONE, oneOff.status)
    }

    @Test
    fun describeSaysTodayTomorrowOrTheDate() {
        val now = at("2026-09-29T02:00:00Z") // 上海 09-29 10:00
        assertEquals("今天 20:00", ReminderRules.describe(at("2026-09-29T12:00:00Z"), now, shanghai))
        assertEquals("明天 09:00", ReminderRules.describe(at("2026-09-30T01:00:00Z"), now, shanghai))
        assertEquals("10月5日 09:00", ReminderRules.describe(at("2026-10-05T01:00:00Z"), now, shanghai))
        assertEquals("2027年1月3日 09:00", ReminderRules.describe(at("2027-01-03T01:00:00Z"), now, shanghai))
    }

    @Test
    fun describeRepeatNamesTheWeekday() {
        assertEquals("", ReminderRules.describeRepeat(Repeat.NONE, null, shanghai))
        assertEquals("每天", ReminderRules.describeRepeat(Repeat.DAILY, null, shanghai))
        assertEquals("每周四", ReminderRules.describeRepeat(Repeat.WEEKLY, at("2026-10-01T12:00:00Z"), shanghai))
    }

    @Test
    fun endOfDayIsMidnightInTheUsersZone() {
        assertEquals(at("2026-09-29T16:00:00Z"), ReminderRules.endOfDay(at("2026-09-29T02:00:00Z"), shanghai))
    }
}
