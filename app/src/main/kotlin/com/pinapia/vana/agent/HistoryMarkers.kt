package com.pinapia.vana.agent

import com.pinapia.vana.agentruntime.AgentChatMessageDTO
import com.pinapia.vana.session.ChatMessage
import com.pinapia.vana.thread.SideChatQuote
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 单线程里消息跨天:模型必须知道「昨天说的」是哪一次。
 *
 * 相邻两条隔得够久,就在后一条用户消息前面补一行确定性的时间标记。它由每条消息存下来的
 * `createdAt` 算出,同一段历史每次算出来都一样——所以只会在窗口后面追加,不会让请求前缀变来变去。
 */
object HistoryMarkers {
    /** 隔多久算「隔了一阵」。 */
    val GAP: Duration = Duration.ofHours(6)

    fun marker(previous: Instant, current: Instant, zone: ZoneId = ZoneId.systemDefault()): String? {
        val gap = Duration.between(previous, current)
        if (gap < GAP) return null
        val stamp = DateTimeFormatter.ofPattern("M月d日 HH:mm").withZone(zone).format(current)
        val hours = gap.toHours()
        val since = if (hours < 48) "${hours} 小时" else "${gap.toDays()} 天"
        return "——（$stamp，距上一条约 $since）——"
    }

    /**
     * 把线程里的消息变成发给模型的历史。两件事:
     *
     * - 隔得久的用户消息前面补时间标记;
     * - **主动消息**(Vana 自己先开口:check-in、回头看的结论……)不作为独立的助手消息发出去,而是折进
     *   **下一条用户消息**的开头(「Vana 之前主动说过：……」)。请求里助手和用户消息严格交替——有的 provider
     *   (比如 Anthropic 协议)不接受连着两条助手消息,也不接受以助手消息开头。
     *   从另一条线上搬过来的那两种(侧聊的开头、带回主对话的)各自有一段说明来历的话
     *   ([SideChatQuote.modelNote]),不混进「主动说过」那一句里。
     */
    fun apply(messages: List<ChatMessage>, zone: ZoneId = ZoneId.systemDefault()): List<AgentChatMessageDTO> {
        var previous: ChatMessage? = null
        val proactive = ArrayList<String>()
        val quoted = ArrayList<String>()
        val out = ArrayList<AgentChatMessageDTO>()
        for (message in messages) {
            val before = previous
            previous = message
            if (message.role == ChatMessage.Role.ASSISTANT && message.isProactive) {
                val note = SideChatQuote.modelNote(message)
                if (note != null) {
                    quoted += note
                } else {
                    message.text.trim().takeIf { it.isNotEmpty() }?.let { proactive += it }
                }
                continue
            }
            val dto = message.toDTO()
            if (message.role != ChatMessage.Role.USER) {
                out += dto
                continue
            }
            val prefix = StringBuilder()
            if (before != null) {
                marker(
                    previous = Instant.ofEpochMilli(before.createdAt.toEpochMilliseconds()),
                    current = Instant.ofEpochMilli(message.createdAt.toEpochMilliseconds()),
                    zone = zone,
                )?.let { prefix.append(it).append('\n') }
            }
            quoted.forEach { prefix.append(it).append('\n') }
            quoted.clear()
            if (proactive.isNotEmpty()) {
                prefix.append("（Vana 之前主动说过：").append(proactive.joinToString("；")).append("）\n")
                proactive.clear()
            }
            out += if (prefix.isEmpty()) dto else dto.copy(text = prefix.toString() + dto.text)
        }
        return out
    }
}
