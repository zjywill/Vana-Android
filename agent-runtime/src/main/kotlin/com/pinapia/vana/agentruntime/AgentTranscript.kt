package com.pinapia.vana.agentruntime

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

typealias RuntimeProviderMetadata = Map<String, Map<String, RuntimeJSONValue>>

@Serializable
data class AgentTranscript(
    val messages: List<Message> = emptyList(),
) {
    @Serializable
    enum class Role {
        @SerialName("system") SYSTEM,
        @SerialName("user") USER,
        @SerialName("assistant") ASSISTANT,
        @SerialName("tool") TOOL,
        ;

        val rawValue: String
            get() = when (this) {
                SYSTEM -> "system"
                USER -> "user"
                ASSISTANT -> "assistant"
                TOOL -> "tool"
            }
    }

    @Serializable
    sealed class Part {
        @Serializable
        @SerialName("text")
        data class Text(val text: String) : Part()

        @Serializable
        @SerialName("reasoning")
        data class Reasoning(
            val text: String,
            val metadata: RuntimeProviderMetadata = emptyMap(),
        ) : Part()

        @Serializable
        @SerialName("toolCall")
        data class ToolCallPart(val toolCall: ToolCall) : Part()

        @Serializable
        @SerialName("toolResult")
        data class ToolResultPart(val toolResult: ToolResult) : Part()

        @Serializable
        @SerialName("file")
        data class File(val file: FilePart) : Part()
    }

    @Serializable
    data class FilePart(
        val mediaType: String,
        val data: Payload,
        val filename: String? = null,
    ) {
        @Serializable
        sealed class Payload {
            @Serializable
            @SerialName("base64")
            data class Base64(val value: String) : Payload()

            @Serializable
            @SerialName("url")
            data class Url(val value: String) : Payload()
        }
    }

    @Serializable
    data class ToolCall(
        val toolCallId: String,
        val toolName: String,
        val input: String,
        val providerExecuted: Boolean = false,
        val dynamic: Boolean = false,
        val metadata: RuntimeProviderMetadata = emptyMap(),
    )

    @Serializable
    data class ToolResult(
        val toolCallId: String,
        val toolName: String,
        val result: RuntimeJSONValue,
        val isError: Boolean = false,
        val preliminary: Boolean = false,
        val dynamic: Boolean = false,
        val metadata: RuntimeProviderMetadata = emptyMap(),
    )

    @Serializable
    data class Message(
        val role: Role,
        val parts: List<Part>,
        val metadata: RuntimeProviderMetadata = emptyMap(),
    ) {
        val text: String
            get() = parts.mapNotNull { (it as? Part.Text)?.text }.joinToString("")

        val isEmpty: Boolean
            get() = parts.isEmpty() || parts.all {
                when (it) {
                    is Part.Text -> it.text.isEmpty()
                    else -> false
                }
            }

        val compactionSourceIDs: List<java.util.UUID>
            get() = metadata["agent-runtime"]
                ?.get("compaction_sources")
                ?.arrayValue
                ?.mapNotNull { it.stringValue?.let(java.util.UUID::fromString) }
                ?: emptyList()

        companion object {
            fun system(text: String): Message =
                Message(role = Role.SYSTEM, parts = listOf(Part.Text(text)))

            fun user(text: String): Message =
                Message(role = Role.USER, parts = listOf(Part.Text(text)))

            fun assistant(text: String): Message =
                Message(role = Role.ASSISTANT, parts = listOf(Part.Text(text)))

            fun toolResult(
                toolCallId: String,
                toolName: String,
                result: RuntimeJSONValue,
                isError: Boolean = false,
            ): Message = Message(
                role = Role.TOOL,
                parts = listOf(
                    Part.ToolResultPart(
                        ToolResult(
                            toolCallId = toolCallId,
                            toolName = toolName,
                            result = result,
                            isError = isError,
                        ),
                    ),
                ),
            )

            /**
             * 摘要消息带上它是从哪几条压出来的,方便以后回溯或者再压一层。
             */
            fun assistantSummary(text: String, sourceIDs: List<java.util.UUID>): Message =
                Message(
                    role = Role.ASSISTANT,
                    parts = listOf(Part.Text(text)),
                    metadata = mapOf(
                        "agent-runtime" to mapOf(
                            "compaction_sources" to RuntimeJSONValue.arr(
                                sourceIDs.map { RuntimeJSONValue.string(it.toString()) },
                            ),
                        ),
                    ),
                )
        }
    }

    /**
     * 摊平成人和模型都读得懂的纯文本。给总结用。
     *
     * 工具调用和结果也摊进来——一段对话里最值钱的信息往往就在工具结果的数字上,
     * 只摊 `.text` 等于把要保住的东西先扔了。
     */
    fun plainTextRendering(maxCharactersPerPart: Int = 2_000): String =
        messages.mapNotNull { message ->
            val rendered = message.parts.mapNotNull { part ->
                when (part) {
                    is Part.Text -> part.text.takeIf { it.isNotEmpty() }
                    is Part.Reasoning -> null
                    is Part.ToolCallPart -> {
                        val call = part.toolCall
                        "[tool] ${call.toolName} ${call.input}"
                    }
                    is Part.ToolResultPart -> {
                        val result = part.toolResult
                        val value = result.result.stringValue
                            ?: runCatching { result.result.encodedString() }.getOrDefault("")
                        val clipped = if (value.length > maxCharactersPerPart) {
                            value.take(maxCharactersPerPart) + "…"
                        } else {
                            value
                        }
                        "[result] ${result.toolName} → $clipped"
                    }
                    is Part.File -> "[file] ${part.file.filename ?: part.file.mediaType}"
                }
            }.joinToString("\n")
            if (rendered.isEmpty()) null else "${message.role.rawValue}: $rendered"
        }.joinToString("\n\n")
}
