package com.pinapia.vana.medications

import com.pinapia.vana.agentruntime.AgentToolOutput
import com.pinapia.vana.agentruntime.CapabilityDefinition
import com.pinapia.vana.agentruntime.CapabilityExecutionResult
import com.pinapia.vana.agentruntime.CapabilityInvocation
import com.pinapia.vana.agentruntime.CapabilityRegistry
import com.pinapia.vana.agentruntime.RuntimeJSONValue

object MedicationTools {
    const val LIST = "list_medications"
    const val LOG = "log_medication"
    const val UPDATE = "update_medication"

    fun registry(store: MedicationStore, allowsWrites: Boolean): CapabilityRegistry {
        val definitions = buildList {
            add(listDefinition())
            if (allowsWrites) {
                add(logDefinition())
                add(updateDefinition())
            }
        }
        return CapabilityRegistry(definitions = definitions) { invocation ->
            when (invocation.name) {
                LIST -> list(store)
                LOG -> if (allowsWrites) log(store, invocation) else writeDenied()
                UPDATE -> if (allowsWrites) update(store, invocation) else writeDenied()
                else -> CapabilityExecutionResult(
                    output = AgentToolOutput(kind = AgentToolOutput.Kind.TEXT, text = "不支持名为 ${invocation.name} 的工具。"),
                    isError = true,
                )
            }
        }
    }

    private fun writeDenied() = CapabilityExecutionResult(
        output = AgentToolOutput(kind = AgentToolOutput.Kind.TEXT, text = "当前会话不允许写入用药表。"),
        isError = true,
    )

    private fun list(store: MedicationStore): CapabilityExecutionResult {
        val items = store.load()
        if (items.isEmpty()) {
            return CapabilityExecutionResult(
                output = AgentToolOutput(kind = AgentToolOutput.Kind.TEXT, text = "用药与补剂清单是空的。"),
            )
        }
        val text = items.joinToString("\n") { item ->
            buildString {
                append("- [${item.status.label}] ${item.name}")
                if (item.whenText.isNotBlank()) append("；什么情况下吃：${item.whenText}")
                if (item.reason.isNotBlank()) append("；为什么吃：${item.reason}")
                if (item.outcome.isNotBlank()) append("；自己的评价：${item.outcome}")
                if (item.brief.isNotBlank()) append("；一般说明：${item.brief}")
                if (item.note.isNotBlank()) append("；备注：${item.note}")
            }
        }
        return CapabilityExecutionResult(
            output = AgentToolOutput(kind = AgentToolOutput.Kind.TEXT, text = text),
        )
    }

    private fun log(store: MedicationStore, invocation: CapabilityInvocation): CapabilityExecutionResult {
        val input = runCatching { RuntimeJSONValue.decode(from = invocation.input) }.getOrNull()
        val name = input?.get("name")?.stringValue?.trim().orEmpty().take(20)
        val status = parseStatus(input?.get("status")?.stringValue)
        if (name.isEmpty() || status == null) {
            return CapabilityExecutionResult(
                output = AgentToolOutput(kind = AgentToolOutput.Kind.TEXT, text = "log_medication 需要 name 和合法的 status。"),
                isError = true,
            )
        }
        val followUpDays = input?.get("followUpDays")?.intValue
        val item = store.upsert(
            name = name,
            status = status,
            whenText = input?.get("when")?.stringValue.orEmpty(),
            reason = input?.get("reason")?.stringValue.orEmpty(),
            followUpDays = followUpDays,
            origin = MedicationItem.Origin.ASKED,
        ) ?: return CapabilityExecutionResult(
            output = AgentToolOutput(kind = AgentToolOutput.Kind.TEXT, text = "用药表已满，这条没有记下来。"),
            isError = true,
        )
        return CapabilityExecutionResult(
            output = AgentToolOutput(
                kind = AgentToolOutput.Kind.TEXT,
                text = "已记下「${item.name}」（${item.status.label}）",
            ),
        )
    }

    private fun update(store: MedicationStore, invocation: CapabilityInvocation): CapabilityExecutionResult {
        val input = runCatching { RuntimeJSONValue.decode(from = invocation.input) }.getOrNull()
        val name = input?.get("name")?.stringValue?.trim().orEmpty()
        val items = store.load()
        if (items.none { it.name.equals(name, ignoreCase = true) }) {
            val names = items.joinToString("、") { it.name }.ifEmpty { "（空）" }
            return CapabilityExecutionResult(
                output = AgentToolOutput(
                    kind = AgentToolOutput.Kind.TEXT,
                    text = "没找到名为「$name」的条目。现有：$names",
                ),
                isError = true,
            )
        }
        val outcome = input?.get("outcome")?.stringValue
        val updated = store.update(
            name = name,
            status = parseStatus(input?.get("status")?.stringValue),
            outcome = outcome,
            whenText = input?.get("when")?.stringValue,
            reason = input?.get("reason")?.stringValue,
            clearFollowUp = !outcome.isNullOrBlank(),
        ) ?: return CapabilityExecutionResult(
            output = AgentToolOutput(kind = AgentToolOutput.Kind.TEXT, text = "更新失败。"),
            isError = true,
        )
        return CapabilityExecutionResult(
            output = AgentToolOutput(
                kind = AgentToolOutput.Kind.TEXT,
                text = "已更新「${updated.name}」",
            ),
        )
    }

