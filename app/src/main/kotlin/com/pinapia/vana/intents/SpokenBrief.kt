package com.pinapia.vana.intents

import com.pinapia.vana.Features
import com.pinapia.vana.health.DayActivity
import com.pinapia.vana.health.HealthSituation
import com.pinapia.vana.health.HealthStore
import com.pinapia.vana.health.HealthTrigger
import com.pinapia.vana.health.NightSleep
import com.pinapia.vana.health.WorkoutItem
import com.pinapia.vana.tenant.TenantScope
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Assistant / 快捷方式念出来的那一两句话。
 *
 * 和 app 里的对话是两套东西:全在本地算完,不联网、不看 API key。
 */
object SpokenBrief {
    suspend fun todayStatus(healthStore: HealthStore): String {
        if (!Features.HEALTH_CONNECT) return deferredLine()
        blockedReason(healthStore)?.let { return it }
        return try {
            healthStore.withOwnerAccess {
                val today = dailyActivity(1).lastOrNull()
                val situation = HealthSituation.detect(
                    healthStore = this,
                    interests = TenantScope.ownerStores.sessions.interests(),
                )
                todayLine(activity = today, situation = situation)
            }
        } catch (error: Throwable) {
            failureLine(error)
        }
    }

    suspend fun lastNightSleep(healthStore: HealthStore): String {
        if (!Features.HEALTH_CONNECT) return deferredLine()
        blockedReason(healthStore)?.let { return it }
        return try {
            healthStore.withOwnerAccess {
                sleepLine(nights = sleepSummary(14))
            }
        } catch (error: Throwable) {
            failureLine(error)
        }
    }

    suspend fun lastWorkout(healthStore: HealthStore): String {
        if (!Features.HEALTH_CONNECT) return deferredLine()
        blockedReason(healthStore)?.let { return it }
        return try {
            healthStore.withOwnerAccess {
                workoutLine(sessions = workouts(14))
            }
        } catch (error: Throwable) {
            failureLine(error)
        }
    }

    fun todayLine(activity: DayActivity?, situation: HealthSituation): String {
        val headline = situation.triggers.firstOrNull { isSpeakable(it) }
        return activityLine(activity, situation) +
            (headline?.let { "${it.brief}。" } ?: "其他几项都在最近的常态范围里。")
    }

    fun sleepLine(
        nights: List<NightSleep>,
        now: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val sorted = nights.sortedBy { it.night }
        val latest = sorted.lastOrNull() ?: return "最近两周都没有睡眠记录。"

        val tonight = LocalDate.ofInstant(now, zone)
        val nightsAgo = ChronoUnit.DAYS.between(latest.night, tonight).toInt()
        if (nightsAgo > 1) {
            return "昨晚没有睡眠记录，多半是没戴设备。最近一次是 ${dayLabel(latest.night)}，" +
                "睡了 ${spokenDuration(latest.asleepSeconds)}。"
        }

        var line = "昨晚睡了 ${spokenDuration(latest.asleepSeconds)}"
        val bedtime = latest.bedtime
        val wake = latest.wake
        if (bedtime != null && wake != null) {
            line += "，${clockLabel(bedtime, zone)} 睡的，${clockLabel(wake, zone)} 醒"
        }
        line += "。"

        val earlier = sorted.dropLast(1).map { it.asleepSeconds }
        if (earlier.size >= 3) {
            val average = earlier.average()
            val delta = ((latest.asleepSeconds - average) / 60).roundToInt()
            line += if (abs(delta) >= 20) {
                "比最近几晚平均${if (delta > 0) "多" else "少"} ${abs(delta)} 分钟。"
            } else {
                "和最近几晚差不多。"
            }
        }
        return line
    }

