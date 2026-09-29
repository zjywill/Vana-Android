package com.pinapia.vana.thread

import com.pinapia.vana.agentruntime.AgentTranscript
import com.pinapia.vana.agentruntime.TokenEstimate
import com.pinapia.vana.agentruntime.WindowPolicy
import com.pinapia.vana.session.ChatMessage

/**
 * 把 [WindowPolicy](纯逻辑,只认「每一轮多少 token」)接到真正的消息列表上:
 * 切轮、估 token、算出窗口起点该前移到哪。
 */
object ThreadWindow {
    /** 一轮:从 [startIndex] 起,一条用户消息加其后的助手消息。 */
    data class Turn(val startIndex: Int, val tokens: Int)

    /** 估 token。口径在 [TokenEstimate]:和上下文规划器共用一把尺子,宁可高估。 */
    fun estimateTokens(text: String): Int = TokenEstimate.text(text)

    /**
     * 一条消息发出去要占多少。除了正文和工具的输入输出,还有**回放给模型的思考**:
     * 历史里助手消息的 `reasoning` 在 OpenAI 兼容和 Gemini 协议下会原样发回去,思考模型的思考常常比答案还长,
     * 不计入的话窗口以为自己没满、请求其实已经大了一截。回放的是 `storedTurn.exactTranscript` 里那份
     * (界面上的 `reasoning` 是同一段文字的副本,不能再算一遍);没有 exact transcript 的那轮(被停掉、失败)
     * 回放走重建,里面没有思考。
     *
     * [replaysReasoning] 由当前用的协议决定,见 `OpenAICompatibleModelClient.replaysReasoning`。
     */
    fun estimate(message: ChatMessage, replaysReasoning: Boolean = true): Int {
        var tokens = estimateTokens(message.modelText)
        for (call in message.toolCalls) {
            tokens += estimateTokens(call.input) + estimateTokens(call.output.orEmpty())
        }
        if (replaysReasoning && message.role == ChatMessage.Role.ASSISTANT) {
            for (replayed in message.storedTurn.exactTranscript.messages) {
                for (part in replayed.parts) {
                    if (part is AgentTranscript.Part.Reasoning) tokens += estimateTokens(part.text)
                }
            }
        }
        // 每条消息的角色、分隔这些固定开销。
        return tokens + 6
    }

    /** 从 [startIndex] 起按轮切。开头如果不是用户消息(理论上不会),并进第一轮。 */
    fun turns(messages: List<ChatMessage>, startIndex: Int, replaysReasoning: Boolean = true): List<Turn> {
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
            tokens += estimate(message, replaysReasoning)
        }
        turns += Turn(start, tokens)
        return turns
    }

    /**
     * 窗口起点该在哪。返回 [startIndex] 表示不动;否则是新起点(某一轮的第一条)的下标。
     * [overheadTokens] 是每一轮请求都要带、但不在消息列表里的那部分(system 段、工具定义占的位子):
     * 从预算里先扣掉,不然窗口按「只有对话」算,加上固定开销就超了。
     */
    fun evict(
        messages: List<ChatMessage>,
        startIndex: Int,
        policy: WindowPolicy,
        overheadTokens: Int = 0,
        replaysReasoning: Boolean = true,
    ): Int {
        val turns = turns(messages, startIndex, replaysReasoning)
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
