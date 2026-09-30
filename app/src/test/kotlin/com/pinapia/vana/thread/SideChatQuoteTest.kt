package com.pinapia.vana.thread

import com.pinapia.vana.agent.HistoryMarkers
import com.pinapia.vana.session.ChatMessage
import com.pinapia.vana.session.ToolCallRecord
import com.pinapia.vana.vision.ChatAttachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 两条线之间搬一段话:搬的是可见正文,不是 transcript;给模型的那段话说明来历。口径同 iOS 的 `SideChatTests`。 */
class SideChatQuoteTest {
    private fun answer(text: String = "住四条附近方便。") = ChatMessage(
        role = ChatMessage.Role.ASSISTANT,
        text = text,
        reasoning = "先想想交通",
        toolCalls = listOf(
            ToolCallRecord(id = "1", name = "web_search", input = "{}", output = "…"),
            ToolCallRecord(id = "2", name = "fetch_url", input = "{}", output = "…"),
            ToolCallRecord(id = "3", name = "web_search", input = "{}", output = "…"),
        ),
        attachments = listOf(ChatAttachment(imageFileName = "photo.jpg")),
    )

    private val question = ChatMessage(role = ChatMessage.Role.USER, text = "  十月去京都住哪 ")

    @Test
    fun aQuoteCarriesTheQuestionAndTheVisibleAnswerNotTheTranscript() {
        val seed = SideChatQuote.seed(question, answer())
        assertEquals(ChatMessage.Origin.FROM_MAIN, seed.origin)
        assertEquals(ChatMessage.Role.ASSISTANT, seed.role)
        assertEquals("住四条附近方便。", seed.text)
        assertEquals("十月去京都住哪", seed.provenance?.question)
        assertEquals(listOf("web_search", "fetch_url"), seed.provenance?.toolNames)
        // tool_call 配对、思考、原图,随便断一样就是一个 400。照片文件归原来那条线。
        assertTrue(seed.toolCalls.isEmpty())
        assertTrue(seed.reasoning.isEmpty())
        assertTrue(seed.storedTurn.exactTranscript.messages.isEmpty())
        assertTrue(seed.attachments.isEmpty())

        val capped = SideChatQuote.broughtBack(answer("长".repeat(5_000)), sideChatTitle = "京都")
        assertEquals(SideChatQuote.MAX_CHARACTERS + 1, capped.text.length)
        assertTrue(capped.text.endsWith("…"))
        assertEquals(ChatMessage.Origin.FROM_SIDE_CHAT, capped.origin)
        assertEquals("京都", capped.provenance?.sideChatTitle)
        assertTrue(SideChatQuote.label(capped)!!.contains("京都"))
    }

    @Test
    fun onlyAFinishedModelAnswerCanBeQuoted() {
        assertTrue(SideChatQuote.canQuote(ChatMessage(role = ChatMessage.Role.ASSISTANT, text = "答")))
        assertFalse(SideChatQuote.canQuote(ChatMessage(role = ChatMessage.Role.USER, text = "问")))
        assertFalse(SideChatQuote.canQuote(ChatMessage(role = ChatMessage.Role.ASSISTANT, text = "已停止", textIsPlaceholder = true)))
        assertFalse(
            SideChatQuote.canQuote(ChatMessage(role = ChatMessage.Role.ASSISTANT, text = "提醒", origin = ChatMessage.Origin.REMINDER)),
        )
        assertFalse(SideChatQuote.canQuote(ChatMessage(role = ChatMessage.Role.ASSISTANT, text = "", errorDescription = "坏了")))
        // 带过来的那一段本身不能再搬:它已经是一段引用了。
        assertFalse(SideChatQuote.canQuote(SideChatQuote.seed(question, answer())))
    }

    /** 给模型的那段话折进下一条用户消息开头,说明来历,不混进「Vana 之前主动说过」。 */
    @Test
    fun aQuoteFoldsIntoTheNextUserMessageWithItsOwnFraming() {
        val reminder = ChatMessage(role = ChatMessage.Role.ASSISTANT, text = "该交房租了", origin = ChatMessage.Origin.REMINDER)
        val seed = SideChatQuote.seed(question, answer())
        val user = ChatMessage(role = ChatMessage.Role.USER, text = "预算三万呢")
        val history = HistoryMarkers.apply(listOf(reminder, seed, user))
        assertEquals(1, history.size)
        val text = history.single().text
        assertTrue(text.contains("这条侧聊接着主对话里的这一段开始"))
        assertTrue(text.contains("用户当时问：「十月去京都住哪」"))
        assertTrue(text.contains("（当时查过：web_search、fetch_url）"))
        assertTrue(text.contains("住四条附近方便。"))
        assertTrue(text.contains("（Vana 之前主动说过：该交房租了）"))
        assertFalse(text.contains("主动说过：住四条"))
        assertTrue(text.endsWith("预算三万呢"))

        val back = SideChatQuote.broughtBack(answer("定了，住四条。"), sideChatTitle = "京都")
        val main = HistoryMarkers.apply(listOf(back, ChatMessage(role = ChatMessage.Role.USER, text = "好")))
        assertTrue(main.single().text.contains("用户从侧聊「京都」里带回来一段你在那里说过的话"))
        assertTrue(main.single().text.contains("定了，住四条。"))
    }
}
