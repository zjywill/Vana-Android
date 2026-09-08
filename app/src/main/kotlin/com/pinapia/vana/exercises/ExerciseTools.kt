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

    /**
     * 按部位挑时的闭集。**和场景是两把尺子**：「在工位上能做点什么」问的是场合，「练胸」问的
     * 是部位，合成一个枚举的话模型每次都要在两类东西里挑一个，而它们根本不互斥。
     */
    val regions = listOf("胸", "背", "肩", "手臂", "核心", "腰背", "臀", "腿", "小腿", "髋", "拉伸")

    /** 用户手边可能有什么。**硬过滤**：没说的时候只给 `householdEquipment` 那几样。 */
    val equipmentKinds = listOf(
        "徒手", "墙", "门框", "毛巾", "椅子", "长凳", "箱子",
        "哑铃", "杠铃", "杠铃片", "壶铃", "弹力带", "绳索", "器械", "单杠", "瑜伽球",
    )

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
                                    "description" to RuntimeJSONValue.StringValue(
                                        "什么场合，比如他在工位上、睡前、跑步之前。和 part 至少给一个；「跑完拉一下腿」这种两个都给",
                                    ),
                                    "enum" to RuntimeJSONValue.ArrayValue(
                                        library.scenes.map { RuntimeJSONValue.StringValue(it) },
                                    ),
                                ),
                            ),
                            "part" to RuntimeJSONValue.ObjectValue(
                                mapOf(
                                    "type" to RuntimeJSONValue.StringValue("string"),
                                    "description" to RuntimeJSONValue.StringValue(
                                        "练哪儿。他说「练胸」「练腿」时用这个，和 scene 至少给一个",
                                    ),
                                    "enum" to RuntimeJSONValue.ArrayValue(
                                        regions.map { RuntimeJSONValue.StringValue(it) },
                                    ),
                                ),
                            ),
                            "equipment" to RuntimeJSONValue.ObjectValue(
                                mapOf(
                                    "type" to RuntimeJSONValue.StringValue("array"),
                                    "description" to RuntimeJSONValue.StringValue(
                                        "他手边有什么。**不确定就别传**——不传时只给徒手和家里现成的东西（墙、门框、毛巾、椅子）。" +
                                            "他说了在健身房、或者说了有哑铃有弹力带，才把对应的几样列进来。列了他没有的，换回来的是一张他做不了的卡",
                                    ),
                                    "items" to RuntimeJSONValue.ObjectValue(
                                        mapOf(
                                            "type" to RuntimeJSONValue.StringValue("string"),
                                            "enum" to RuntimeJSONValue.ArrayValue(
                                                equipmentKinds.map { RuntimeJSONValue.StringValue(it) },
                                            ),
                                        ),
                                    ),
                                ),
                            ),
                            "advanced" to RuntimeJSONValue.ObjectValue(
                                mapOf(
                                    "type" to RuntimeJSONValue.StringValue("boolean"),
                                    "description" to RuntimeJSONValue.StringValue(
                                        "他明确说了想练难一点的、或者说了自己一直在健身，才传 true。" +
                                            "默认不给单腿深蹲、倒立俯卧撑这一类需要基础的动作",
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
                    // scene 和 part 都不是必填，但**至少要有一个**——这一条 JSON Schema 表达不了
                    // （anyOf 在几家 provider 的 strict 模式下都不保证支持），所以写在两处的
                    // description 里，执行那一侧再兜一道。
                    "required" to RuntimeJSONValue.ArrayValue(emptyList()),
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
        val region = input?.get("part")?.stringValue.orEmpty()
        val excluded = input?.get("excludeJoint")?.arrayValue?.mapNotNull { it.stringValue }.orEmpty()
        val noFloor = input?.get("noFloor")?.boolValue == true
        val advanced = input?.get("advanced")?.boolValue == true
        // **没传和传了空数组是两回事。** 没传是「不知道他有什么」，走家里现成的那几样；
        // 传了空数组是模型明确说了「什么都没有」，那就只剩徒手。分不开的话，一次
        // `"equipment": []` 会被当成没问过，照样给他一张椅子上的动作。
        val equipment = input?.get("equipment")?.arrayValue?.mapNotNull { it.stringValue }
        val count = input?.get("count")?.intValue ?: 3
        val picked = library.suggest(
            scene = scene,
            region = region,
            excludeJoints = excluded,
            avoidsFloor = noFloor,
            equipment = equipment,
            includesAdvanced = advanced,
            limit = count,
        )
        if (picked.isEmpty()) {
            return CapabilityExecutionResult(
                output = AgentToolOutput(
                    kind = AgentToolOutput.Kind.TEXT,
                    text = emptyText(scene, region, excluded, equipment),
                ),
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

    /**
     * 挑不到时说给模型听的那一段。
     *
     * **要说清是被哪一条挡住的。** 「没有可推荐的动作」是一句死路：模型只能原样转告，而用户
     * 其实只要补一句「我有哑铃」就能拿到一整组。所以把当时的条件念回去——器械那一档尤其要念，
     * 因为它有一个**用户从没说过的默认值**（不传就只给徒手和家里现成的），不念的话那次落空在
     * 他看来毫无道理。
     */
    fun emptyText(
        scene: String,
        region: String = "",
        excluded: List<String> = emptyList(),
        equipment: List<String>? = null,
    ): String {
        val asked = listOfNotNull(
            scene.takeIf { it.isNotBlank() }?.let { "「$it」" },
            region.takeIf { it.isNotBlank() }?.let { "「$it」" },
        ).joinToString("、")
        if (asked.isEmpty()) {
            return "这次调用没说要什么：scene（什么场合）和 part（练哪儿）至少要给一个。" +
                "重新调一次，别自己编一个动作出来。"
        }

        var text = "动作库里 $asked 这一类"
        if (excluded.isNotEmpty()) {
            text += "，避开${excluded.joinToString("、")}之后"
        }
        text += "没有可推荐的动作。"
        text += if (equipment != null) {
            "这次限定了只用${equipment.ifEmpty { listOf("徒手") }.joinToString("、")}。"
        } else {
            "这次没有指定器械，所以只找了徒手和家里现成的东西（墙、门框、毛巾、椅子）能做的。" +
                "他要是在健身房、或者手边有哑铃弹力带，问一句再调一次就有了。"
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