    fun workoutLine(sessions: List<WorkoutItem>, now: Instant = Instant.now()): String {
        val latest = sessions.maxByOrNull { it.startTime }
            ?: return "最近两周没有锻炼记录。"

        val ended = latest.endTime
        var line = "${elapsedLabel(since = ended, now = now)}那次${latest.typeName}，" +
            "练了 ${spokenDuration(latest.durationSeconds)}"
        latest.distanceKm?.takeIf { it > 0 }?.let { line += "，${oneDecimal(it)} 公里" }
        latest.averageHeartRate?.let { line += "，平均心率 ${it.roundToInt()}" }
        latest.activeEnergyKcal?.let { line += "，消耗 ${it.roundToInt()} 千卡" }
        line += "。"

        val peers = sessions.filter { it.typeName == latest.typeName && it.startTime != latest.startTime }
        if (peers.size >= 3) {
            val average = peers.map { it.durationSeconds }.average()
            val delta = ((latest.durationSeconds - average) / 60).roundToInt()
            line += if (abs(delta) >= 5) {
                "比最近两周同类训练的平均时长${if (delta > 0) "长" else "短"} ${abs(delta)} 分钟。"
            } else {
                "和最近两周的同类训练时长差不多。"
            }
        }
        return line
    }

    private fun activityLine(day: DayActivity?, situation: HealthSituation): String {
        if (day == null) return "今天还没读到活动量。"
        if (day.steps <= 0) return "今天到现在还没有步数记录。"
        var line = "今天走了 ${day.steps.roundToInt()} 步"
        day.exerciseMinutes?.takeIf { it >= 1 }?.let {
            line += "，运动 ${it.roundToInt()} 分钟"
        }
        if (situation.triggers.any { it is HealthTrigger.BigActivityDay }) {
            line += "，明显多于平常"
        }
        return "$line。"
    }

    private fun isSpeakable(trigger: HealthTrigger): Boolean = when (trigger) {
        is HealthTrigger.BigActivityDay,
        is HealthTrigger.NoStepsToday,
        is HealthTrigger.WeeklyReview,
        -> false
        else -> true
    }

    private fun deferredLine(): String =
        "本机健康数据暂未接入。打开 Vana 可以拍照化验单，或直接聊症状与用药。"

    private suspend fun blockedReason(healthStore: HealthStore): String? =
        when (healthStore.readAccess()) {
            HealthStore.ReadAccess.READY -> null
            HealthStore.ReadAccess.NOT_REQUESTED ->
                "还没拿到健康数据的读取权限。先打开 Vana 授权一次，之后就能直接问我了。"
            HealthStore.ReadAccess.UNAVAILABLE ->
                "这台设备上没有健康数据。"
        }

    private fun failureLine(error: Throwable): String =
        if (error.message?.contains("locked", ignoreCase = true) == true) {
            "手机锁着的时候读不到健康数据，解锁之后再问我一次。"
        } else {
            "读健康数据的时候出了点问题，打开 Vana 看看吧。"
        }

    private fun spokenDuration(seconds: Double): String {
        val total = maxOf((seconds / 60).roundToInt(), 0)
        val hours = total / 60
        val minutes = total % 60
        return when {
            hours == 0 -> "$minutes 分钟"
            minutes == 0 -> "$hours 小时"
            else -> "$hours 小时 $minutes 分"
        }
    }

    private fun clockLabel(instant: Instant, zone: ZoneId): String {
        val local = instant.atZone(zone)
        return if (local.minute == 0) "${local.hour} 点" else "${local.hour} 点 ${local.minute} 分"
    }

    private fun dayLabel(date: LocalDate): String = "${date.monthValue} 月 ${date.dayOfMonth} 日那晚"

    private fun elapsedLabel(since: Instant, now: Instant): String {
        val minutes = ChronoUnit.MINUTES.between(since, now).toInt()
        return when {
            minutes < 10 -> "刚刚"
            minutes < 60 -> "$minutes 分钟前"
            minutes < 60 * 24 -> "${minutes / 60} 小时前"
            minutes < 60 * 48 -> "昨天"
            else -> "${minutes / (60 * 24)} 天前"
        }
    }

    private fun oneDecimal(value: Double): String =
        if (value == value.roundToInt().toDouble()) value.roundToInt().toString()
        else String.format("%.1f", value)
}
