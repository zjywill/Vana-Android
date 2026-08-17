package com.pinapia.vana.agentruntime

import java.util.UUID

sealed class AgentTurnEvent {
    data class TextDelta(val text: String) : AgentTurnEvent()

    /**
     * 模型的思考。带工具的一轮里会出现好几段——每次要调工具之前都想一次。
     */
    data class ReasoningDelta(val text: String) : AgentTurnEvent()

    data class ToolCallStarted(val record: ToolCallRecordDTO) : AgentTurnEvent()

    data class ToolCallFinished(
        val id: String,
        val output: AgentToolOutput,
        val isError: Boolean,
    ) : AgentTurnEvent()

    /**
     * 这一轮要重跑了,已经吐出去的半截话得撤掉——重跑会从头再说一遍,不撤就是半句接整句。
     *
     * 和 [RetryScheduled] 分开发:重跑不一定是重试(压缩之后重跑那一轮也走这条),
     * 而「撤字」和「告诉用户在重试」是两件事,合在一起的话压缩会顶着重试的文案出现。
     */
    data class TextRolledBack(val characterCount: Int) : AgentTurnEvent()

    /**
     * 同上,但撤的是思考。两个计数分开:重跑时两边都要回到这一次尝试之前的样子,
     * 而它们各自存在各自的地方。
     */
    data class ReasoningRolledBack(val characterCount: Int) : AgentTurnEvent()

    /** 这一轮失败了,退避之后会再试。纯观测事件,给 UI 用。 */
    data class RetryScheduled(val notice: AgentRetryNotice) : AgentTurnEvent()

    /** 开始叫模型写整段摘要。压缩要花钱花时间,静默地做等于线上出问题时无从查起。 */
    data class CompactionStarted(val reason: AgentCompactionReason) : AgentTurnEvent()

    /** 总结没写成。不是致命错误——退回机械压缩继续跑,但要留下痕迹。 */
    data class CompactionFailed(
        val reason: AgentCompactionReason,
        val message: String,
    ) : AgentTurnEvent()

    /**
     * 用户在这一轮跑的过程中补的那几句,已经并进这一轮的上下文了。
     *
     * 排队和已送达必须分得开:排着的那句模型还没看见,而用户看到的是同一个气泡。不发这个
     * 事件,他就只能靠猜——而猜错的方向恰好是最糟的那个(以为说了,其实没说)。
     */
    data class PendingInputAccepted(val inputs: List<AgentPendingInput>) : AgentTurnEvent()

    /**
     * 早先某一段被总结掉了,artifact 挂在 `messageID` 这条上。
     *
     * 唯一一个不落在"正在写的那条回复"上的事件——总结是一次真实的模型调用,算完必须存下来,
     * 否则每轮都要重算一遍,既慢又费钱。
     */
    data class HistoryCompacted(
        val messageID: UUID,
        val artifact: CompactionArtifact,
    ) : AgentTurnEvent()

    data class TurnCompleted(
        val transcript: AgentTranscript,
        val finishReason: AgentFinishReason?,
        val usage: AgentUsage?,
        val context: TurnContextSnapshotDTO?,
    ) : AgentTurnEvent()
}

/**
 * 承接一轮事件的容器。
 *
 * app 的 `ChatMessage` 和 runtime 的 [AgentChatMessageDTO] 各实现一份存储,但「事件怎么
 * 改状态」只写在 [apply] 里一处——否则测试里验的和 app 里跑的迟早分家。
 */
interface AgentTurnSink {
    fun appendText(delta: String)
    fun appendReasoning(delta: String)
    fun startToolCall(record: ToolCallRecordDTO)
    fun finishToolCall(id: String, output: AgentToolOutput, isError: Boolean)
    fun completeTurn(
        transcript: AgentTranscript,
        finishReason: AgentFinishReason?,
        usage: AgentUsage?,
        context: TurnContextSnapshotDTO?,
    )

    /**
     * 撤掉刚吐出去的 `characterCount` 个字符。
     *
     * 只在重试前用:那一轮已经说了半句话,重试的完整回答会从头再来一遍,不撤掉就是把
     * 半句和整句拼在一起。撤的是**这一次尝试**吐的量,更早那几轮的文本不受影响。
     */
    fun rollBackText(characterCount: Int)

    /** 同上,撤的是思考。 */
    fun rollBackReasoning(characterCount: Int)

    /**
     * 这几条用户消息在工具轮边界被并进了这一轮的 transcript。
     *
     * 记在**这一轮**上,不是记在那几条消息上:回放时要跳过它们,而「跳过谁」的依据只有
     * 「谁吸收了它」说得清——同一条消息在别的会话里(分叉出去的那条)可能根本没被吸收。
     */
    fun acceptPendingInput(inputs: List<AgentPendingInput>)

    /** 用户手动停下。已经收到的文本和工具结果都留着。 */
    fun markStopped()
    fun markFailed(description: String)
}

