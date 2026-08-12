package com.pinapia.vana.ask

import com.pinapia.vana.agentruntime.AgentToolOutput
import com.pinapia.vana.agentruntime.CapabilityDefinition
import com.pinapia.vana.agentruntime.CapabilityExecutionResult
import com.pinapia.vana.agentruntime.CapabilityInvocation
import com.pinapia.vana.agentruntime.CapabilityRegistry
import com.pinapia.vana.agentruntime.RuntimeJSONValue

object AskUserTools {
    const val ASK_TOOL_NAME = "ask_user"

    val footer = """
        接下来：正文里不要把上面的选项再列一遍——卡片上已经有了，最多用一句话说清你为什么要问。也不用告诉他去哪儿点：卡片排在你这段话的**下面**，别写成「上面的选项」。说完就停下等他回答，不要替他假设一个答案接着往下分析。他跳过了、或者答得含糊，就按已有的信息继续，同一个问题不要再问第二遍。
    """.trimIndent()

    fun registry(): CapabilityRegistry {
        val definition = CapabilityDefinition(
            name = ASK_TOOL_NAME,
            description = """
                反问用户一句，并把答案做成他点一下就能选的卡片。他的描述里**缺一个会改变你回答方向的条件、而它的取值只有有限几种**时就调用：是哪一种不舒服、什么时候开始的、想从哪儿入手，或者接下来能帮他的方向有好几个、该挑哪个得他说了算。**先问再答**，不要按最可能的那一种猜着答完。卡片显示在你这条回复下面，他也可以自己写一句或者跳过不答。健康数据里查得到的（睡了多久、走了多少步、心率多少）**不要问他**，去调健康工具。答案本身是开放的（他得讲一段经过）就直接用一句话问，别硬凑几个选项；一次只问一个问题。
            """.trimIndent().replace("\n", ""),
            inputSchema = RuntimeJSONValue.ObjectValue(
                mapOf(
                    "type" to RuntimeJSONValue.StringValue("object"),
                    "properties" to RuntimeJSONValue.ObjectValue(
                        mapOf(
                            "question" to RuntimeJSONValue.ObjectValue(
                                mapOf(
                                    "type" to RuntimeJSONValue.StringValue("string"),
                                    "description" to RuntimeJSONValue.StringValue(
                                        "要问他的那一句，一个问题，不超过 ${AskUserQuestion.MAX_QUESTION} 个字",
                                    ),
                                ),
                            ),
                            "options" to RuntimeJSONValue.ObjectValue(
                                mapOf(
                                    "type" to RuntimeJSONValue.StringValue("array"),
                                    "description" to RuntimeJSONValue.StringValue(
                                        "${AskUserQuestion.MIN_OPTIONS}–${AskUserQuestion.MAX_OPTIONS} 个互不重叠的选项，覆盖常见的几种情况。「其他」和「不想说」不用写，卡片上本来就有",
                                    ),
                                    "items" to RuntimeJSONValue.ObjectValue(
                                        mapOf(
                                            "type" to RuntimeJSONValue.StringValue("object"),
                                            "properties" to RuntimeJSONValue.ObjectValue(
                                                mapOf(
                                                    "label" to RuntimeJSONValue.ObjectValue(
                                                        mapOf(
                                                            "type" to RuntimeJSONValue.StringValue("string"),
                                                            "description" to RuntimeJSONValue.StringValue(
                                                                "按钮上的字，用他会说的说法，不超过 ${AskUserQuestion.MAX_LABEL} 个字",
                                                            ),
                                                        ),
                                                    ),
                                                    "detail" to RuntimeJSONValue.ObjectValue(
                                                        mapOf(
                                                            "type" to RuntimeJSONValue.StringValue("string"),
                                                            "description" to RuntimeJSONValue.StringValue(
                                                                "这条是什么意思，一句话，不超过 ${AskUserQuestion.MAX_DETAIL} 个字。标签本身已经说清楚就别写",
                                                            ),
                                                        ),
                                                    ),
                                                ),
                                            ),
                                            "required" to RuntimeJSONValue.ArrayValue(
                                                listOf(RuntimeJSONValue.StringValue("label")),
                                            ),
                                            "additionalProperties" to RuntimeJSONValue.BoolValue(false),
                                        ),
                                    ),
                                    "minItems" to RuntimeJSONValue.IntValue(AskUserQuestion.MIN_OPTIONS),
                                    "maxItems" to RuntimeJSONValue.IntValue(AskUserQuestion.MAX_OPTIONS),
                                ),
                            ),
                            "allowsMultiple" to RuntimeJSONValue.ObjectValue(
                                mapOf(
                                    "type" to RuntimeJSONValue.StringValue("boolean"),
                                    "description" to RuntimeJSONValue.StringValue(
                                        "几条可以同时成立时传 true，比如问他有哪些症状。只能选一种就别传",
                                    ),
                                ),
                            ),
                        ),
                    ),
                    "required" to RuntimeJSONValue.ArrayValue(
                        listOf("question", "options").map { RuntimeJSONValue.StringValue(it) },
                    ),
                    "additionalProperties" to RuntimeJSONValue.BoolValue(false),
                ),
            ),
            strictPreferred = false,
        )
        return CapabilityRegistry(definitions = listOf(definition)) { invocation ->
            ask(invocation)
        }
    }

