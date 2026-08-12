package com.pinapia.vana.agentruntime

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * 脚本化的假模型:每一轮该回什么写死在数组里,不发网络请求。
 *
 * 它同时记下每一轮实际收到的 prompt——loop 集成测试真正要验的就是「这一轮到底发了什么
 * 出去」,而不只是「最后 UI 上留下了什么」。
 */
class ScriptedModelClient(
    override val profile: AgentModelProfile,
    turns: List<Turn>,
    private val tokensPerCharacter: Double = 1.0,
) : AgentModelClient {
    data class Turn(
        val textDeltas: List<String> = emptyList(),
        /** 思考。真模型先思考再说话,脚本也按这个顺序发。 */
        val reasoningDeltas: List<String> = emptyList(),
        val toolCalls: List<CapabilityInvocation> = emptyList(),
        val finishReason: AgentFinishReason = AgentFinishReason(unified = AgentFinishReason.Unified.STOP),
        val usage: AgentUsage? = null,
        val servedModelId: String? = null,
        val failureMessage: String? = null,
        /**
         * 这一轮开口之前先跑一下。挂住某一轮,好在模型还没说话时制造取消。
         *
         * 必须挂在开口**之前**:挂在之后的话,delta 已经进了 stream 的缓冲区,消费者会先把
         * 缓冲区抽干再发现自己被取消了——测试就变成看谁跑得快。
         */
        val beforeResponding: (suspend () -> Unit)? = null,
        /** 说了半句之后流断掉。传输层的失败长这样:没有 finish reason,只有一个抛出来的错。 */
        val throwsAfterText: Throwable? = null,
    )

    private val lock = Any()
    private val remainingTurns = turns.toMutableList()
    private val _requests = mutableListOf<AgentModelRequest>()

    val requests: List<AgentModelRequest>
        get() = synchronized(lock) { _requests.toList() }

    val lastPrompt: AgentTranscript?
        get() = requests.lastOrNull()?.prompt

    override fun estimateTokens(request: AgentModelRequest): Int {
        val characters = request.prompt.messages.sumOf { message ->
            message.parts.sumOf { it.approximateCharacterCount }
        }
        return kotlin.math.round(characters * tokensPerCharacter).toInt()
    }

    override fun stream(request: AgentModelRequest): Flow<AgentModelStreamEvent> {
        val turn = synchronized(lock) {
            _requests += request
            if (remainingTurns.isEmpty()) Turn() else remainingTurns.removeAt(0)
        }

        return flow {
            turn.beforeResponding?.invoke()
            currentCoroutineContext().ensureActive()

            val parts = mutableListOf<AgentTranscript.Part>()
            for (delta in turn.reasoningDeltas) {
                currentCoroutineContext().ensureActive()
                emit(AgentModelStreamEvent.ReasoningDelta(delta))
            }
            for (delta in turn.textDeltas) {
                currentCoroutineContext().ensureActive()
                emit(AgentModelStreamEvent.TextDelta(delta))
            }
            turn.throwsAfterText?.let { throw it }

            val reasoning = turn.reasoningDeltas.joinToString("")
            if (reasoning.isNotEmpty()) {
                parts += AgentTranscript.Part.Reasoning(reasoning)
            }
            val text = turn.textDeltas.joinToString("")
            if (text.isNotEmpty()) {
                parts += AgentTranscript.Part.Text(text)
            }
            parts += turn.toolCalls.map {
                AgentTranscript.Part.ToolCallPart(
                    AgentTranscript.ToolCall(
                        toolCallId = it.toolCallId,
                        toolName = it.name,
                        input = it.input,
                    ),
                )
            }

            emit(
                AgentModelStreamEvent.Completed(
                    AgentModelResponse(
                        assistantMessage = if (parts.isEmpty()) {
                            null
                        } else {
                            AgentTranscript.Message(
                                role = AgentTranscript.Role.ASSISTANT,
                                parts = parts,
                            )
                        },
                        pendingCalls = turn.toolCalls,
                        finishReason = turn.finishReason,
                        usage = turn.usage,
                        servedModelId = turn.servedModelId,
                        failureMessage = turn.failureMessage,
                    ),
                ),
            )
        }
    }
}