    private fun parseStatus(raw: String?): MedicationItem.Status? = when (raw) {
        "cannotTake" -> MedicationItem.Status.CANNOT_TAKE
        "ongoing" -> MedicationItem.Status.ONGOING
        "asNeeded" -> MedicationItem.Status.AS_NEEDED
        "tried" -> MedicationItem.Status.TRIED
        else -> null
    }

    private fun listDefinition() = CapabilityDefinition(
        name = LIST,
        description = "读用户记下的全部用药与补剂。系统提示里已经有一份精简名单，" +
            "只在需要名单上没有的细节时调用。",
        inputSchema = objectSchema(emptyMap()),
    )

    private fun logDefinition() = CapabilityDefinition(
        name = LOG,
        description = "把一样药或补剂记进用户的清单。在他说出自己和某样东西的关系时调用。" +
            "同名的会覆盖原来那条。剂量不要记进来。",
        inputSchema = objectSchema(
            mapOf(
                "name" to stringProp("药或补剂的名字，用他说的那个叫法，不超过 20 个字"),
                "status" to enumProp(
                    listOf("cannotTake", "ongoing", "asNeeded", "tried"),
                    "cannotTake 过敏或不能吃、ongoing 长期在吃、asNeeded 需要时才吃、tried 试过之后的结论",
                ),
                "when" to stringProp("什么情况下吃，如「头疼时」「每天早上」「冬天」。不知道就留空"),
                "reason" to stringProp("为什么吃，或者谁让他吃的。一句话，不超过 30 个字"),
                "followUpDays" to RuntimeJSONValue.ObjectValue(
                    mapOf(
                        "type" to RuntimeJSONValue.StringValue("integer"),
                        "description" to RuntimeJSONValue.StringValue(
                            "他是刚开始试这个东西时传：几天后回头问他有没有用，范围 3–180。" +
                                "补剂见效慢，给长一点。已经吃了很久的不用传",
                        ),
                        "minimum" to RuntimeJSONValue.IntValue(3),
                        "maximum" to RuntimeJSONValue.IntValue(180),
                    ),
                ),
            ),
            required = listOf("name", "status"),
        ),
    )

    private fun updateDefinition() = CapabilityDefinition(
        name = UPDATE,
        description = "按名字更新用药表里已有条目的状态或他自己的效果评价。",
        inputSchema = objectSchema(
            mapOf(
                "name" to stringProp("要更新的药名"),
                "status" to enumProp(listOf("cannotTake", "ongoing", "asNeeded", "tried")),
                "when" to stringProp("什么情况下吃"),
                "reason" to stringProp("为什么吃"),
                "outcome" to stringProp("他自己的效果评价，不超过 30 个字"),
            ),
            required = listOf("name"),
        ),
    )

    private fun objectSchema(
        properties: Map<String, RuntimeJSONValue>,
        required: List<String> = emptyList(),
    ): RuntimeJSONValue = RuntimeJSONValue.ObjectValue(
        buildMap {
            put("type", RuntimeJSONValue.StringValue("object"))
            put("properties", RuntimeJSONValue.ObjectValue(properties))
            put("required", RuntimeJSONValue.ArrayValue(required.map { RuntimeJSONValue.StringValue(it) }))
            put("additionalProperties", RuntimeJSONValue.BoolValue(false))
        },
    )

    private fun stringProp(description: String) = RuntimeJSONValue.ObjectValue(
        mapOf(
            "type" to RuntimeJSONValue.StringValue("string"),
            "description" to RuntimeJSONValue.StringValue(description),
        ),
    )

    private fun enumProp(values: List<String>, description: String? = null) = RuntimeJSONValue.ObjectValue(
        buildMap {
            put("type", RuntimeJSONValue.StringValue("string"))
            put("enum", RuntimeJSONValue.ArrayValue(values.map { RuntimeJSONValue.StringValue(it) }))
            if (description != null) {
                put("description", RuntimeJSONValue.StringValue(description))
            }
        },
    )
}
