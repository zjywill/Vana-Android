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
import com.pinapia.vana.agentruntime.RuntimeJSONValue
import com.pinapia.vana.settings.ApiKeyNormalizer
import com.pinapia.vana.settings.CloudCatalog
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources

/** `JsonNull` 不是 Kotlin null，`?.jsonObject` 拦不住，会直接抛。 */
private fun JsonElement?.asObjectOrNull(): JsonObject? = this as? JsonObject

private fun JsonElement?.asArrayOrNull(): JsonArray? = this as? JsonArray

private fun JsonElement?.asStringOrNull(): String? = (this as? JsonPrimitive)?.contentOrNull

private fun JsonElement?.asIntOrNull(): Int? = (this as? JsonPrimitive)?.intOrNull

/**
 * OpenAI Chat Completions、Anthropic Messages 和 Google Gemini SSE。
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
            CloudCatalog.WireProtocol.GEMINI -> geminiBody(request)
        }
        val url = when (wireProtocol) {
            CloudCatalog.WireProtocol.OPENAI -> joinUrl(baseUrl, "chat/completions")
            CloudCatalog.WireProtocol.ANTHROPIC -> joinUrl(baseUrl, "v1/messages")
            CloudCatalog.WireProtocol.GEMINI -> geminiEndpoint(baseUrl, request.profile.modelId)
        }
        val builder = Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
        when (wireProtocol) {
            CloudCatalog.WireProtocol.OPENAI -> {
                val key = ApiKeyNormalizer.normalize(apiKey)
                if (!key.isValid) {
                    trySend(
                        AgentModelStreamEvent.Completed(
                            AgentModelResponse(failureMessage = key.error ?: "API 密钥无效"),
                        ),
                    )
                    close()
                    return@callbackFlow
                }
                builder.header("Authorization", "Bearer ${key.value}")
            }
            CloudCatalog.WireProtocol.ANTHROPIC -> {
                val key = ApiKeyNormalizer.normalize(apiKey)
                if (!key.isValid) {
                    trySend(
                        AgentModelStreamEvent.Completed(
                            AgentModelResponse(failureMessage = key.error ?: "API 密钥无效"),
                        ),
                    )
                    close()
                    return@callbackFlow
                }
                builder.header("x-api-key", key.value)
                builder.header("anthropic-version", "2023-06-01")
            }
            CloudCatalog.WireProtocol.GEMINI -> {
                val key = ApiKeyNormalizer.normalize(apiKey)
                if (!key.isValid) {
                    trySend(
                        AgentModelStreamEvent.Completed(
                            AgentModelResponse(failureMessage = key.error ?: "API 密钥无效"),
                        ),
                    )
                    close()
                    return@callbackFlow
                }
                builder.header("x-goog-api-key", key.value)
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
                            val root = json.parseToJsonElement(data).asObjectOrNull() ?: return
                            servedModelId = root["model"].asStringOrNull() ?: servedModelId
                            root["error"].asObjectOrNull()?.get("message").asStringOrNull()?.let {
                                failure = it
                            } ?: root["error"].asStringOrNull()?.let { failure = it }
                            root["usage"].asObjectOrNull()?.let { usage = parseUsage(it) }
                            val choices = root["choices"].asArrayOrNull() ?: return
                            for (choice in choices) {
                                val obj = choice.asObjectOrNull() ?: continue
                                obj["finish_reason"].asStringOrNull()?.let { finishReason = it }
                                val delta = obj["delta"].asObjectOrNull() ?: continue
                                delta["content"].asStringOrNull()?.takeIf { it.isNotEmpty() }?.let {
                                    text.append(it)
                                    trySend(AgentModelStreamEvent.TextDelta(it))
                                }
                                delta["reasoning_content"].asStringOrNull()?.takeIf { it.isNotEmpty() }?.let {
                                    reasoning.append(it)
                                    trySend(AgentModelStreamEvent.ReasoningDelta(it))
                                }
                                delta["tool_calls"].asArrayOrNull()?.forEach { element ->
                                    val call = element.asObjectOrNull() ?: return@forEach
                                    val index = call["index"].asIntOrNull() ?: 0
                                    val bucket = toolCalls.getOrPut(index) { MutableToolCall() }
                                    call["id"].asStringOrNull()?.let { bucket.id = it }
                                    call["function"].asObjectOrNull()?.let { fn ->
                                        fn["name"].asStringOrNull()?.let { bucket.name += it }
                                        fn["arguments"].asStringOrNull()?.let { bucket.arguments += it }
                                    }
                                }
                            }
                        }
                        CloudCatalog.WireProtocol.ANTHROPIC -> {
                            val root = json.parseToJsonElement(data).asObjectOrNull() ?: return
                            when (type ?: root["type"].asStringOrNull()) {
                                "error" -> {
                                    failure = root["error"].asObjectOrNull()?.get("message").asStringOrNull()
                                        ?: root["error"].asStringOrNull()
                                        ?: "anthropic error"
                                }
                                "content_block_delta" -> {
                                    val delta = root["delta"].asObjectOrNull() ?: return
                                    when (delta["type"].asStringOrNull()) {
                                        "text_delta" -> delta["text"].asStringOrNull()?.takeIf { it.isNotEmpty() }?.let {
                                            text.append(it)
                                            trySend(AgentModelStreamEvent.TextDelta(it))
                                        }
                                        "input_json_delta" -> {
                                            val index = root["index"].asIntOrNull() ?: 0
                                            val bucket = toolCalls.getOrPut(index) { MutableToolCall() }
                                            delta["partial_json"].asStringOrNull()?.let {
                                                bucket.arguments += it
                                            }
                                        }
                                        "thinking_delta" -> delta["thinking"].asStringOrNull()?.takeIf { it.isNotEmpty() }?.let {
                                            reasoning.append(it)
                                            trySend(AgentModelStreamEvent.ReasoningDelta(it))
                                        }
                                    }
                                }
                                "content_block_start" -> {
                                    val block = root["content_block"].asObjectOrNull()
                                    if (block != null && block["type"].asStringOrNull() == "tool_use") {
                                        val index = root["index"].asIntOrNull() ?: 0
                                        val bucket = toolCalls.getOrPut(index) { MutableToolCall() }
                                        bucket.id = block["id"].asStringOrNull() ?: bucket.id
                                        bucket.name = block["name"].asStringOrNull() ?: bucket.name
                                    }
                                }
                                "message_delta" -> {
                                    root["delta"].asObjectOrNull()?.get("stop_reason").asStringOrNull()?.let {
                                        finishReason = it
                                    }
                                    root["usage"].asObjectOrNull()?.let { usage = parseAnthropicUsage(it, usage) }
                                }
                                "message_start" -> {
                                    root["message"].asObjectOrNull()?.get("model").asStringOrNull()?.let {
                                        servedModelId = it
                                    }
                                    root["message"].asObjectOrNull()?.get("usage").asObjectOrNull()?.let {
                                        usage = parseAnthropicUsage(it, usage)
                                    }
                                }
                            }
                        }
                        CloudCatalog.WireProtocol.GEMINI -> {
                            val chunk = parseGeminiChunk(data)
                            servedModelId = chunk.modelVersion ?: servedModelId
                            failure = chunk.failure ?: failure
                            usage = chunk.usage ?: usage
                            finishReason = chunk.finishReason ?: finishReason
                            chunk.reasoningDeltas.forEach { delta ->
                                reasoning.append(delta)
                                trySend(AgentModelStreamEvent.ReasoningDelta(delta))
                            }
                            chunk.textDeltas.forEach { delta ->
                                text.append(delta)
                                trySend(AgentModelStreamEvent.TextDelta(delta))
                            }
                            chunk.toolCalls.forEach { call ->
                                val index = toolCalls.size
                                toolCalls[index] = MutableToolCall(
                                    id = "${call.name}-${index + 1}",
                                    name = call.name,
                                    arguments = call.arguments,
                                    metadata = call.metadata,
                                )
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
                val completedCalls = toolCalls.entries.sortedBy { it.key }.mapNotNull { (_, call) ->
                    if (call.name.isBlank()) return@mapNotNull null
                    val invocation = CapabilityInvocation(
                        toolCallId = call.id.ifBlank { UUID.randomUUID().toString() },
                        name = call.name,
                        input = call.arguments.ifBlank { "{}" },
                    )
                    invocation to call.metadata
                }
                val pending = completedCalls.map { it.first }
                val parts = mutableListOf<AgentTranscript.Part>()
                if (reasoning.isNotEmpty()) {
                    parts += AgentTranscript.Part.Reasoning(reasoning.toString())
                }
                if (text.isNotEmpty()) {
                    parts += AgentTranscript.Part.Text(text.toString())
                }
                parts += completedCalls.map { (invocation, metadata) ->
                    AgentTranscript.Part.ToolCallPart(
                        AgentTranscript.ToolCall(
                            toolCallId = invocation.toolCallId,
                            toolName = invocation.name,
                            input = invocation.input,
                            metadata = metadata,
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
    }.buffer(Channel.UNLIMITED)

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
            put(
                "stream_options",
                buildJsonObject { put("include_usage", true) },
            )
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

    internal fun geminiBody(request: AgentModelRequest): String {
        val system = request.prompt.messages
            .filter { it.role == AgentTranscript.Role.SYSTEM }
            .map { it.text }
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
        val contents = buildJsonArray {
            request.prompt.messages
                .filter { it.role != AgentTranscript.Role.SYSTEM }
                .forEach { message ->
                    val parts = buildJsonArray {
                        message.parts.forEach { part ->
                            when (part) {
                                is AgentTranscript.Part.Text -> {
                                    if (part.text.isNotEmpty()) {
                                        add(buildJsonObject { put("text", part.text) })
                                    }
                                }
                                is AgentTranscript.Part.Reasoning -> add(
                                    buildJsonObject {
                                        put("text", part.text)
                                        put("thought", true)
                                        part.metadata["google"]?.get("thoughtSignature")?.let { signature ->
                                            put(
                                                "thoughtSignature",
                                                json.parseToJsonElement(signature.encodedString()),
                                            )
                                        }
                                    },
                                )
                                is AgentTranscript.Part.ToolCallPart -> add(
                                    buildJsonObject {
                                        put(
                                            "functionCall",
                                            buildJsonObject {
                                                put("name", part.toolCall.toolName)
                                                val args = runCatching {
                                                    json.parseToJsonElement(
                                                        part.toolCall.input.ifBlank { "{}" },
                                                    )
                                                }.getOrElse { buildJsonObject {} }
                                                put("args", args)
                                            },
                                        )
                                        part.toolCall.metadata["google"]?.get("thoughtSignature")?.let { signature ->
                                            put(
                                                "thoughtSignature",
                                                json.parseToJsonElement(signature.encodedString()),
                                            )
                                        }
                                    },
                                )
                                is AgentTranscript.Part.ToolResultPart -> {
                                    val result = part.toolResult.result
                                    val response = if (result.objectValue != null) {
                                        json.parseToJsonElement(result.encodedString())
                                    } else {
                                        buildJsonObject {
                                            put("result", json.parseToJsonElement(result.encodedString()))
                                        }
                                    }
                                    add(
                                        buildJsonObject {
                                            put(
                                                "functionResponse",
                                                buildJsonObject {
                                                    put("name", part.toolResult.toolName)
                                                    put("response", response)
                                                },
                                            )
                                        },
                                    )
                                }
                                is AgentTranscript.Part.File -> {
                                    when (val payload = part.file.data) {
                                        is AgentTranscript.FilePart.Payload.Base64 -> add(
                                            buildJsonObject {
                                                put(
                                                    "inlineData",
                                                    buildJsonObject {
                                                        put("mimeType", part.file.mediaType)
                                                        put("data", payload.value)
                                                    },
                                                )
                                            },
                                        )
                                        is AgentTranscript.FilePart.Payload.Url -> add(
                                            buildJsonObject {
                                                put(
                                                    "fileData",
                                                    buildJsonObject {
                                                        put("mimeType", part.file.mediaType)
                                                        put("fileUri", payload.value)
                                                    },
                                                )
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (parts.isNotEmpty()) {
                        add(
                            buildJsonObject {
                                put(
                                    "role",
                                    if (message.role == AgentTranscript.Role.ASSISTANT) "model" else "user",
                                )
                                put("parts", parts)
                            },
                        )
                    }
                }
        }
        return buildJsonObject {
            if (system.isNotEmpty()) {
                put(
                    "systemInstruction",
                    buildJsonObject {
                        put(
                            "parts",
                            buildJsonArray {
                                add(buildJsonObject { put("text", system) })
                            },
                        )
                    },
                )
            }
            put("contents", contents)
            if (request.capabilities.isNotEmpty()) {
                put(
                    "tools",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("functionDeclarations", toolsGemini(request.capabilities))
                            },
                        )
                    },
                )
            }
            put(
                "generationConfig",
                buildJsonObject {
                    request.profile.maxOutputTokens?.let { put("maxOutputTokens", it) }
                    if (supportsReasoning) {
                        put(
                            "thinkingConfig",
                            buildJsonObject {
                                if (request.profile.modelId.startsWith("gemini-3")) {
                                    put("thinkingLevel", if (thinkingEnabled) "high" else "minimal")
                                } else {
                                    put("thinkingBudget", if (thinkingEnabled) -1 else 0)
                                }
                            },
                        )
                    }
                },
            )
        }.toString()
    }

    internal fun parseGeminiChunk(data: String): GeminiChunk {
        val root = json.parseToJsonElement(data).asObjectOrNull()
            ?: return GeminiChunk(failure = "Gemini returned a non-object event")
        val text = mutableListOf<String>()
        val reasoning = mutableListOf<String>()
        val calls = mutableListOf<GeminiToolCall>()
        var finishReason: String? = null

        root["candidates"].asArrayOrNull()?.forEach { candidateElement ->
            val candidate = candidateElement.asObjectOrNull() ?: return@forEach
            finishReason = candidate["finishReason"].asStringOrNull() ?: finishReason
            candidate["content"].asObjectOrNull()
                ?.get("parts").asArrayOrNull()
                ?.forEach { partElement ->
                    val part = partElement.asObjectOrNull() ?: return@forEach
                    part["text"].asStringOrNull()?.takeIf { it.isNotEmpty() }?.let { delta ->
                        if ((part["thought"] as? JsonPrimitive)?.booleanOrNull == true) {
                            reasoning += delta
                        } else {
                            text += delta
                        }
                    }
                    part["functionCall"].asObjectOrNull()?.let { call ->
                        val name = call["name"].asStringOrNull() ?: return@let
                        val metadata = part["thoughtSignature"]?.let { signature ->
                            mapOf(
                                "google" to mapOf(
                                    "thoughtSignature" to RuntimeJSONValue.fromJsonElement(signature),
                                ),
                            )
                        }.orEmpty()
                        calls += GeminiToolCall(
                            name = name,
                            arguments = call["args"]?.toString() ?: "{}",
                            metadata = metadata,
                        )
                    }
                }
        }

        return GeminiChunk(
            textDeltas = text,
            reasoningDeltas = reasoning,
            toolCalls = calls,
            usage = root["usageMetadata"].asObjectOrNull()?.let(::parseGeminiUsage),
            finishReason = finishReason,
            modelVersion = root["modelVersion"].asStringOrNull(),
            failure = root["error"].asObjectOrNull()?.get("message").asStringOrNull(),
        )
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
                            val parameters = runCatching {
                                json.parseToJsonElement(capability.inputSchema.encodedString())
                            }.getOrNull().asObjectOrNull() ?: buildJsonObject {
                                put("type", "object")
                                put("properties", buildJsonObject {})
                            }
                            put("parameters", parameters)
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
                    val schema = runCatching {
                        json.parseToJsonElement(capability.inputSchema.encodedString())
                    }.getOrNull().asObjectOrNull() ?: buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {})
                    }
                    put("input_schema", schema)
                },
            )
        }
    }

    private fun toolsGemini(capabilities: List<CapabilityDefinition>): JsonArray = buildJsonArray {
        capabilities.forEach { capability ->
            add(
                buildJsonObject {
                    put("name", capability.name)
                    capability.description?.let { put("description", it) }
                    val parameters = runCatching {
                        json.parseToJsonElement(capability.inputSchema.encodedString())
                    }.getOrNull().asObjectOrNull() ?: buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {})
                    }
                    put("parametersJsonSchema", parameters)
                },
            )
        }
    }

    private fun parseUsage(obj: JsonObject): AgentUsage = AgentUsage(
        inputTokens = AgentUsage.Input(total = obj["prompt_tokens"].asIntOrNull()),
        outputTokens = AgentUsage.Output(total = obj["completion_tokens"].asIntOrNull()),
    )

    private fun parseAnthropicUsage(obj: JsonObject, previous: AgentUsage?): AgentUsage = AgentUsage(
        inputTokens = AgentUsage.Input(
            total = obj["input_tokens"].asIntOrNull() ?: previous?.inputTokens?.total,
        ),
        outputTokens = AgentUsage.Output(
            total = obj["output_tokens"].asIntOrNull() ?: previous?.outputTokens?.total,
        ),
    )

    private fun parseGeminiUsage(obj: JsonObject): AgentUsage {
        val candidates = obj["candidatesTokenCount"].asIntOrNull() ?: 0
        val thoughts = obj["thoughtsTokenCount"].asIntOrNull() ?: 0
        return AgentUsage(
            inputTokens = AgentUsage.Input(total = obj["promptTokenCount"].asIntOrNull()),
            outputTokens = AgentUsage.Output(total = candidates + thoughts),
        )
    }

    private fun mapFinishReason(raw: String?, hasTools: Boolean): AgentFinishReason? {
        if (raw == null && !hasTools) return null
        val unified = when (raw) {
            "stop", "end_turn", "STOP" -> AgentFinishReason.Unified.STOP
            "length", "max_tokens", "MAX_TOKENS" -> AgentFinishReason.Unified.LENGTH
            "tool_calls", "tool_use" -> AgentFinishReason.Unified.TOOL_CALLS
            "content_filter", "SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT",
            "SPII", "IMAGE_SAFETY",
            -> AgentFinishReason.Unified.CONTENT_FILTER
            null -> if (hasTools) AgentFinishReason.Unified.TOOL_CALLS else AgentFinishReason.Unified.STOP
            else -> AgentFinishReason.Unified.OTHER
        }
        return AgentFinishReason(unified = unified, raw = raw)
    }

    private fun extractErrorMessage(body: String): String {
        return runCatching {
            val root = json.parseToJsonElement(body).asObjectOrNull() ?: return@runCatching body.take(300)
            root["error"].asObjectOrNull()?.get("message").asStringOrNull()
                ?: root["error"].asStringOrNull()
                ?: root["message"].asStringOrNull()
                ?: body.take(300)
        }.getOrDefault(body.take(300))
    }

    private data class MutableToolCall(
        var id: String = "",
        var name: String = "",
        var arguments: String = "",
        var metadata: Map<String, Map<String, RuntimeJSONValue>> = emptyMap(),
    )

    internal data class GeminiToolCall(
        val name: String,
        val arguments: String,
        val metadata: Map<String, Map<String, RuntimeJSONValue>> = emptyMap(),
    )

    internal data class GeminiChunk(
        val textDeltas: List<String> = emptyList(),
        val reasoningDeltas: List<String> = emptyList(),
        val toolCalls: List<GeminiToolCall> = emptyList(),
        val usage: AgentUsage? = null,
        val finishReason: String? = null,
        val modelVersion: String? = null,
        val failure: String? = null,
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

        internal fun geminiEndpoint(base: String, model: String): String {
            val trimmed = base.trimEnd('/').removeSuffix("/v1beta")
            return "$trimmed/v1beta/models/$model:streamGenerateContent?alt=sse"
        }
    }
}
