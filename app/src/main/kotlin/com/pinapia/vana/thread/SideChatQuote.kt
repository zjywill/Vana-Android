package com.pinapia.vana.thread

import com.pinapia.vana.session.ChatMessage
import com.pinapia.vana.ui.L10n
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * 在主对话和侧聊之间搬一段话。纯函数。
 *
 * 两个方向:
 * - **在侧聊里接着聊**:主对话里某一问一答,作为一条新侧聊的开头([ChatMessage.Origin.FROM_MAIN])。
 * - **带回主对话**:侧聊里他挑的一段回复,原样追加到主对话([ChatMessage.Origin.FROM_SIDE_CHAT])。
 *
 * **搬的是文字,不是 transcript。** 原样拷过去的话,`tool_call` 和结果的配对、DeepSeek 的 `reasoning_content`、
 * 随行原图,随便断一样就是一个 400,而且那条线从此发不出去。所以只带可见的正文,工具只留名字(同召回读回来的
 * 原文);照片不带——照片文件归原来那条线,两条线引用同一张图的话,删其中一条就会把另一条的图删掉。
 *
 * **带回来不花一次调用去总结**:总结会漂,还多付一次钱;要带什么由他自己挑那一条。
 */
object SideChatQuote {
    /** 搬过去的正文最长多少字。它要进另一条线的窗口,一段几千字的长回复原样搬过去,等于一次性占掉那边窗口的一大块。 */
    const val MAX_CHARACTERS = 3_000

    /** 这一条能不能搬:模型真的写的、写完了的、不是主动消息的回复。 */
    fun canQuote(message: ChatMessage): Boolean =
        message.role == ChatMessage.Role.ASSISTANT &&
            !message.textIsPlaceholder &&
            message.errorDescription == null &&
            message.text.isNotBlank() &&
            !message.isProactive &&
            !message.isQueued

    /** 侧聊的开头。[question] 是这段回答上面那句提问(找不到就是 null)。 */
    fun seed(question: ChatMessage?, answer: ChatMessage, now: Instant = Clock.System.now()): ChatMessage {
        val asked = question?.text?.trim()?.takeIf { it.isNotEmpty() }
        return ChatMessage(
            role = ChatMessage.Role.ASSISTANT,
            text = capped(answer.text),
            createdAt = now,
            origin = ChatMessage.Origin.FROM_MAIN,
            provenance = ChatMessage.Provenance(
                question = asked,
                toolNames = toolNames(answer),
                date = answer.createdAt,
            ),
        )
    }

    /** 带回主对话的那一条。 */
    fun broughtBack(answer: ChatMessage, sideChatTitle: String, now: Instant = Clock.System.now()): ChatMessage =
        ChatMessage(
            role = ChatMessage.Role.ASSISTANT,
            text = capped(answer.text),
            createdAt = now,
            origin = ChatMessage.Origin.FROM_SIDE_CHAT,
            provenance = ChatMessage.Provenance(
                sideChatTitle = sideChatTitle,
                toolNames = toolNames(answer),
                date = answer.createdAt,
            ),
        )

    /** 气泡顶上那一行小字。带回来的那条要写出是哪条侧聊:主对话里可能摆着好几条侧聊带回来的话。 */
    fun label(message: ChatMessage): String? = when (message.origin) {
        ChatMessage.Origin.FROM_MAIN -> L10n.text("从主对话接着聊", "Continued from the main conversation")
        ChatMessage.Origin.FROM_SIDE_CHAT -> {
            val title = message.provenance?.sideChatTitle?.takeIf { it.isNotEmpty() }
            if (title != null) {
                L10n.text("从侧聊「$title」带回来的", "Brought back from side chat \"$title\"")
            } else {
                L10n.text("从侧聊带回来的", "Brought back from a side chat")
            }
        }
        else -> null
    }

    /**
     * 给模型看的那一段(`HistoryMarkers` 把它折进下一条用户消息开头)。不是这两种就返回 null。
     *
     * 要说清两件事:这段话从哪儿来,以及它**不是对上一句的回答**——否则模型读到的是自己突然说了一段和上下文
     * 不相干的话。
     */
    fun modelNote(message: ChatMessage): String? {
        val text = message.text.trim()
        if (text.isEmpty()) return null
        val tools = message.provenance?.toolNames.orEmpty()
        val checked = if (tools.isEmpty()) "" else "（当时查过：${tools.joinToString("、")}）"
        return when (message.origin) {
            ChatMessage.Origin.FROM_MAIN -> {
                val asked = message.provenance?.question?.let { "用户当时问：「$it」\n" }.orEmpty()
                "（这条侧聊接着主对话里的这一段开始。${asked}你当时答$checked：\n$text\n）"
            }
            ChatMessage.Origin.FROM_SIDE_CHAT -> {
                val title = message.provenance?.sideChatTitle?.let { "「$it」" }.orEmpty()
                "（用户从侧聊${title}里带回来一段你在那里说过的话$checked：\n$text\n）"
            }
            else -> null
        }
    }

    private fun capped(text: String): String {
        val trimmed = text.trim()
        if (trimmed.length <= MAX_CHARACTERS) return trimmed
        return trimmed.take(MAX_CHARACTERS) + "…"
    }

    private fun toolNames(message: ChatMessage): List<String> = message.toolCalls.map { it.name }.distinct()
}