    private fun ask(invocation: CapabilityInvocation): CapabilityExecutionResult {
        val input = runCatching { RuntimeJSONValue.decode(from = invocation.input) }.getOrNull()
        val question = clipped(input?.get("question")?.stringValue, AskUserQuestion.MAX_QUESTION)
        if (question.isEmpty()) {
            return failure("参数不全：需要 question。")
        }
        val seen = mutableSetOf<String>()
        val options = input?.get("options")?.arrayValue.orEmpty()
            .map {
                AskUserQuestion.Option(
                    label = clipped(it["label"]?.stringValue, AskUserQuestion.MAX_LABEL),
                    detail = clipped(it["detail"]?.stringValue, AskUserQuestion.MAX_DETAIL),
                )
            }
            .filter { it.label.isNotEmpty() && seen.add(it.label) }
            .take(AskUserQuestion.MAX_OPTIONS)
        if (options.size < AskUserQuestion.MIN_OPTIONS) {
            return failure(
                "至少要 ${AskUserQuestion.MIN_OPTIONS} 个不重复的选项，这次只有 ${options.size} 个。" +
                    "本来就只有一种可能的追问，直接在正文里用一句话问他，不用调这个工具。",
            )
        }
        val payload = AskUserQuestion(
            question = question,
            options = options,
            allowsMultiple = input?.get("allowsMultiple")?.boolValue == true,
        )
        return CapabilityExecutionResult(
            output = AgentToolOutput(
                kind = AgentToolOutput.Kind.TEXT,
                text = modelText(payload),
                metadata = AskUserQuestion.encodeForToolMetadata(payload),
            ),
        )
    }

    fun modelText(question: AskUserQuestion): String {
        val lines = mutableListOf(
            "已经把这个问题做成选项卡片，显示在你这条回复下面：",
            "",
            "问：${question.question}",
        )
        lines += question.options.map { option ->
            if (option.detail.isEmpty()) "- ${option.label}" else "- ${option.label}（${option.detail}）"
        }
        lines += if (question.allowsMultiple) {
            "他可以勾选其中几条，也可以自己写一句，或者跳过不答。"
        } else {
            "他可以点其中一条，也可以自己写一句，或者跳过不答。"
        }
        lines += ""
        lines += footer
        return lines.joinToString("\n")
    }

    private fun failure(text: String) = CapabilityExecutionResult(
        output = AgentToolOutput(kind = AgentToolOutput.Kind.TEXT, text = text),
        isError = true,
    )

    private fun clipped(value: String?, limit: Int): String =
        (value ?: "").trim().take(limit)
}
