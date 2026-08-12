package com.pinapia.vana.agent

import com.pinapia.vana.agentruntime.AgentFinishReason
import com.pinapia.vana.agentruntime.AgentModelClient
import com.pinapia.vana.agentruntime.AgentModelProfile
import com.pinapia.vana.agentruntime.AgentModelRequest
import com.pinapia.vana.agentruntime.AgentModelResponse
import com.pinapia.vana.agentruntime.AgentModelStreamEvent
import com.pinapia.vana.agentruntime.AgentTranscript
import com.pinapia.vana.agentruntime.AgentUsage
import com.pinapia.vana.agentruntime.CapabilityDefinition
import com.pinapia.vana.agentruntime.CapabilityInvocation
import com.pinapia.vana.settings.CloudCatalog
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources

/**
 * OpenAI Chat Completions SSE + Anthropic Messages SSE。
 */
class OpenAICompatibleModelClient(
    override val profile: AgentModelProfile,
    private val apiKey: String,
    private val baseUrl: String,
    private val wireProtocol: CloudCatalog.WireProtocol,
    private val thinkingEnabled: Boolean = false,
    private val supportsReasoning: Boolean = false,
    private val httpClient: OkHttpClient = defaultClient,
) : AgentModelClient {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override fun estimateTokens(request: AgentModelRequest): Int {
        val chars = request.prompt.messages.sumOf { message ->
            message.parts.sumOf { part ->
                when (part) {
                    is AgentTranscript.Part.Text -> part.text.length
                    is AgentTranscript.Part.Reasoning -> part.text.length
                    is AgentTranscript.Part.ToolCallPart -> part.toolCall.input.length + part.toolCall.toolName.length
                    is AgentTranscript.Part.ToolResultPart -> part.toolResult.result.encodedString().length
                    is AgentTranscript.Part.File -> 100
                }
            }
        } + request.capabilities.sumOf { it.name.length + (it.description?.length ?: 0) + 80 }
        return (chars / 4).coerceAtLeast(1)
    }

    override fun stream(request: AgentModelRequest): Flow<AgentModelStreamEvent> = callbackFlow {
        val body = when (wireProtocol) {
            CloudCatalog.WireProtocol.OPENAI -> openaiBody(request)
            CloudCatalog.WireProtocol.ANTHROPIC -> anthropicBody(request)
        }
        val url = when (wireProtocol) {
            CloudCatalog.WireProtocol.OPENAI -> joinUrl(baseUrl, "chat/completions")
            CloudCatalog.WireProtocol.ANTHROPIC -> joinUrl(baseUrl, "v1/messages")
        }
        val builder = Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("Content-Type", "application/json")
        when (wireProtocol) {
            CloudCatalog.WireProtocol.OPENAI -> {
                builder.header("Authorization", "Bearer $apiKey")
            }
            CloudCatalog.WireProtocol.ANTHROPIC -> {
                builder.header("x-api-key", apiKey)
                builder.header("anthropic-version", "2023-06-01")
            }
        }

        val text = StringBuilder()
        val reasoning = StringBuilder()
        val toolCalls = linkedMapOf<Int, MutableToolCall>()
        var finishReason: String? = null
        var usage: AgentUsage? = null
        var failure: String? = null
        var servedModelId: String? = null

        val listener = object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (data == "[DONE]") return
                try {
                    when (wireProtocol) {
                        CloudCatalog.WireProtocol.OPENAI -> {
                            val root = json.parseToJsonElement(data).jsonObject
                            servedModelId = root["model"]?.jsonPrimitive?.contentOrNull ?: servedModelId
                            root["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull?.let {
                                failure = it
                            }
                            root["usage"]?.jsonObject?.let { usage = parseUsage(it) }
                            val choices = root["choices"]?.jsonArray ?: return
                            for (choice in choices) {
                                val obj = choice.jsonObject
                                obj["finish_reason"]?.jsonPrimitive?.contentOrNull?.let { finishReason = it }
                                val delta = obj["delta"]?.jsonObject ?: continue
                                delta["content"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }?.let {
                                    text.append(it)
                                    trySend(AgentModelStreamEvent.TextDelta(it))
                                }
                                delta["reasoning_content"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }?.let {
                                    reasoning.append(it)
                                    trySend(AgentModelStreamEvent.ReasoningDelta(it))
                                }
                                delta["tool_calls"]?.jsonArray?.forEach { element ->
                                    val call = element.jsonObject
                                    val index = call["index"]?.jsonPrimitive?.intOrNull ?: 0
                                    val bucket = toolCalls.getOrPut(index) { MutableToolCall() }
                                    call["id"]?.jsonPrimitive?.contentOrNull?.let { bucket.id = it }
                                    call["function"]?.jsonObject?.let { fn ->
                                        fn["name"]?.jsonPrimitive?.contentOrNull?.let { bucket.name += it }
                                        fn["arguments"]?.jsonPrimitive?.contentOrNull?.let { bucket.arguments += it }
                                    }
                                }
                            }
                        }
                        CloudCatalog.WireProtocol.ANTHROPIC -> {
                            val root = json.parseToJsonElement(data).jsonObject
                            when (type ?: root["type"]?.jsonPrimitive?.contentOrNull) {
                                "error" -> {
                                    failure = root["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
                                        ?: "anthropic error"
                                }
                                "content_block_delta" -> {
                                    val delta = root["delta"]?.jsonObject
                                    when (delta?.get("type")?.jsonPrimitive?.contentOrNull) {
                                        "text_delta" -> delta["text"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }?.let {
                                            text.append(it)
                                            trySend(AgentModelStreamEvent.TextDelta(it))
                                        }
                                        "input_json_delta" -> {
                                            val index = root["index"]?.jsonPrimitive?.intOrNull ?: 0
                                            val bucket = toolCalls.getOrPut(index) { MutableToolCall() }
                                            delta["partial_json"]?.jsonPrimitive?.contentOrNull?.let {
                                                bucket.arguments += it
                                            }
                                        }
                                        "thinking_delta" -> delta["thinking"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }?.let {
                                            reasoning.append(it)
                                            trySend(AgentModelStreamEvent.ReasoningDelta(it))
                                        }
                                    }
                                }
                                "content_block_start" -> {
                                    val block = root["content_block"]?.jsonObject
                                    if (block?.get("type")?.jsonPrimitive?.contentOrNull == "tool_use") {
                                        val index = root["index"]?.jsonPrimitive?.intOrNull ?: 0
                                        val bucket = toolCalls.getOrPut(index) { MutableToolCall() }
                                        bucket.id = block["id"]?.jsonPrimitive?.contentOrNull ?: bucket.id
                                        bucket.name = block["name"]?.jsonPrimitive?.contentOrNull ?: bucket.name
                                    }
                                }
                                "message_delta" -> {
                                    root["delta"]?.jsonObject?.get("stop_reason")?.jsonPrimitive?.contentOrNull?.let {
                                        finishReason = it
                                    }
                                    root["usage"]?.jsonObject?.let { usage = parseAnthropicUsage(it, usage) }
                                }
                                "message_start" -> {
                                    root["message"]?.jsonObject?.get("model")?.jsonPrimitive?.contentOrNull?.let {
                                        servedModelId = it
                                    }
                                    root["message"]?.jsonObject?.get("usage")?.jsonObject?.let {
                                        usage = parseAnthropicUsage(it, usage)
                                    }
                                }
                            }
                        }
                    }
                } catch (error: Throwable) {
                    failure = error.message ?: "parse error"
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: okhttp3.Response?) {
                val bodyText = response?.body?.string()
                val message = bodyText?.let { extractErrorMessage(it) }
                    ?: t?.message
                    ?: "HTTP ${response?.code ?: "?"}"
                trySend(
                    AgentModelStreamEvent.Completed(
                        AgentModelResponse(failureMessage = message),
                    ),
                )
                close()
            }

            override fun onClosed(eventSource: EventSource) {
                val pending = toolCalls.entries.sortedBy { it.key }.mapNotNull { (_, call) ->
                    if (call.name.isBlank()) return@mapNotNull null
                    CapabilityInvocation(
                        toolCallId = call.id.ifBlank { UUID.randomUUID().toString() },
                        name = call.name,
                        input = call.arguments.ifBlank { "{}" },
                    )
                }
                val parts = mutableListOf<AgentTranscript.Part>()
                if (reasoning.isNotEmpty()) {
                    parts += AgentTranscript.Part.Reasoning(reasoning.toString())
                }
                if (text.isNotEmpty()) {
                    parts += AgentTranscript.Part.Text(text.toString())
                }
                parts += pending.map {
                    AgentTranscript.Part.ToolCallPart(
                        AgentTranscript.ToolCall(
                            toolCallId = it.toolCallId,
                            toolName = it.name,
                            input = it.input,
                        ),
                    )
                }
                val assistant = if (parts.isEmpty()) {
                    null
                } else {
                    AgentTranscript.Message(role = AgentTranscript.Role.ASSISTANT, parts = parts)
                }
                trySend(
                    AgentModelStreamEvent.Completed(
                        AgentModelResponse(
                            assistantMessage = assistant,
                            pendingCalls = pending,
                            finishReason = mapFinishReason(finishReason, pending.isNotEmpty()),
                            usage = usage,
                            servedModelId = servedModelId,
                            failureMessage = failure,
                        ),
                    ),
                )
                close()
            }
        }

        val eventSource = EventSources.createFactory(httpClient).newEventSource(builder.build(), listener)
        awaitClose { eventSource.cancel() }
    }

    private fun openaiBody(request: AgentModelRequest): String {
        val messages = buildJsonArray {
            for (message in request.prompt.messages) {
                when (message.role) {
                    AgentTranscript.Role.SYSTEM -> add(
                        buildJsonObject {
                            put("role", "system")
                            put("content", message.text)
                        },
                    )
                    AgentTranscript.Role.USER -> add(userContentOpenAI(message))
                    AgentTranscript.Role.ASSISTANT -> {
                        val toolCalls = message.parts.filterIsInstance<AgentTranscript.Part.ToolCallPart>()
                        add(
                            buildJsonObject {
                                put("role", "assistant")
                                val text = message.parts.filterIsInstance<AgentTranscript.Part.Text>()
                                    .joinToString("") { it.text }
                                if (text.isNotEmpty() || toolCalls.isEmpty()) put("content", text)
                                val reasoningText = message.parts.filterIsInstance<AgentTranscript.Part.Reasoning>()
                                    .joinToString("") { it.text }
                                if (reasoningText.isNotEmpty()) put("reasoning_content", reasoningText)
                                if (toolCalls.isNotEmpty()) {
                                    put(
                                        "tool_calls",
                                        buildJsonArray {
                                            toolCalls.forEach { part ->
                                                add(
                                                    buildJsonObject {
                                                        put("id", part.toolCall.toolCallId)
                                                        put("type", "function")
                                                        put(
                                                            "function",
                                                            buildJsonObject {
                                                                put("name", part.toolCall.toolName)
                                                                put("arguments", part.toolCall.input)
                                                            },
                                                        )
                                                    },
                                                )
                                            }
                                        },
                                    )
                                }
                            },
                        )
                    }
                    AgentTranscript.Role.TOOL -> {
                        message.parts.filterIsInstance<AgentTranscript.Part.ToolResultPart>().forEach { part ->
                            add(
                                buildJsonObject {
                                    put("role", "tool")
                                    put("tool_call_id", part.toolResult.toolCallId)
                                    put("content", part.toolResult.result.stringValue
                                        ?: part.toolResult.result.encodedString())
                                },
                            )
                        }
                    }
                }
            }
        }
        return buildJsonObject {
            put("model", request.profile.modelId)
            put("stream", true)
            put("messages", messages)
            if (request.capabilities.isNotEmpty()) {
                put("tools", toolsOpenAI(request.capabilities))
            }
            if (supportsReasoning) {
                // DeepSeek / Qwen / GLM：留空不等于关
                put("thinking", buildJsonObject {
                    put("type", if (thinkingEnabled) "enabled" else "disabled")
                })
            }
        }.toString()
    }

    private fun anthropicBody(request: AgentModelRequest): String {
        var system: String? = null
        val messages = buildJsonArray {
            for (message in request.prompt.messages) {
                when (message.role) {
                    AgentTranscript.Role.SYSTEM -> {
                        system = listOfNotNull(system, message.text).joinToString("\n\n")
                    }
                    AgentTranscript.Role.USER -> add(userContentAnthropic(message))
                    AgentTranscript.Role.ASSISTANT -> {
                        val content = buildJsonArray {
                            message.parts.forEach { part ->
                                when (part) {
                                    is AgentTranscript.Part.Text -> add(
                                        buildJsonObject {
                                            put("type", "text")
                                            put("text", part.text)
                                        },
                                    )
                                    is AgentTranscript.Part.ToolCallPart -> add(
                                        buildJsonObject {
                                            put("type", "tool_use")
                                            put("id", part.toolCall.toolCallId)
                                            put("name", part.toolCall.toolName)
                                            put("input", json.parseToJsonElement(part.toolCall.input.ifBlank { "{}" }))
                                        },
                                    )
                                    else -> Unit
                                }
                            }
                        }
                        add(
                            buildJsonObject {
                                put("role", "assistant")
                                put("content", content)
                            },
                        )
                    }
                    AgentTranscript.Role.TOOL -> {
                        val content = buildJsonArray {
                            message.parts.filterIsInstance<AgentTranscript.Part.ToolResultPart>().forEach { part ->
                                add(
                                    buildJsonObject {
                                        put("type", "tool_result")
                                        put("tool_use_id", part.toolResult.toolCallId)
                                        put(
                                            "content",
                                            part.toolResult.result.stringValue
                                                ?: part.toolResult.result.encodedString(),
                                        )
                                        if (part.toolResult.isError) put("is_error", true)
                                    },
                                )
                            }
                        }
                        add(
                            buildJsonObject {
                                put("role", "user")
                                put("content", content)
                            },
                        )
                    }
                }
            }
        }
        return buildJsonObject {
            put("model", request.profile.modelId)
            put("stream", true)
            put("max_tokens", request.profile.maxOutputTokens ?: 4096)
            system?.let { put("system", it) }
            put("messages", messages)
            if (request.capabilities.isNotEmpty()) {
                put("tools", toolsAnthropic(request.capabilities))
            }
        }.toString()
    }

    private fun userContentOpenAI(message: AgentTranscript.Message): JsonObject {
        val files = message.parts.filterIsInstance<AgentTranscript.Part.File>()
        val text = message.parts.filterIsInstance<AgentTranscript.Part.Text>()
            .joinToString("") { it.text }
            .ifBlank { message.text }
        if (files.isEmpty()) {
            return buildJsonObject {
                put("role", "user")
                put("content", text)
            }
        }
        return buildJsonObject {
            put("role", "user")
            put(
                "content",
                buildJsonArray {
                    if (text.isNotBlank()) {
                        add(
                            buildJsonObject {
                                put("type", "text")
                                put("text", text)
                            },
                        )
                    }
                    files.forEach { part ->
                        val base64 = (part.file.data as? AgentTranscript.FilePart.Payload.Base64)?.value
                            ?: return@forEach
                        add(
                            buildJsonObject {
                                put("type", "image_url")
                                put(
                                    "image_url",
                                    buildJsonObject {
                                        put("url", "data:${part.file.mediaType};base64,$base64")
                                    },
                                )
                            },
                        )
                    }
                },
            )
        }
    }

    private fun userContentAnthropic(message: AgentTranscript.Message): JsonObject {
        val files = message.parts.filterIsInstance<AgentTranscript.Part.File>()
        val text = message.parts.filterIsInstance<AgentTranscript.Part.Text>()
            .joinToString("") { it.text }
            .ifBlank { message.text }
        if (files.isEmpty()) {
            return buildJsonObject {
                put("role", "user")
                put("content", text)
            }
        }
        return buildJsonObject {
            put("role", "user")
            put(
                "content",
                buildJsonArray {
                    if (text.isNotBlank()) {
                        add(
                            buildJsonObject {
                                put("type", "text")
                                put("text", text)
                            },
                        )
                    }
                    files.forEach { part ->
                        val base64 = (part.file.data as? AgentTranscript.FilePart.Payload.Base64)?.value
                            ?: return@forEach
                        val media = part.file.mediaType.ifBlank { "image/jpeg" }
                        add(
                            buildJsonObject {
                                put("type", "image")
                                put(
                                    "source",
                                    buildJsonObject {
                                        put("type", "base64")
                                        put("media_type", media)
                                        put("data", base64)
                                    },
                                )
                            },
                        )
                    }
                },
            )
        }
    }

    private fun toolsOpenAI(capabilities: List<CapabilityDefinition>): JsonArray = buildJsonArray {
        capabilities.forEach { capability ->
            add(
                buildJsonObject {
                    put("type", "function")
                    put(
                        "function",
                        buildJsonObject {
                            put("name", capability.name)
                            capability.description?.let { put("description", it) }
                            put("parameters", json.parseToJsonElement(capability.inputSchema.encodedString()))
                        },
                    )
                },
            )
        }
    }

    private fun toolsAnthropic(capabilities: List<CapabilityDefinition>): JsonArray = buildJsonArray {
        capabilities.forEach { capability ->
            add(
                buildJsonObject {
                    put("name", capability.name)
                    capability.description?.let { put("description", it) }
                    put("input_schema", json.parseToJsonElement(capability.inputSchema.encodedString()))
                },
            )
        }
    }

    private fun parseUsage(obj: JsonObject): AgentUsage = AgentUsage(
        inputTokens = AgentUsage.Input(total = obj["prompt_tokens"]?.jsonPrimitive?.intOrNull),
        outputTokens = AgentUsage.Output(total = obj["completion_tokens"]?.jsonPrimitive?.intOrNull),
    )

    private fun parseAnthropicUsage(obj: JsonObject, previous: AgentUsage?): AgentUsage = AgentUsage(
        inputTokens = AgentUsage.Input(
            total = obj["input_tokens"]?.jsonPrimitive?.intOrNull ?: previous?.inputTokens?.total,
        ),
        outputTokens = AgentUsage.Output(
            total = obj["output_tokens"]?.jsonPrimitive?.intOrNull ?: previous?.outputTokens?.total,
        ),
    )

    private fun mapFinishReason(raw: String?, hasTools: Boolean): AgentFinishReason? {
        if (raw == null && !hasTools) return null
        val unified = when (raw) {
            "stop", "end_turn" -> AgentFinishReason.Unified.STOP
            "length", "max_tokens" -> AgentFinishReason.Unified.LENGTH
            "tool_calls", "tool_use" -> AgentFinishReason.Unified.TOOL_CALLS
            "content_filter" -> AgentFinishReason.Unified.CONTENT_FILTER
            null -> if (hasTools) AgentFinishReason.Unified.TOOL_CALLS else AgentFinishReason.Unified.STOP
            else -> AgentFinishReason.Unified.OTHER
        }
        return AgentFinishReason(unified = unified, raw = raw)
    }

    private fun extractErrorMessage(body: String): String {
        return runCatching {
            val root = json.parseToJsonElement(body).jsonObject
            root["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
                ?: root["message"]?.jsonPrimitive?.contentOrNull
                ?: body.take(300)
        }.getOrDefault(body.take(300))
    }

    private data class MutableToolCall(
        var id: String = "",
        var name: String = "",
        var arguments: String = "",
    )

    companion object {
        private val defaultClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        private fun joinUrl(base: String, path: String): String {
            val trimmed = base.trimEnd('/')
            return if (trimmed.endsWith("/v1") && path.startsWith("v1/")) {
                trimmed.removeSuffix("/v1") + "/" + path
            } else if (path.startsWith("chat/") && trimmed.endsWith("/v1")) {
                "$trimmed/$path"
            } else if (path == "chat/completions") {
                if (trimmed.endsWith("/v1")) "$trimmed/$path" else "$trimmed/v1/$path"
            } else {
                "$trimmed/$path"
            }
        }
    }
}
