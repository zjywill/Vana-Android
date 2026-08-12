package com.pinapia.vana.recall

import com.pinapia.vana.session.ChatMessage

object SessionRecallTrigger {
    private val phrases = listOf(
        "上次", "上回", "上一次", "那次", "上个月我们", "之前", "以前", "早先",
        "说过", "提过", "提到过", "聊过", "讨论过", "分析过", "问过",
        "我们聊", "我们说", "我们讨论", "我们分析", "你说的", "你提的",
        "还记得", "记不记得", "记得吗",
        "last time", "earlier", "we talked", "we discussed", "you said", "you mentioned", "remember",
    )

    fun unlocksRecall(inMessages: List<ChatMessage>): Boolean =
        inMessages.any { it.role == ChatMessage.Role.USER && mentionsPast(it.text) }

    fun mentionsPast(text: String): Boolean {
        val lowered = text.lowercase()
        return phrases.any { lowered.contains(it) }
    }
}
