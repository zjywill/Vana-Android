package com.pinapia.vana.session

import com.pinapia.vana.vision.ChatAttachment
import kotlinx.datetime.Clock
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionTitleTest {
    @Test
    fun photoOnlySessionGetsATitle() {
        val session = ChatSession(
            messages = listOf(
                ChatMessage(
                    role = ChatMessage.Role.USER,
                    text = "",
                    attachments = listOf(ChatAttachment(text = "布洛芬缓释胶囊")),
                ),
            ),
        )
        assertEquals("照片", session.title)
    }

    @Test
    fun emptyFallsBackToNewChat() {
        assertEquals("新对话", ChatSession().title)
    }

    @Test
    fun topicNameWhenEmptyWithTopic() {
        val session = ChatSession(topicId = "sleep")
        assertEquals("睡眠", session.title)
    }

    @Test
    fun firstUserLineIsTitle() {
        val session = ChatSession(
            messages = listOf(
                ChatMessage(role = ChatMessage.Role.USER, text = "昨晚睡得怎么样？"),
            ),
            updatedAt = Clock.System.now(),
        )
        assertEquals("昨晚睡得怎么样？", session.title)
    }
}
