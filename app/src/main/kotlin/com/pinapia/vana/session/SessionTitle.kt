package com.pinapia.vana.session

import com.pinapia.vana.ui.L10n
import kotlinx.datetime.Instant
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 会话在列表上叫什么。
 *
 * 界面走 [ChatSession.title],索引走 [SessionIndexEntry]——必须共用这一份算法。
 */
object SessionTitle {
    fun make(
        threadId: String?,
        threadTitle: String?,
        firstUserText: String?,
        firstUserHasAttachments: Boolean = false,
        createdAt: Instant,
    ): String {
        // 延续线用线程名。
        SessionThread.parse(threadId)?.let { thread ->
            val name = threadTitle?.trim().orEmpty()
            val base = if (name.isNotEmpty()) name else thread.title
            return L10n.text(
                "$base · ${dateLabel(createdAt)}起",
                "$base · since ${dateLabel(createdAt)}",
            )
        }

        val first = firstUserText?.trim().orEmpty()
        if (first.isEmpty()) {
            if (firstUserHasAttachments) return L10n.text("照片", "Photo")
            return L10n.text("新对话", "New conversation")
        }
        val firstLine = first.lineSequence().firstOrNull().orEmpty().ifEmpty { first }
        return if (firstLine.length <= 24) firstLine else firstLine.take(24) + "…"
    }

    fun dateLabel(instant: Instant): String {
        val locale = Locale.getDefault()
        return if (locale.language == "en") {
            SimpleDateFormat("MMM d", locale).format(Date(instant.toEpochMilliseconds()))
        } else {
            val cal = Calendar.getInstance().apply { timeInMillis = instant.toEpochMilliseconds() }
            "${cal.get(Calendar.MONTH) + 1}月${cal.get(Calendar.DAY_OF_MONTH)}日"
        }
    }
}
