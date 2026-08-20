package com.pinapia.vana.chat

import com.pinapia.vana.session.SessionSummary
import com.pinapia.vana.ui.L10n
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

enum class SessionTimeBucket {
    TODAY,
    YESTERDAY,
    LAST_WEEK,
    EARLIER;

    val title: String
        get() = when (this) {
            TODAY -> L10n.text("今天", "Today")
            YESTERDAY -> L10n.text("昨天", "Yesterday")
            LAST_WEEK -> L10n.text("最近 7 天", "Last 7 days")
            EARLIER -> L10n.text("更早", "Earlier")
        }
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
        val locale = Locale.getDefault()
        val time = SimpleDateFormat(if (locale.language == "en") "h:mm a" else "H:mm", locale)
            .format(Date(updatedAtMillis))
        return when (bucket) {
            SessionTimeBucket.TODAY, SessionTimeBucket.YESTERDAY -> time
            SessionTimeBucket.LAST_WEEK -> {
                val weekday = SimpleDateFormat("E", locale).format(Date(updatedAtMillis))
                "$weekday $time"
            }
            SessionTimeBucket.EARLIER -> {
                val now = Calendar.getInstance().apply { timeInMillis = nowMillis }
                val pattern = if (locale.language == "en") {
                    if (cal.get(Calendar.YEAR) == now.get(Calendar.YEAR)) "MMM d" else "MMM d, y"
                } else {
                    if (cal.get(Calendar.YEAR) == now.get(Calendar.YEAR)) "M/d" else "y/M/d"
                }
                SimpleDateFormat(pattern, locale).format(Date(updatedAtMillis))
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