val AgentTranscript.Part.approximateCharacterCount: Int
    get() = when (this) {
        is AgentTranscript.Part.Text -> text.length
        is AgentTranscript.Part.Reasoning -> text.length
        is AgentTranscript.Part.ToolCallPart -> toolCall.toolName.length + toolCall.input.length
        is AgentTranscript.Part.ToolResultPart ->
            toolResult.toolName.length +
                (runCatching { toolResult.result.encodedString().length }.getOrDefault(0))
        is AgentTranscript.Part.File -> 0
    }

val AgentTranscript.allText: String
    get() = messages.joinToString("\n") { it.text }

fun AgentTranscript.contains(needle: String): Boolean =
    allText.contains(needle) || messages.any { message ->
        message.parts.any { part ->
            if (part is AgentTranscript.Part.ToolResultPart) {
                (part.toolResult.result.stringValue ?: "").contains(needle)
            } else {
                false
            }
        }
    }

/** 一组固定输出的能力,不碰任何系统框架。 */
fun stubRegistry(
    outputs: Map<String, String>,
    onExecute: ((CapabilityInvocation) -> Unit)? = null,
): CapabilityRegistry = CapabilityRegistry(
    definitions = outputs.keys.sorted().map { name ->
        CapabilityDefinition(
            name = name,
            description = "stub $name",
            inputSchema = RuntimeJSONValue.obj(mapOf("type" to RuntimeJSONValue.string("object"))),
        )
    },
) { invocation ->
    onExecute?.invoke(invocation)
    val text = outputs[invocation.name]
    if (text == null) {
        CapabilityExecutionResult(
            output = AgentToolOutput(kind = AgentToolOutput.Kind.TEXT, text = "unknown"),
            isError = true,
        )
    } else {
        CapabilityExecutionResult(
            output = AgentToolOutput(kind = AgentToolOutput.Kind.TABLE, text = text),
        )
    }
}

data class RecordedRun(
    val messages: List<AgentChatMessageDTO>,
    val events: List<AgentTurnEvent>,
    val error: Throwable?,
)

/**
 * 照 app 的方式消费一轮:事件按顺序归约进历史,同时原样留一份事件流。
 *
 * 事件流是要验的东西之一——重试、压缩这些都只在事件里看得见,归约完的消息上是看不出来的。
 */
suspend fun record(
    loop: AgentLoop,
    history: List<AgentChatMessageDTO>,
): RecordedRun {
    val result = history.toMutableList()
    val events = mutableListOf<AgentTurnEvent>()
    AgentTurnReducer.startReply(result)
    return try {
        loop.run(history).collect { event ->
            events += event
            AgentTurnReducer.apply(event, result)
        }
        RecordedRun(result, events, null)
    } catch (error: Throwable) {
        RecordedRun(result, events, error)
    }
}

suspend fun collect(
    loop: AgentLoop,
    history: List<AgentChatMessageDTO>,
): Pair<List<AgentChatMessageDTO>, Throwable?> {
    val outcome = record(loop, history)
    return outcome.messages to outcome.error
}

val List<AgentTurnEvent>.retries: List<AgentRetryNotice>
    get() = mapNotNull { (it as? AgentTurnEvent.RetryScheduled)?.notice }

val List<AgentTurnEvent>.rolledBackCharacters: List<Int>
    get() = mapNotNull { (it as? AgentTurnEvent.TextRolledBack)?.characterCount }

val List<AgentTurnEvent>.rolledBackReasoningCharacters: List<Int>
    get() = mapNotNull { (it as? AgentTurnEvent.ReasoningRolledBack)?.characterCount }

val List<AgentTurnEvent>.compactionReasons: List<AgentCompactionReason>
    get() = mapNotNull { (it as? AgentTurnEvent.CompactionStarted)?.reason }

val List<AgentTurnEvent>.compactionFailures: List<String>
    get() = mapNotNull { (it as? AgentTurnEvent.CompactionFailed)?.message }

val List<AgentTurnEvent>.finishReason: AgentFinishReason?
    get() {
        for (event in this) {
            if (event is AgentTurnEvent.TurnCompleted) return event.finishReason
        }
        return null
    }

/** 测试里数一下能力被执行了几次。 */
class Counter(initial: Int = 0) {
    private val stored = AtomicInteger(initial)
    val value: Int get() = stored.get()
    fun increment() {
        stored.incrementAndGet()
    }
}
