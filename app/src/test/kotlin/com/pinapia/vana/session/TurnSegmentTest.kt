package com.pinapia.vana.session

import com.pinapia.vana.agentruntime.AgentToolOutput
import com.pinapia.vana.agentruntime.ToolCallRecordDTO
import com.pinapia.vana.ask.AskUserQuestion
import com.pinapia.vana.ask.AskUserTools
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 助手气泡里一轮回复的排列顺序。
 *
 * `text` / `reasoning` 各是一个往后接的字符串,`toolCalls` 是数组,而模型这一轮实际是
 * 交错的。三堆各自堆在一起的老排法会让 chip 插进已写正文上方、因果反了。
 */
class TurnSegmentTest {
    private fun shape(message: ChatMessage): List<String> = message.turnSegments.map { segment ->
        when (segment) {
            is TurnSegment.Reasoning -> "think:${segment.text}"
            is TurnSegment.Text -> "text:${segment.text}"
            is TurnSegment.Tool -> "tool:${segment.call.name}"
        }
    }

    private fun finish(message: ChatMessage, id: String) {
        message.finishToolCall(
            id = id,
            output = AgentToolOutput(kind = AgentToolOutput.Kind.TABLE, text = "08-06 6 小时 29 分"),
            isError = false,
        )
    }

    @Test
    fun interleavesInHappenedOrder() {
        val message = ChatMessage(role = ChatMessage.Role.ASSISTANT, text = "")
        message.appendReasoning("先查睡眠。")
        message.appendText("先看睡眠。我查最近 7 晚。")
        message.startToolCall(ToolCallRecordDTO(id = "1", name = "sleep_summary", input = "{}"))
        finish(message, "1")
        message.appendReasoning("拿到睡眠了,接着查心率。")
        message.appendText("睡眠结果：三晚有记录。我查最近 7 天。")
        message.startToolCall(ToolCallRecordDTO(id = "2", name = "heart_rate_summary", input = "{}"))
        finish(message, "2")
        message.appendText("心率结果：贴着基线。")

        assertEquals(
            listOf(
                "think:先查睡眠。",
                "text:先看睡眠。我查最近 7 晚。",
                "tool:sleep_summary",
                "think:拿到睡眠了,接着查心率。",
                "text:睡眠结果：三晚有记录。我查最近 7 天。",
                "tool:heart_rate_summary",
                "text:心率结果：贴着基线。",
            ),
            shape(message),
        )
    }

    @Test
    fun skipsChipForRoundsWithoutReasoning() {
        val message = ChatMessage(role = ChatMessage.Role.ASSISTANT, text = "")
        message.appendReasoning("先查睡眠。")
        message.appendText("我查一下。")
        message.startToolCall(ToolCallRecordDTO(id = "1", name = "sleep_summary", input = "{}"))
        finish(message, "1")
        message.appendText("三晚有记录。")

        assertEquals(
            listOf(
                "think:先查睡眠。",
                "text:我查一下。",
                "tool:sleep_summary",
                "text:三晚有记录。",
            ),
            shape(message),
        )
    }

    @Test
    fun keepsParallelCallsTogether() {
        val message = ChatMessage(role = ChatMessage.Role.ASSISTANT, text = "")
        message.appendText("三项一起查。")
        message.startToolCall(ToolCallRecordDTO(id = "1", name = "sleep_summary", input = "{}"))
        message.startToolCall(ToolCallRecordDTO(id = "2", name = "heart_rate_summary", input = "{}"))
        finish(message, "1")
        finish(message, "2")
        message.appendText("都拿到了。")

        assertEquals(
            listOf(
                "text:三项一起查。",
                "tool:sleep_summary",
                "tool:heart_rate_summary",
                "text:都拿到了。",
            ),
            shape(message),
        )
    }

    @Test
    fun fallsBackWhenOffsetsAreMissing() {
        val message = ChatMessage(
            role = ChatMessage.Role.ASSISTANT,
            text = "查完了,三晚有记录。",
            reasoning = "先查睡眠。",
            toolCalls = listOf(
                ToolCallRecord(id = "1", name = "sleep_summary", input = "{}", output = "…"),
            ),
        )

        assertEquals(
            listOf(
                "think:先查睡眠。",
                "tool:sleep_summary",
                "text:查完了,三晚有记录。",
            ),
            shape(message),
        )
    }

    @Test
    fun clampsOffsetsPastTheEnd() {
        val message = ChatMessage(role = ChatMessage.Role.ASSISTANT, text = "")
        message.appendText("先看睡眠，我查一下。")
        message.startToolCall(ToolCallRecordDTO(id = "1", name = "sleep_summary", input = "{}"))
        finish(message, "1")
        message.rollBackText(6)

        assertEquals(listOf("text:先看睡眠", "tool:sleep_summary"), shape(message))
    }

    @Test
    fun doesNotSplitAroundHiddenCalls() {
        val message = ChatMessage(role = ChatMessage.Role.ASSISTANT, text = "")
        message.appendText("这个得先问你一句。")
        message.startToolCall(ToolCallRecordDTO(id = "1", name = AskUserTools.ASK_TOOL_NAME, input = "{}"))
        val asked = AskUserQuestion(
            question = "你说的头疼是哪一种？",
            options = listOf(
                AskUserQuestion.Option(label = "胀痛"),
                AskUserQuestion.Option(label = "刺痛"),
            ),
        )
        message.finishToolCall(
            id = "1",
            output = AgentToolOutput(
                kind = AgentToolOutput.Kind.TEXT,
                text = "已显示",
                metadata = AskUserQuestion.encodeForToolMetadata(asked),
            ),
            isError = false,
        )
        message.appendText("等你选完再往下说。")

        assertEquals(
            listOf("text:这个得先问你一句。等你选完再往下说。"),
            shape(message),
        )
    }

    @Test
    fun trimsLeadingBlankLines() {
        val message = ChatMessage(role = ChatMessage.Role.ASSISTANT, text = "")
        message.appendText("我查一下。\n\n")
        message.startToolCall(ToolCallRecordDTO(id = "1", name = "sleep_summary", input = "{}"))
        finish(message, "1")
        message.appendText("\n\n三晚有记录。")

        assertEquals(
            listOf(
                "text:我查一下。",
                "tool:sleep_summary",
                "text:三晚有记录。",
            ),
            shape(message),
        )
    }
}
