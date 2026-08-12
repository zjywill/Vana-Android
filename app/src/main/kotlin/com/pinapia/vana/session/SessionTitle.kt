package com.pinapia.vana.session

import com.pinapia.vana.chat.ChatTopics
import kotlinx.datetime.Instant
import java.util.Calendar

/**
 * 会话在列表上叫什么。
 *
 * 界面走 [ChatSession.title],索引走 [SessionIndexEntry]——必须共用这一份算法。
 */
object SessionTitle {
    fun make(
        threadId: String?,
        threadTitle: String?,
        topicId: String?,
        firstUserText: String?,
        firstUserHasAttachments: Boolean = false,
        createdAt: Instant,
    ): String {
        // 延续线用线程名。
        SessionThread.parse(threadId)?.let { thread ->
            val name = threadTitle?.trim().orEmpty()
            val base = if (name.isNotEmpty()) name else thread.title
            return "$base · ${dateLabel(createdAt)}起"
        }

        val first = firstUserText?.trim().orEmpty()
        if (first.isEmpty()) {
            if (firstUserHasAttachments) return "照片"
            return ChatTopics.find(topicId)?.name ?: "新对话"
        }
        val firstLine = first.lineSequence().firstOrNull().orEmpty().ifEmpty { first }
        return if (firstLine.length <= 24) firstLine else firstLine.take(24) + "…"
    }

    fun dateLabel(instant: Instant): String {
        val cal = Calendar.getInstance().apply { timeInMillis = instant.toEpochMilliseconds() }
        return "${cal.get(Calendar.MONTH) + 1}月${cal.get(Calendar.DAY_OF_MONTH)}日"
    }
}
