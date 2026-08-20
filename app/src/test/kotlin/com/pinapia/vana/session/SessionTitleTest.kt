package com.pinapia.vana.session

import com.pinapia.vana.vision.ChatAttachment
import kotlinx.datetime.Clock
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class SessionTitleTest {
    @Test
    fun photoOnlySessionGetsATitle() {
        withLocale(Locale.SIMPLIFIED_CHINESE) {
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
    }

    @Test
    fun emptyFallsBackToNewChat() {
        withLocale(Locale.SIMPLIFIED_CHINESE) {
            assertEquals("新对话", ChatSession().title)
        }
    }

    @Test
    fun firstUserLineIsTitle() {
        withLocale(Locale.SIMPLIFIED_CHINESE) {
            val session = ChatSession(
                messages = listOf(
                    ChatMessage(role = ChatMessage.Role.USER, text = "昨晚睡得怎么样？"),
                ),
                updatedAt = Clock.System.now(),
            )
            assertEquals("昨晚睡得怎么样？", session.title)
        }
    }

    @Test
    fun emptyAndPhotoTitlesFollowEnglishUi() {
        withLocale(Locale.ENGLISH) {
            assertEquals("New conversation", ChatSession().title)
            val session = ChatSession(
                messages = listOf(
                    ChatMessage(
                        role = ChatMessage.Role.USER,
                        text = "",
                        attachments = listOf(ChatAttachment(text = "Ibuprofen")),
                    ),
                ),
            )
            assertEquals("Photo", session.title)
        }
    }

    private inline fun withLocale(locale: Locale, block: () -> Unit) {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(locale)
            block()
        } finally {
            Locale.setDefault(previous)
        }
    }
}
