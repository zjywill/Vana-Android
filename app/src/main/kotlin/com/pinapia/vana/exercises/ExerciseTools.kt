package com.pinapia.vana.exercises

import com.pinapia.vana.agentruntime.AgentToolOutput
import com.pinapia.vana.agentruntime.CapabilityDefinition
import com.pinapia.vana.agentruntime.CapabilityExecutionResult
import com.pinapia.vana.agentruntime.CapabilityInvocation
import com.pinapia.vana.agentruntime.CapabilityRegistry
import com.pinapia.vana.agentruntime.RuntimeJSONValue

data class ExerciseSelection(val moveIDs: List<String>) {
    companion object {
        fun encodeForToolMetadata(selection: ExerciseSelection): RuntimeJSONValue =
            RuntimeJSONValue.ObjectValue(
                mapOf(
                    "moveIDs" to RuntimeJSONValue.ArrayValue(
                        selection.moveIDs.map { RuntimeJSONValue.StringValue(it) },
                    ),
                ),
            )

        fun decode(fromToolMetadata: RuntimeJSONValue?): ExerciseSelection? {
            val ids = fromToolMetadata?.get("moveIDs")?.arrayValue
                ?.mapNotNull { it.stringValue }
                ?.filter { it.isNotBlank() }
                .orEmpty()
            if (ids.isEmpty()) return null
            return ExerciseSelection(moveIDs = ids)
        }
    }
}

object ExerciseTools {
    const val SUGGEST_TOOL_NAME = "suggest_exercises"
    val joints = listOf("颈", "肩", "肘", "腕", "腰", "髋", "膝", "踝")

    val footer = """
        接下来：正文里不要把上面的步骤逐条复述一遍——卡片上已经有图和步骤了，说清为什么挑这几个、他做的时候要注意什么就够了。用户说过做不了的动作绝对不要提。不要给次数、组数或者保持多少秒，让他按自己的感觉来，有不适就停。
    """.trimIndent()

    fun registry(library: ExerciseLibrary): CapabilityRegistry {
        val definition = CapabilityDefinition(
            name = SUGGEST_TOOL_NAME,
            description = """
                在用户问「做点什么」「怎么拉伸」「有什么动作」，或者你打算建议他活动一下的时候调用。返回的动作会带图示显示在你这条回复下面，用户能照着做。**只能推荐这个工具返回的动作**：库以外的动作没有图，说了他也不知道怎么做。疼痛、受伤、术后、孕期不要调这个，先让他去看医生。
            """.trimIndent().replace("\n", ""),
            inputSchema = RuntimeJSONValue.ObjectValue(
                mapOf(
                    "type" to RuntimeJSONValue.StringValue("object"),
                    "properties" to RuntimeJSONValue.ObjectValue(
                        mapOf(
                            "scene" to RuntimeJSONValue.ObjectValue(
                                mapOf(
                                    "type" to RuntimeJSONValue.StringValue("string"),
                                    "description" to RuntimeJSONValue.StringValue("从哪一类里挑"),
                                    "enum" to RuntimeJSONValue.ArrayValue(
                                        library.scenes.map { RuntimeJSONValue.StringValue(it) },
                                    ),
                                ),
                            ),
                            "excludeJoint" to RuntimeJSONValue.ObjectValue(
                                mapOf(
                                    "type" to RuntimeJSONValue.StringValue("array"),
                                    "description" to RuntimeJSONValue.StringValue(
                                        "用户说过不好、受过伤、做不了的关节。带这些关节的动作一个都不会返回。记忆或用药表里提到过的也要带上",
                                    ),
                                    "items" to RuntimeJSONValue.ObjectValue(
                                        mapOf(
                                            "type" to RuntimeJSONValue.StringValue("string"),
                                            "enum" to RuntimeJSONValue.ArrayValue(
                                                joints.map { RuntimeJSONValue.StringValue(it) },
                                            ),
                                        ),
                                    ),
                                ),
                            ),
                            "noFloor" to RuntimeJSONValue.ObjectValue(
                                mapOf(
                                    "type" to RuntimeJSONValue.StringValue("boolean"),
                                    "description" to RuntimeJSONValue.StringValue(
                                        "他不方便躺下或跪地时传 true，比如在办公室、在外面，或者他说了起身困难",
                                    ),
                                ),
                            ),
                            "count" to RuntimeJSONValue.ObjectValue(
                                mapOf(
                                    "type" to RuntimeJSONValue.StringValue("integer"),
                                    "description" to RuntimeJSONValue.StringValue("要几个，1–4，默认 3"),
                                    "minimum" to RuntimeJSONValue.IntValue(1),
                                    "maximum" to RuntimeJSONValue.IntValue(4),
                                ),
                            ),
                        ),
                    ),
                    "required" to RuntimeJSONValue.ArrayValue(listOf(RuntimeJSONValue.StringValue("scene"))),
                    "additionalProperties" to RuntimeJSONValue.BoolValue(false),
                ),
            ),
            strictPreferred = false,
        )
        return CapabilityRegistry(definitions = listOf(definition)) { invocation ->
            suggest(library, invocation)
        }
    }

    private fun suggest(
        library: ExerciseLibrary,
        invocation: CapabilityInvocation,
    ): CapabilityExecutionResult {
        if (invocation.name != SUGGEST_TOOL_NAME) {
            return CapabilityExecutionResult(
                output = AgentToolOutput(kind = AgentToolOutput.Kind.TEXT, text = "不支持名为 ${invocation.name} 的工具。"),
                isError = true,
            )
        }
        val input = runCatching { RuntimeJSONValue.decode(from = invocation.input) }.getOrNull()
        val scene = input?.get("scene")?.stringValue.orEmpty()
        val excluded = input?.get("excludeJoint")?.arrayValue?.mapNotNull { it.stringValue }.orEmpty()
        val noFloor = input?.get("noFloor")?.boolValue == true
        val count = input?.get("count")?.intValue ?: 3
        val picked = library.suggest(
            scene = scene,
            excludeJoints = excluded,
            avoidsFloor = noFloor,
            limit = count,
        )
        if (picked.isEmpty()) {
            return CapabilityExecutionResult(
                output = AgentToolOutput(kind = AgentToolOutput.Kind.TEXT, text = emptyText(scene, excluded)),
            )
        }
        return CapabilityExecutionResult(
            output = AgentToolOutput(
                kind = AgentToolOutput.Kind.TEXT,
                text = modelText(picked),
                metadata = ExerciseSelection.encodeForToolMetadata(
                    ExerciseSelection(moveIDs = picked.map { it.id }),
                ),
            ),
        )
    }

    fun emptyText(scene: String, excluded: List<String>): String {
        var text = "动作库里「$scene」这一类"
        text += if (excluded.isEmpty()) {
            "暂时没有可推荐的动作。"
        } else {
            "里，避开${excluded.joinToString("、")}之后没有剩下可推荐的动作。"
        }
        return text + "照实告诉用户这次没有能配图的动作，需要的话让他去问康复师或医生。不要自己编一个动作出来。"
    }

    fun modelText(moves: List<ExerciseMove>): String {
        val lines = mutableListOf("为用户挑了这 ${moves.size} 个动作，卡片（含图示）已经显示在你这条回复下面：")
        moves.forEachIndexed { index, move ->
            lines += ""
            lines += "${index + 1}. ${move.zh}（${move.part}；${move.gear}）"
            lines += move.steps.map { "   - $it" }
            lines += "   要领：${move.cue}"
            lines += "   什么情况别做：${move.avoid}"
        }
        lines += ""
        lines += footer
        return lines.joinToString("\n")
    }
}
