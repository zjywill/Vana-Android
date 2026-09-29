package com.pinapia.vana.tasks

/**
 * 把后台助手最后写的那段话拆成结果:第一段是结论(它会原样进对话,模型下一轮看得到),
 * 空一行之后是详细内容,最后一段「来源：」每行一条。
 *
 * 不用一个专门的「交卷」工具:模型直接写话最自然,少一个它可能忘记调的工具,
 * 而格式偏了也不丢东西——最坏是整段成了结论。
 */
object SubagentResult {
    private const val SUMMARY_LIMIT = 160
    private val sourceHeader = Regex("""^\s*(来源|参考|资料来源|Sources?)\s*[:：]\s*(.*)$""", RegexOption.IGNORE_CASE)

    fun parse(text: String, proposals: List<TaskProposal> = emptyList()): TaskResult? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null

        val lines = trimmed.lines()
        val sourceStart = lines.indexOfLast { sourceHeader.matches(it) }
        val main: List<String>
        val sources: List<String>
        if (sourceStart >= 0) {
            main = lines.take(sourceStart)
            val first = sourceHeader.matchEntire(lines[sourceStart])!!.groupValues[2]
            sources = (listOf(first) + lines.drop(sourceStart + 1))
                .map { it.trim().trimStart('-', '•', '*', ' ').trim() }
                .filter { it.isNotEmpty() }
                .take(10)
        } else {
            main = lines
            sources = emptyList()
        }

        val mainText = main.joinToString("\n").trim()
        val split = mainText.indexOf("\n\n")
        val first = (if (split >= 0) mainText.substring(0, split) else mainText).trim()
        val rest = if (split >= 0) mainText.substring(split).trim() else ""
        val summary = if (first.length <= SUMMARY_LIMIT) first else clip(first)
        // 第一段被截短了,被截掉的那部分不能丢:并回详细内容。
        val body = if (first.length <= SUMMARY_LIMIT) rest else (first + if (rest.isEmpty()) "" else "\n\n$rest")
        if (summary.isEmpty()) return null
        return TaskResult(summary = summary, body = body, proposals = proposals, sources = sources)
    }

    private fun clip(text: String): String {
        val end = text.take(SUMMARY_LIMIT).indexOfLast { it in "。！？!?；;" }
        return if (end >= 40) text.take(end + 1) else text.take(SUMMARY_LIMIT - 1) + "…"
    }
}

/** 后台助手的角色说明。**不带领域词**:健康那一侧该守的规则由健康插件在这条路上自己贡献。 */
object SubagentInstructions {
    fun text(): String =
        """
            你现在是 Vana 派出去的后台助手，替用户独立完成一件事。他此刻不在场，你没有和他对话的通道：
            - 不能反问，也不要写「请告诉我」。信息不全就按最合理的假设做，并在结果里说明你的假设。
            - 你是只读的：可以查资料、看记忆和过往对话，不能改任何东西。想让用户做的事（设提醒、记成目标、记住某件事）用 ${SubagentTools.PROPOSE_ACTION} 提议，他点了才会执行。
            - 工具最多用 ${SubagentLimits.MAX_TOOL_ROUNDS} 轮，够用就停，不要为了显得认真而多查。
            - 搜索词里不要写进用户的姓名、账号、住址这类个人信息。
            - 结果这样写：第一段一两句话的结论（它会直接出现在对话里）；空一行，写详细内容；如果引用了资料，最后另起一段「来源：」，一行一条。
            - 拿不准的地方直说拿不准，不要编。没有把握的事实不要写成确定的。
        """.trimIndent()
}
