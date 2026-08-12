package com.pinapia.vana.chat

import com.pinapia.vana.session.SessionSummary
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

enum class SessionTimeBucket(val title: String) {
    TODAY("今天"),
    YESTERDAY("昨天"),
    LAST_WEEK("最近 7 天"),
    EARLIER("更早"),
}

data class SessionTimeGroup(
    val bucket: SessionTimeBucket,
    val sessions: List<SessionSummary>,
)

object SessionTimeSection {
    fun group(summaries: List<SessionSummary>, nowMillis: Long = System.currentTimeMillis()): List<SessionTimeGroup> {
        val buckets = linkedMapOf<SessionTimeBucket, MutableList<SessionSummary>>()
        for (summary in summaries) {
            val bucket = bucketFor(summary.updatedAt.toEpochMilliseconds(), nowMillis)
            buckets.getOrPut(bucket) { mutableListOf() } += summary
        }
        return SessionTimeBucket.entries.mapNotNull { bucket ->
            val list = buckets[bucket] ?: return@mapNotNull null
            if (list.isEmpty()) null else SessionTimeGroup(bucket, list)
        }
    }

    fun rowTimeLabel(updatedAtMillis: Long, nowMillis: Long = System.currentTimeMillis()): String {
        val bucket = bucketFor(updatedAtMillis, nowMillis)
        val cal = Calendar.getInstance().apply { timeInMillis = updatedAtMillis }
        val time = SimpleDateFormat("H:mm", Locale.CHINA).format(Date(updatedAtMillis))
        return when (bucket) {
            SessionTimeBucket.TODAY, SessionTimeBucket.YESTERDAY -> time
            SessionTimeBucket.LAST_WEEK -> {
                val weekday = SimpleDateFormat("E", Locale.CHINA).format(Date(updatedAtMillis))
                "$weekday $time"
            }
            SessionTimeBucket.EARLIER -> {
                val now = Calendar.getInstance().apply { timeInMillis = nowMillis }
                val pattern = if (cal.get(Calendar.YEAR) == now.get(Calendar.YEAR)) "M/d" else "y/M/d"
                SimpleDateFormat(pattern, Locale.CHINA).format(Date(updatedAtMillis))
            }
        }
    }

    private fun bucketFor(targetMillis: Long, nowMillis: Long): SessionTimeBucket {
        val target = startOfDay(targetMillis)
        val today = startOfDay(nowMillis)
        val days = TimeUnit.MILLISECONDS.toDays(today - target)
        return when {
            days <= 0 -> SessionTimeBucket.TODAY // 未来时间也算今天
            days == 1L -> SessionTimeBucket.YESTERDAY
            days in 2..6 -> SessionTimeBucket.LAST_WEEK
            else -> SessionTimeBucket.EARLIER
        }
    }

    private fun startOfDay(millis: Long): Long {
        return Calendar.getInstance().apply {
            timeInMillis = millis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