fun AgentTurnSink.apply(event: AgentTurnEvent) {
    when (event) {
        is AgentTurnEvent.TextDelta -> appendText(event.text)
        is AgentTurnEvent.ReasoningDelta -> appendReasoning(event.text)
        is AgentTurnEvent.ToolCallStarted -> startToolCall(event.record)
        is AgentTurnEvent.ToolCallFinished ->
            finishToolCall(event.id, event.output, event.isError)
        is AgentTurnEvent.TurnCompleted ->
            completeTurn(event.transcript, event.finishReason, event.usage, event.context)
        is AgentTurnEvent.TextRolledBack -> rollBackText(event.characterCount)
        is AgentTurnEvent.ReasoningRolledBack -> rollBackReasoning(event.characterCount)
        is AgentTurnEvent.PendingInputAccepted -> acceptPendingInput(event.inputs)
        is AgentTurnEvent.RetryScheduled,
        is AgentTurnEvent.CompactionStarted,
        is AgentTurnEvent.CompactionFailed,
        -> {
            // 观测用。落到哪条消息上都不合适,由 app 自己决定要不要显示或记日志。
        }
        is AgentTurnEvent.HistoryCompacted -> {
            // 这条不是给当前回复的,由数组层按 id 投递。
        }
    }
}

/** Mutable wrapper around [AgentChatMessageDTO] that implements [AgentTurnSink]. */
class MutableAgentChatMessage(
    initial: AgentChatMessageDTO,
) : AgentTurnSink {
    var value: AgentChatMessageDTO = initial
        private set

    override fun appendText(delta: String) {
        var msg = value
        if (msg.textIsPlaceholder) {
            msg = msg.copy(text = "", textIsPlaceholder = false)
        }
        value = msg.copy(text = msg.text + delta)
    }

    override fun appendReasoning(delta: String) {
        value = value.copy(reasoning = value.reasoning + delta)
    }

    override fun startToolCall(record: ToolCallRecordDTO) {
        value = value.copy(toolCalls = value.toolCalls + record)
    }

    override fun finishToolCall(id: String, output: AgentToolOutput, isError: Boolean) {
        val index = value.toolCalls.indexOfFirst { it.id == id }
        if (index < 0) return
        val updated = value.toolCalls.toMutableList()
        updated[index] = updated[index].copy(output = output, isError = isError)
        value = value.copy(toolCalls = updated)
    }

    override fun completeTurn(
        transcript: AgentTranscript,
        finishReason: AgentFinishReason?,
        usage: AgentUsage?,
        context: TurnContextSnapshotDTO?,
    ) {
        value = value.copy(
            storedTurn = value.storedTurn.copy(
                exactTranscript = transcript,
                finishReason = finishReason,
                usage = usage,
                context = context,
                state = StoredAgentTurn.State.COMPLETED,
            ),
        )
    }

    override fun rollBackText(characterCount: Int) {
        if (characterCount <= 0 || value.textIsPlaceholder) return
        val drop = minOf(characterCount, value.text.length)
        value = value.copy(text = value.text.dropLast(drop))
    }

    override fun rollBackReasoning(characterCount: Int) {
        if (characterCount <= 0) return
        val drop = minOf(characterCount, value.reasoning.length)
        value = value.copy(reasoning = value.reasoning.dropLast(drop))
    }

    override fun acceptPendingInput(inputs: List<AgentPendingInput>) {
        val ids = value.storedTurn.inlinedMessageIDs + inputs.map { it.id.toString() }
        value = value.copy(storedTurn = value.storedTurn.copy(inlinedMessageIDs = ids))
    }

    override fun markStopped() {
        var msg = value
        if (msg.text.isEmpty()) {
            msg = msg.copy(textIsPlaceholder = true)
        }
        value = msg.copy(storedTurn = msg.storedTurn.copy(state = StoredAgentTurn.State.STOPPED))
    }

    override fun markFailed(description: String) {
        var msg = value
        if (msg.text.isEmpty()) {
            msg = msg.copy(textIsPlaceholder = true)
        }
        value = msg.copy(
            storedTurn = msg.storedTurn.copy(state = StoredAgentTurn.State.FAILED),
            errorDescription = description,
        )
    }
}

/** 数组层的便利包装:大多数调用方拿着的是整条会话,不是单条消息。 */
object AgentTurnReducer {
    fun startReply(history: MutableList<AgentChatMessageDTO>) {
        history += AgentChatMessageDTO(role = AgentChatMessageDTO.Role.ASSISTANT, text = "")
    }

    fun apply(event: AgentTurnEvent, history: MutableList<AgentChatMessageDTO>) {
        if (event is AgentTurnEvent.HistoryCompacted) {
            val index = history.indexOfFirst { it.id == event.messageID.toString() }
            if (index < 0) return
            val msg = history[index]
            history[index] = msg.copy(
                storedTurn = msg.storedTurn.copy(compaction = event.artifact),
            )
            return
        }
        if (history.isEmpty()) return
        val lastIndex = history.lastIndex
        val sink = MutableAgentChatMessage(history[lastIndex])
        sink.apply(event)
        history[lastIndex] = sink.value
    }

    fun markStopped(history: MutableList<AgentChatMessageDTO>) {
        if (history.isEmpty()) return
        val sink = MutableAgentChatMessage(history.last())
        sink.markStopped()
        history[history.lastIndex] = sink.value
    }

    fun markFailed(description: String, history: MutableList<AgentChatMessageDTO>) {
        if (history.isEmpty()) return
        val sink = MutableAgentChatMessage(history.last())
        sink.markFailed(description)
        history[history.lastIndex] = sink.value
    }
}
