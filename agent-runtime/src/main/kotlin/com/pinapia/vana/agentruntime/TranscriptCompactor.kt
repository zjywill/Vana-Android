package com.pinapia.vana.agentruntime

/**
 * 把一轮完整的 agent 活动压成一个显式的 compaction artifact。
 *
 * 「压缩」不等于「退回可见回答文本」。一轮里真正发生的事有两份读者:
 * - 用户要的是那段可见回答(`visibleSummary`),折叠提示、会话摘要都用它;
 * - 模型要的是「这轮我查了什么、查出来大概是什么」(`replaySummary`),纯回答文本会把
 *   工具轨迹整个丢掉,追问时模型就不知道自己上次查过 daily_steps 了。
 *
 * 两份分开生成、一起存,才是能被别的 agent 复用的 artifact,而不是一个临时的省 token 技巧。
 */
data class TranscriptCompactor(
    /** 每次工具调用在摘要里最多占多少字符。超了截断加后缀。 */
    val maxCharactersPerToolCall: Int = 160,
    /** 摘要里最多列几次工具调用,再多只报个数。 */
    val maxToolCallsInDigest: Int = 6,
    /** 折叠提示的格式串,只有一个 `%d`(折叠了几次调用)。 */
    val digestHeaderFormat: String = "[folded %d tool call(s) from this turn]",
    val truncationSuffix: String = "…",
    /** 还有多少次调用没列出来,格式串只有一个 `%d`。 */
    val overflowFormat: String = "(+%d more)",
) {
    /** 给一条 assistant 消息造 artifact。没有任何可留下的内容就返回 null。 */
    fun artifact(forMessage: AgentChatMessageDTO): CompactionArtifact? {
        if (forMessage.role != AgentChatMessageDTO.Role.ASSISTANT) return null

        val visible = if (forMessage.textIsPlaceholder) "" else forMessage.text
        val executed = forMessage.toolCalls.filter { it.output != null }
        if (visible.isEmpty() && executed.isEmpty()) return null

        val digest = toolDigest(executed)
        val replayText = listOf(visible, digest)
            .filter { it.isNotEmpty() }
            .joinToString("\n\n")
        if (replayText.isEmpty()) return null

        return CompactionArtifact(
            kind = if (digest.isEmpty()) CompactionArtifact.Kind.ANSWER_ONLY else CompactionArtifact.Kind.TOOL_DIGEST,
            visibleSummary = visible,
            replaySummary = AgentTranscript.Message.assistantSummary(
                replayText,
                listOf(forMessage.uuid),
            ),
            sourceMessageIDs = listOf(forMessage.id),
            foldedToolCalls = executed.size,
        )
    }

    private fun toolDigest(calls: List<ToolCallRecordDTO>): String {
        if (calls.isEmpty()) return ""

        val lines = mutableListOf(String.format(digestHeaderFormat, calls.size))
        for (call in calls.take(maxToolCallsInDigest)) {
            val output = call.output?.text.orEmpty()
            val flattened = output
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString(" / ")
            lines += "- ${call.name} ${call.input} → ${truncated(flattened)}"
        }
        val overflow = calls.size - maxToolCallsInDigest
        if (overflow > 0) {
            lines += String.format(overflowFormat, overflow)
        }
        return lines.joinToString("\n")
    }

    private fun truncated(text: String): String {
        if (text.length <= maxCharactersPerToolCall) return text
        return text.take(maxCharactersPerToolCall) + truncationSuffix
    }

    companion object {
        val default = TranscriptCompactor()
    }
}
