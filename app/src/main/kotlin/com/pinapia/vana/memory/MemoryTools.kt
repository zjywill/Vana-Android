package com.pinapia.vana.memory

import com.pinapia.vana.agentruntime.CapabilityDefinition
import com.pinapia.vana.agentruntime.CapabilityExecutionResult
import com.pinapia.vana.agentruntime.CapabilityInvocation
import com.pinapia.vana.agentruntime.CapabilityRegistry
import com.pinapia.vana.agentruntime.AgentToolOutput
import com.pinapia.vana.agentruntime.RuntimeJSONValue

object MemoryTools {
    const val REMEMBER = "remember"

    fun registry(store: MemoryStore): CapabilityRegistry {
        val definition = CapabilityDefinition(
            name = REMEMBER,
            description = "当用户明确要求记住某件关于自己的长期情况、偏好或约定时调用。" +
                "不要记 Health Connect 能查到的数字。" +
                "用药与补剂请走用药表工具；口述的身高体重心率血压等测量请走 log_measurement，不要用 remember。",
            inputSchema = RuntimeJSONValue.ObjectValue(
                mapOf(
                    "type" to RuntimeJSONValue.StringValue("object"),
                    "properties" to RuntimeJSONValue.ObjectValue(
                        mapOf(
                            "text" to RuntimeJSONValue.ObjectValue(
                                mapOf(
                                    "type" to RuntimeJSONValue.StringValue("string"),
                                    "description" to RuntimeJSONValue.StringValue(
                                        "用中文第三人称写，一句话，不超过 40 个字",
                                    ),
                                ),
                            ),
                            "kind" to RuntimeJSONValue.ObjectValue(
                                mapOf(
                                    "type" to RuntimeJSONValue.StringValue("string"),
                                    "enum" to RuntimeJSONValue.ArrayValue(
                                        listOf("profile", "preference", "interpretation", "followUp")
                                            .map { RuntimeJSONValue.StringValue(it) },
                                    ),
                                ),
                            ),
                            "days" to RuntimeJSONValue.ObjectValue(
                                mapOf(
                                    "type" to RuntimeJSONValue.StringValue("integer"),
                                    "description" to RuntimeJSONValue.StringValue(
                                        "仅 followUp：几天后回头看，范围 1–180，默认 14",
                                    ),
                                    "minimum" to RuntimeJSONValue.IntValue(1),
                                    "maximum" to RuntimeJSONValue.IntValue(180),
                                ),
                            ),
                        ),
                    ),
                    "required" to RuntimeJSONValue.ArrayValue(
                        listOf("text", "kind").map { RuntimeJSONValue.StringValue(it) },
                    ),
                    "additionalProperties" to RuntimeJSONValue.BoolValue(false),
                ),
            ),
            strictPreferred = false,
        )

        return CapabilityRegistry(definitions = listOf(definition)) { invocation ->
            execute(store, invocation)
        }
    }

    private fun execute(store: MemoryStore, invocation: CapabilityInvocation): CapabilityExecutionResult {
        val input = runCatching { RuntimeJSONValue.decode(from = invocation.input) }.getOrNull()
        val text = input?.get("text")?.stringValue?.trim().orEmpty()
        val kindRaw = input?.get("kind")?.stringValue.orEmpty()
        val kind = when (kindRaw) {
            "profile" -> MemoryItem.Kind.PROFILE
            "preference" -> MemoryItem.Kind.PREFERENCE
            "interpretation" -> MemoryItem.Kind.INTERPRETATION
            "followUp" -> MemoryItem.Kind.FOLLOW_UP
            else -> null
        }
        if (text.isEmpty() || kind == null) {
            return CapabilityExecutionResult(
                output = AgentToolOutput(kind = AgentToolOutput.Kind.TEXT, text = "remember 需要 text 和合法的 kind。"),
                isError = true,
            )
        }
        val days = input?.get("days")?.intValue
        val item = store.remember(text = text, kind = kind, days = days)
            ?: return CapabilityExecutionResult(
                output = AgentToolOutput(
                    kind = AgentToolOutput.Kind.TEXT,
                    text = "记忆已满，这条没有记下来。让用户到设置里清理一下。",
                ),
                isError = true,
            )
        return CapabilityExecutionResult(
            output = AgentToolOutput(
                kind = AgentToolOutput.Kind.TEXT,
                text = "已记住：${item.text}",
            ),
        )
    }
}
