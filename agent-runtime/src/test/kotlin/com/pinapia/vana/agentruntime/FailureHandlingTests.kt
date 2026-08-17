package com.pinapia.vana.agentruntime

import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlin.time.Duration.Companion.ZERO
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * 失败路径的集成测试。
 *
 * 这里每一条都是「本来会让用户白等一场」的场景:基站切换、provider 拥塞、输出被截断、
 * 查询次数用光。agent 循环真正的质量差别在这些地方,不在顺利那条路上。
 */
class FailureHandlingTests {
    private val profile = AgentModelProfile(
        providerId = "anthropic",
        modelId = "claude-sonnet-5",
        contextWindow = 20_000,
        maxOutputTokens = 8_000,
    )

    /** 退避时间设成 0:测的是「重试了没有」,不是「等够了没有」。 */
    private val fastRetry = RetryPolicy(maxRetries = 2, baseDelay = ZERO, maxDelay = ZERO)

    private val question = listOf(
        AgentChatMessageDTO(role = AgentChatMessageDTO.Role.USER, text = "上周走了多少"),
    )

    private fun loop(
        client: ScriptedModelClient,
        capabilities: CapabilityRegistry = stubRegistry(mapOf("daily_steps" to "9,100 步")),
        retryPolicy: RetryPolicy = fastRetry,
        policy: ContextPolicy = ContextPolicy.default,
        summarizer: AgentSummarizer? = null,
        maxToolRounds: Int = 6,
    ): AgentLoop = AgentLoop(
        client = client,
        capabilities = capabilities,
        systemInstruction = "system",
        summarizer = summarizer,
        policy = policy,
        retryPolicy = retryPolicy,
        maxToolRounds = maxToolRounds,
    )

    @Test
    fun transientProviderErrorIsRetriedAndHalfFinishedSentenceIsRolledBack() = runBlocking {
        val client = ScriptedModelClient(
            profile = profile,
            turns = listOf(
                // 说了半句,然后 provider 报拥塞。
                ScriptedModelClient.Turn(
                    textDeltas = listOf("上周你", "走了"),
                    finishReason = AgentFinishReason(unified = AgentFinishReason.Unified.ERROR),
                    failureMessage = "Error 529: Overloaded, please try again",
                ),
                ScriptedModelClient.Turn(
                    textDeltas = listOf("上周日均 9,100 步。"),
                    finishReason = AgentFinishReason(unified = AgentFinishReason.Unified.STOP),
                ),
            ),
        )

        val outcome = record(loop(client), question)

        assertNull(outcome.error)
        assertEquals(2, client.requests.size)
        // 半句话必须撤掉,否则用户看到的是"上周你走了上周日均 9,100 步。"
        assertEquals("上周日均 9,100 步。", outcome.messages.last().text)

        assertEquals(listOf("上周你走了".length), outcome.events.rolledBackCharacters)
        val retry = requireNotNull(outcome.events.retries.firstOrNull())
        assertEquals(1, retry.attempt)
        assertTrue(retry.reason.contains("Overloaded"))
    }

    @Test
    fun reasoningIsStreamedAndSurvivesToolRound() = runBlocking {
        val client = ScriptedModelClient(
            profile = profile,
            turns = listOf(
                ScriptedModelClient.Turn(
                    reasoningDeltas = listOf("先查步数"),
                    toolCalls = listOf(
                        CapabilityInvocation(
                            toolCallId = "call_1",
                            name = "daily_steps",
                            input = "{}",
                        ),
                    ),
                    finishReason = AgentFinishReason(unified = AgentFinishReason.Unified.TOOL_CALLS),
                ),
                ScriptedModelClient.Turn(
                    reasoningDeltas = listOf("数据够了"),
                    textDeltas = listOf("上周日均 9,100 步。"),
                    finishReason = AgentFinishReason(unified = AgentFinishReason.Unified.STOP),
                ),
            ),
        )

        val outcome = record(loop(client), question)
        assertNull(outcome.error)
        assertEquals("先查步数数据够了", outcome.messages.last().reasoning)
        assertEquals("上周日均 9,100 步。", outcome.messages.last().text)
    }

    @Test
    fun truncatedToolCallIsNotExecuted() = runBlocking {
        val counter = Counter()
        val client = ScriptedModelClient(
            profile = profile,
            turns = listOf(
                ScriptedModelClient.Turn(
                    toolCalls = listOf(
                        CapabilityInvocation(
                            toolCallId = "call_1",
                            name = "daily_steps",
                            input = """{"days":""",
                        ),
                    ),
                    finishReason = AgentFinishReason(unified = AgentFinishReason.Unified.LENGTH),
                ),
                ScriptedModelClient.Turn(
                    textDeltas = listOf("好的,我重新查。"),
                    finishReason = AgentFinishReason(unified = AgentFinishReason.Unified.STOP),
                ),
            ),
        )

        val outcome = record(
            loop(
                client,
                capabilities = stubRegistry(mapOf("daily_steps" to "9,100 步")) {
                    counter.increment()
                },
            ),
            question,
        )

        assertNull(outcome.error)
        assertEquals(0, counter.value)
        val finished = outcome.events.filterIsInstance<AgentTurnEvent.ToolCallFinished>()
        assertEquals(1, finished.size)
        assertTrue(finished.first().isError)
        assertTrue(finished.first().output.text.contains("not executed"))
    }

