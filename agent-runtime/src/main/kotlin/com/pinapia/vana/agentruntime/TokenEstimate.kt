package com.pinapia.vana.agentruntime

/**
 * 本地 token 粗估。窗口([WindowPolicy])和上下文规划器([ConversationHistoryPlanner])用**同一把尺子**:
 * 以前窗口那边认得中文一字一 token,规划器那边一律按四字符一 token,对中文要低估到三四分之一,
 * 只能靠校准比值事后补——校准要有过几轮才准,第一轮就是错的。
 *
 * 中日韩字符大约一字一 token,其余大约四字符一 token。**宁可高估**:高估只会让窗口早一点滑、压缩早一点动,
 * 低估会让请求撞上模型的上下文上限。
 */
object TokenEstimate {
    fun text(text: String): Int {
        var cjk = 0
        var other = 0
        for (ch in text) {
            if (ch.code in 0x2E80..0x9FFF || ch.code in 0xAC00..0xD7AF || ch.code in 0xFF00..0xFFEF) cjk++ else other++
        }
        return cjk + (other + 3) / 4
    }

    /** 一个工具定义占的位子:名字、描述,加**发出去的那份** JSON Schema(不是 Kotlin 的调试输出)。 */
    fun definition(definition: CapabilityDefinition): Int =
        text(definition.name) + text(definition.description.orEmpty()) + text(definition.inputSchema.encodedString())

    fun part(part: AgentTranscript.Part): Int = when (part) {
        is AgentTranscript.Part.Text -> text(part.text)
        is AgentTranscript.Part.Reasoning -> text(part.text)
        is AgentTranscript.Part.ToolCallPart -> text(part.toolCall.input) + text(part.toolCall.toolName)
        // 各家协议发的都是 `stringValue ?: encodedString()`,这里照同一口径算。
        is AgentTranscript.Part.ToolResultPart ->
            text(part.toolResult.result.stringValue ?: part.toolResult.result.encodedString())
        is AgentTranscript.Part.File -> FILE_PART_TOKENS
    }

    fun transcript(transcript: AgentTranscript): Int =
        transcript.messages.sumOf { message -> message.parts.sumOf(::part) }

    /** 一张图或一份文件的固定估值。真实开销随模型和分辨率差很多,这里只求不为零。 */
    private const val FILE_PART_TOKENS = 25
}
