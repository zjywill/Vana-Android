package com.pinapia.vana.agentruntime

import kotlinx.serialization.Serializable

@Serializable
data class CapabilityDefinition(
    val name: String,
    val description: String? = null,
    val inputSchema: RuntimeJSONValue,
    val strictPreferred: Boolean = false,
)

@Serializable
data class CapabilityInvocation(
    val toolCallId: String,
    val name: String,
    val input: String,
)

@Serializable
data class CapabilityExecutionResult(
    val output: AgentToolOutput,
    val isError: Boolean = false,
)

/**
 * runtime 和「能干什么」之间的唯一接口。
 *
 * HealthKit、Calendar、Files 在这一层长得一模一样:一组 JSON Schema 加一个执行闭包。
 * loop 不知道自己在查步数还是在读日历,这正是它能被别的 app 复用的原因。
 */
class CapabilityRegistry(
    val definitions: List<CapabilityDefinition>,
    private val execute: suspend (CapabilityInvocation) -> CapabilityExecutionResult,
) {
    suspend fun execute(invocation: CapabilityInvocation): CapabilityExecutionResult =
        execute.invoke(invocation)

    fun definition(named: String): CapabilityDefinition? =
        definitions.firstOrNull { it.name == named }

    companion object {
        val empty = CapabilityRegistry(definitions = emptyList()) { invocation ->
            CapabilityExecutionResult(
                output = AgentToolOutput(
                    kind = AgentToolOutput.Kind.TEXT,
                    text = "no capability named ${invocation.name}",
                ),
                isError = true,
            )
        }
    }
}