    @Test
    fun toolRoundLimitCompletesWithMarkerInsteadOfThrowing() = runBlocking {
        val client = ScriptedModelClient(
            profile = profile,
            turns = List(4) {
                ScriptedModelClient.Turn(
                    toolCalls = listOf(
                        CapabilityInvocation(
                            toolCallId = "call_$it",
                            name = "daily_steps",
                            input = "{}",
                        ),
                    ),
                    finishReason = AgentFinishReason(unified = AgentFinishReason.Unified.TOOL_CALLS),
                )
            },
        )

        val outcome = record(loop(client, maxToolRounds = 2), question)
        assertNull(outcome.error)
        assertEquals(AgentLoop.TOOL_ROUND_LIMIT_REASON, outcome.events.finishReason?.raw)
        assertEquals(AgentFinishReason.Unified.OTHER, outcome.events.finishReason?.unified)
        assertEquals(StoredAgentTurn.State.COMPLETED, outcome.messages.last().storedTurn.state)
    }

    @Test
    fun transportTimeoutIsRetryable() = runBlocking {
        val client = ScriptedModelClient(
            profile = profile,
            turns = listOf(
                ScriptedModelClient.Turn(
                    textDeltas = listOf("半句"),
                    throwsAfterText = SocketTimeoutException("timed out"),
                ),
                ScriptedModelClient.Turn(
                    textDeltas = listOf("完整回答"),
                    finishReason = AgentFinishReason(unified = AgentFinishReason.Unified.STOP),
                ),
            ),
        )

        val outcome = record(loop(client), question)
        assertNull(outcome.error)
        assertEquals(2, client.requests.size)
        assertEquals("完整回答", outcome.messages.last().text)
        assertEquals(1, outcome.events.retries.size)
    }

    @Test
    fun permanentAuthErrorIsNotRetried() = runBlocking {
        val client = ScriptedModelClient(
            profile = profile,
            turns = listOf(
                ScriptedModelClient.Turn(
                    finishReason = AgentFinishReason(unified = AgentFinishReason.Unified.ERROR),
                    failureMessage = "invalid api key",
                ),
            ),
        )

        val outcome = record(loop(client), question)
        assertNotNull(outcome.error)
        assertEquals(1, client.requests.size)
        assertTrue(outcome.events.retries.isEmpty())
    }

    @Test
    fun failureKindsSeparateUserActions() {
        assertEquals(ModelFailure.Kind.AUTHENTICATION, ModelFailure.kind("Error code: 401"))
        assertEquals(ModelFailure.Kind.QUOTA, ModelFailure.kind("insufficient quota"))
        assertEquals(ModelFailure.Kind.CONTEXT_OVERFLOW, ModelFailure.kind("prompt is too long"))
        assertEquals(ModelFailure.Kind.TRANSIENT, ModelFailure.kind("server overloaded"))
        assertEquals(ModelFailure.Kind.OTHER, ModelFailure.kind("unknown provider response"))
    }

    @Test
    fun pendingInputLandsAfterToolResult() = runBlocking {
        val toolOutput = "最近 7 天睡眠\n08-01 | 7 小时 12 分"
        val interjection = AgentPendingInput(text = "顺便也看看心率")
        // 第一个边界(第一次请求之前)空着,第二个边界才有——这正是「他在模型查数据的时候
        // 补了一句」的样子。
        val batches = mutableListOf(
            emptyList<AgentPendingInput>(),
            listOf(interjection),
        )

        val client = ScriptedModelClient(
            profile = profile,
            turns = listOf(
                ScriptedModelClient.Turn(
                    toolCalls = listOf(
                        CapabilityInvocation(
                            toolCallId = "call_1",
                            name = "sleep_summary",
                            input = """{"days":7}""",
                        ),
                    ),
                    finishReason = AgentFinishReason(unified = AgentFinishReason.Unified.TOOL_CALLS),
                ),
                ScriptedModelClient.Turn(
                    textDeltas = listOf("睡眠和心率都看了"),
                    finishReason = AgentFinishReason(unified = AgentFinishReason.Unified.STOP),
                ),
            ),
        )
        val agentLoop = AgentLoop(
            client = client,
            capabilities = stubRegistry(mapOf("sleep_summary" to toolOutput)),
            systemInstruction = "system",
            pendingInput = {
                if (batches.isEmpty()) emptyList() else batches.removeAt(0)
            },
        )

        val outcome = record(
            agentLoop,
            listOf(AgentChatMessageDTO(role = AgentChatMessageDTO.Role.USER, text = "看看最近睡眠")),
        )
        assertNull(outcome.error)

        val second = requireNotNull(client.requests.lastOrNull()?.prompt)
        val roles = second.messages.map { it.role }
        val toolIndex = roles.indexOfLast { it == AgentTranscript.Role.TOOL }
        val interjected = second.messages.indexOfLast {
            it.role == AgentTranscript.Role.USER && it.text == interjection.text
        }
        // 位置就是全部:排在工具结果前面的话,模型读到的是「他先追问、我才去查」,
        // 而实际顺序正相反。
        assertTrue(interjected > toolIndex)
    }
}
