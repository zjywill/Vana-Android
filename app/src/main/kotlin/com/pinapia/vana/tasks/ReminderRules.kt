package com.pinapia.vana.tasks

import java.time.DayOfWeek
import java.time.Instant as JInstant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.datetime.Instant

/**
 * 提醒的时间规则。纯函数,没有 Android:到点怎么排、重复提醒的下一次在哪、怎么念给用户听。
 */
object ReminderRules {
    /** 一次最多这么多条进行中的提醒。再多就是把提醒当成了别的东西。 */
    const val MAX_ACTIVE_REMINDERS = 50

    /** 最远能约到多久以后。 */
    const val MAX_HORIZON_DAYS = 366L

    private fun Instant.toJava(): JInstant = JInstant.ofEpochMilli(toEpochMilliseconds())
    private fun JInstant.toK(): Instant = Instant.fromEpochMilliseconds(toEpochMilli())

    /**
     * 解析模型给的本地时间:`2026-10-01T20:00`、`2026-10-01 20:00`、`2026-10-01T20:00:00`。
     * 没有时区——这是用户当地的墙上时间,按 [zone] 落到时间轴上。
     */
    fun parseLocal(text: String, zone: ZoneId): Instant? {
        val cleaned = text.trim().replace(' ', 'T')
        val parsed = runCatching { LocalDateTime.parse(cleaned) }.getOrNull()
            ?: runCatching { LocalDateTime.parse(cleaned + ":00") }.getOrNull()
            ?: return null
        return parsed.atZone(zone).toInstant().toK()
    }

    /** 重复提醒**下一次**响的时间(严格晚于 [after]),保持墙上时间不变。不重复的返回 null。 */
    fun nextOccurrence(dueAt: Instant, repeat: Repeat, after: Instant, zone: ZoneId): Instant? {
        if (repeat == Repeat.NONE) return null
        val base = ZonedDateTime.ofInstant(dueAt.toJava(), zone)
        val floor = ZonedDateTime.ofInstant(after.toJava(), zone)
        var next = base
        var guard = 0
        while (!next.isAfter(floor) && guard < 4000) {
            next = when (repeat) {
                Repeat.DAILY -> base.plusDays(dayOffset(base, next) + 1)
                Repeat.WEEKLY -> base.plusWeeks(weekOffset(base, next) + 1)
                Repeat.NONE -> return null
            }
            guard++
        }
        return next.toInstant().toK()
    }

    private fun dayOffset(base: ZonedDateTime, current: ZonedDateTime): Long =
        java.time.temporal.ChronoUnit.DAYS.between(base.toLocalDate(), current.toLocalDate())

    private fun weekOffset(base: ZonedDateTime, current: ZonedDateTime): Long =
        java.time.temporal.ChronoUnit.WEEKS.between(base.toLocalDate(), current.toLocalDate())

    /** 该提醒现在应当在哪个时间响。已经过去了、又不重复的返回 null(那是错过的,由调用方决定是补响还是丢)。 */
    fun scheduledFor(task: Task, now: Instant, zone: ZoneId): Instant? {
        val due = task.dueAt ?: return null
        if (due > now) return due
        return nextOccurrence(due, task.repeat, now, zone)
    }

    /** 响过之后这条提醒变成什么:重复的挪到下一次,不重复的记为完成。 */
    fun afterFiring(task: Task, firedAt: Instant, zone: ZoneId): Task {
        val next = task.dueAt?.let { nextOccurrence(it, task.repeat, firedAt, zone) }
        return if (next != null) {
            task.copy(dueAt = next, status = TaskStatus.QUEUED)
        } else {
            task.copy(status = TaskStatus.DONE)
        }
    }

    /**
     * 念给用户听:今天 20:00 / 明天 09:00 / 10月5日 09:00 / 2027年1月3日 09:00。
     * 给模型的回答用默认的中文(模型看到的标签固定);界面里按当前语言传 [english]。
     */
    fun describe(at: Instant, now: Instant, zone: ZoneId, english: Boolean = false): String {
        val time = DateTimeFormatter.ofPattern("HH:mm").withZone(zone).format(at.toJava())
        val date = LocalDate.ofInstant(at.toJava(), zone)
        val today = LocalDate.ofInstant(now.toJava(), zone)
        val day = if (english) {
            when {
                date == today -> "Today"
                date == today.plusDays(1) -> "Tomorrow"
                date == today.minusDays(1) -> "Yesterday"
                date.year == today.year -> "${date.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH)} ${date.dayOfMonth}"
                else -> "${date.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH)} ${date.dayOfMonth}, ${date.year}"
            }
        } else {
            when {
                date == today -> "今天"
                date == today.plusDays(1) -> "明天"
                date == today.minusDays(1) -> "昨天"
                date.year == today.year -> "${date.monthValue}月${date.dayOfMonth}日"
                else -> "${date.year}年${date.monthValue}月${date.dayOfMonth}日"
            }
        }
        return "$day $time"
    }

    fun describeRepeat(repeat: Repeat, dueAt: Instant?, zone: ZoneId, english: Boolean = false): String = when (repeat) {
        Repeat.NONE -> ""
        Repeat.DAILY -> if (english) "Daily" else "每天"
        Repeat.WEEKLY -> {
            val weekday = dueAt?.let { ZonedDateTime.ofInstant(it.toJava(), zone).dayOfWeek } ?: DayOfWeek.MONDAY
            if (english) {
                "Every " + weekday.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH)
            } else {
                "每周" + arrayOf("一", "二", "三", "四", "五", "六", "日")[weekday.value - 1]
            }
        }
    }

    /** 今天(按 [zone])结束的那一刻。「今天」卡片按它划线。 */
    fun endOfDay(now: Instant, zone: ZoneId): Instant {
        val today = LocalDate.ofInstant(now.toJava(), zone)
        return today.plusDays(1).atStartOfDay(zone).toInstant().toK()
    }
}
