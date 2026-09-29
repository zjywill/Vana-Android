package com.pinapia.vana.thread

import com.pinapia.vana.agentruntime.WindowPolicy
import com.pinapia.vana.session.ChatMessage

/**
 * 把 [WindowPolicy](纯逻辑,只认「每一轮多少 token」)接到真正的消息列表上:
 * 切轮、估 token、算出窗口起点该前移到哪。
 */
object ThreadWindow {
    /** 一轮:从 [startIndex] 起,一条用户消息加其后的助手消息。 */
    data class Turn(val startIndex: Int, val tokens: Int)

    /**
     * 估 token。中日韩字符大约一字一 token,其余大约四字符一 token。
     * 宁可高估:高估只会让窗口早一点滑,低估会让请求撞上模型的上下文上限。
     */
    fun estimateTokens(text: String): Int {
        var cjk = 0
        var other = 0
        for (ch in text) {
            if (ch.code in 0x2E80..0x9FFF || ch.code in 0xAC00..0xD7AF || ch.code in 0xFF00..0xFFEF) cjk++ else other++
        }
        return cjk + (other + 3) / 4
    }

    fun estimate(message: ChatMessage): Int {
        var tokens = estimateTokens(message.modelText)
        for (call in message.toolCalls) {
            tokens += estimateTokens(call.input) + estimateTokens(call.output.orEmpty())
        }
        // 每条消息的角色、分隔这些固定开销。
        return tokens + 6
    }

    /** 从 [startIndex] 起按轮切。开头如果不是用户消息(理论上不会),并进第一轮。 */
    fun turns(messages: List<ChatMessage>, startIndex: Int): List<Turn> {
        if (startIndex !in messages.indices) return emptyList()
        val turns = ArrayList<Turn>()
        var start = startIndex
        var tokens = 0
        for (i in startIndex until messages.size) {
            val message = messages[i]
            if (message.role == ChatMessage.Role.USER && i > start) {
                turns += Turn(start, tokens)
                start = i
                tokens = 0
            }
            tokens += estimate(message)
        }
        turns += Turn(start, tokens)
        return turns
    }

    /**
     * 窗口起点该在哪。返回 [startIndex] 表示不动;否则是新起点(某一轮的第一条)的下标。
     * [pendingTokens] 是这一轮还没写进列表、但马上要发的那部分(系统提示、工具定义占的位子):
     * 从预算里先扣掉,不然窗口按「只有对话」算,加上固定开销就超了。
     */
    fun evict(
        messages: List<ChatMessage>,
        startIndex: Int,
        policy: WindowPolicy,
        overheadTokens: Int = 0,
    ): Int {
        val turns = turns(messages, startIndex)
        if (turns.isEmpty()) return startIndex
        val effective = WindowPolicy(
            budgetTokens = (policy.budgetTokens - overheadTokens).coerceAtLeast(policy.budgetTokens / 2),
            lowRatio = policy.lowRatio,
            minTailTurns = policy.minTailTurns,
        )
        val evicted = effective.turnsToEvict(turns.map { it.tokens })
        return if (evicted <= 0) startIndex else turns[evicted].startIndex
    }

    /** 溢出救援用:不管水位线,只留最近 [keepTurns] 轮。 */
    fun forceEvict(messages: List<ChatMessage>, startIndex: Int, keepTurns: Int): Int {
        val turns = turns(messages, startIndex)
        if (turns.size <= keepTurns) return startIndex
        return turns[turns.size - keepTurns].startIndex
    }
}
