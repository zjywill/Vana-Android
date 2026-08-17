package com.pinapia.vana.agentruntime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * 整条 loop 的集成测试:假模型 + 假能力,但 planner、compactor、calibration、事件归约
 * 全是真的。这三个场景是 agent 循环最容易悄悄坏掉的地方。
 */
class AgentLoopTests {
    private val profile = AgentModelProfile(
        providerId = "anthropic",
        modelId = "claude-sonnet-5",
        contextWindow = 20_000,
        maxOutputTokens = 8_000,
    )

    @Test
    fun stopAfterToolRun_keepsResultAndReplaysInsteadOfReQuerying() = runBlocking {
        val toolOutput = "最近 7 天睡眠\n08-01 | 7 小时 12 分\n08-02 | 6 小时 48 分"
        val counter = Counter()

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
                // 第二轮永远走不完——用户在工具结果回来、模型还没开口时按了停止。
                ScriptedModelClient.Turn(
                    textDeltas = listOf("这几晚"),
                    finishReason = AgentFinishReason(unified = AgentFinishReason.Unified.STOP),
                    beforeResponding = { delay(30_000) },
                ),
            ),
        )
        val loop = AgentLoop(
            client = client,
            capabilities = stubRegistry(mapOf("sleep_summary" to toolOutput)) {
                counter.increment()
            },
            systemInstruction = "system",
        )

        val base = listOf(AgentChatMessageDTO(role = AgentChatMessageDTO.Role.USER, text = "看看最近睡眠"))
        val history = base.toMutableList()
        AgentTurnReducer.startReply(history)

        // 照抄 app 的消费方式:取消的是消费者这个 Job。
        val collectJob = launch {
            loop.run(base).collect { event ->
                AgentTurnReducer.apply(event, history)
                // 工具刚跑完的那一刻按停止。
                if (event is AgentTurnEvent.ToolCallFinished) {
                    coroutineContext[Job]!!.cancel()
                }
            }
        }
        collectJob.join()

        AgentTurnReducer.markStopped(history)

        val assistant = history.last()
        assertEquals(StoredAgentTurn.State.STOPPED, assistant.storedTurn.state)
        assertTrue(assistant.textIsPlaceholder)
        // 已经花掉的那次查询要留着,界面上也还看得到结果。
        assertEquals(1, assistant.toolCalls.size)
        assertEquals(toolOutput, assistant.toolCalls.first().output?.text)
        assertEquals(1, counter.value)

        // 停止之后接着问:上一轮的工具结果照样回放,不该再查一次。
        val planner = ConversationHistoryPlanner(
            systemInstruction = "system",
            profile = profile,
            estimateTokens = { transcript ->
                transcript.messages.sumOf { it.text.length }
            },
        )
        val prepared = planner.prepare(
            history = history + AgentChatMessageDTO(
                role = AgentChatMessageDTO.Role.USER,
                text = "那深睡呢",
            ),
        )

        assertTrue(prepared.prompt.contains(toolOutput))
        // app 写给用户的占位不是模型说过的话,不能混进上下文。
        assertFalse(prepared.prompt.allText.contains("已停止回复"))
    }

    @Test
    fun longConversationCompactsOldTurnsIntoDigestsBeforeSending() = runBlocking {
        val oldOutput = "步数数据很长。".repeat(1_200)
        val client = ScriptedModelClient(
            profile = profile,
            turns = listOf(
                ScriptedModelClient.Turn(
                    textDeltas = listOf("好的"),
                    finishReason = AgentFinishReason(unified = AgentFinishReason.Unified.STOP),
                ),
            ),
            tokensPerCharacter = 4.0,
        )
        val loop = AgentLoop(
            client = client,
            capabilities = stubRegistry(mapOf("daily_steps" to "新的一次查询")),
            systemInstruction = "system",
        )

        val history = listOf(
            AgentChatMessageDTO(role = AgentChatMessageDTO.Role.USER, text = "上个月走了多少"),
            completedAssistant(
                text = "上个月平均一天 9,100 步。",
                toolName = "daily_steps",
                toolOutput = oldOutput,
                context = null,
            ),
            AgentChatMessageDTO(role = AgentChatMessageDTO.Role.USER, text = "那这周呢"),
        )

        val (messages, error) = collect(loop, history)
        assertNull(error)

        val prompt = requireNotNull(client.lastPrompt)
        // 原始工具输出被折叠掉了,但结论和折叠提示都还在——模型知道自己上次查过什么。
        assertFalse(prompt.contains(oldOutput))
        assertTrue(prompt.allText.contains("上个月平均一天 9,100 步。"))
        assertTrue(prompt.allText.contains("daily_steps"))
        assertTrue(prompt.allText.contains("folded 1 tool call"))
        // 最后那句用户问题不能被压没了。
        assertTrue(prompt.allText.contains("那这周呢"))

        val snapshot = requireNotNull(messages.last().storedTurn.context)
        assertTrue(snapshot.compactedAssistantMessages >= 1)
        assertEquals(0, snapshot.droppedConversationTurns)
        assertTrue((snapshot.estimatedPromptTokens ?: 0) <= 20_000 - 2_000)
    }

    @Test
    fun switchingToSmallerModelMigratesHistoryAndDropsOldCalibration() = runBlocking {
        val bigWindowContext = TurnContextSnapshotDTO(
            providerId = "anthropic",
            requestedModelId = "claude-opus-4-8",
            contextWindow = 200_000,
            estimatedPromptTokens = 100,
            // 旧模型上本地估算差了一倍。这把尺子不能拿到新模型上继续用。
            actualPromptTokens = 200,
        )
        // 大到新模型的窗口里放不下:换模型这件事本身不该动历史,放不下才该动。
        val detailedOutput = "详细的工具轨迹。".repeat(2_500)
        val history = listOf(
            AgentChatMessageDTO(role = AgentChatMessageDTO.Role.USER, text = "先聊聊上周"),
            completedAssistant(
                text = "上周整体还行。",
                toolName = "daily_steps",
                toolOutput = detailedOutput,
                context = bigWindowContext,
            ),
            AgentChatMessageDTO(role = AgentChatMessageDTO.Role.USER, text = "换个模型继续说"),
        )

        val client = ScriptedModelClient(
            profile = profile,
            turns = listOf(
                ScriptedModelClient.Turn(
                    textDeltas = listOf("继续"),
                    finishReason = AgentFinishReason(unified = AgentFinishReason.Unified.STOP),
                ),
            ),
        )
        val loop = AgentLoop(
            client = client,
            capabilities = stubRegistry(mapOf("daily_steps" to "新查询")),
            systemInstruction = "system",
        )

        val (messages, error) = collect(loop, history)
        assertNull(error)

        val prompt = requireNotNull(client.lastPrompt)
        val snapshot = requireNotNull(messages.last().storedTurn.context)

        // 窗口变小了,老的那轮主动换成压缩形态,而不是等 provider 报 400。
        assertTrue(snapshot.migrationNotes.contains(ConversationHistoryPlanner.MODEL_SWITCH_NOTE))
        assertEquals(1, snapshot.compactedAssistantMessages)
        assertFalse(prompt.contains(detailedOutput))
        assertTrue(prompt.allText.contains("上周整体还行。"))
        assertTrue(prompt.messages.any { it.compactionSourceIDs.isNotEmpty() })

        // 校准跟着模型走:换了模型就回到裸估算,不拿旧模型 2 倍的比例去套。
        val rawEstimate = client.estimateTokens(
            AgentModelRequest(
                profile = profile,
                prompt = prompt,
                capabilities = loop.capabilities.definitions,
            ),
        )
        assertEquals(rawEstimate, snapshot.estimatedPromptTokens)
        assertNull(ContextCalibration(history = history, profile = profile).scale)

        // 同一个模型的历史才认:换回去就该继续用那把尺子。
        val sameModel = AgentModelProfile(
            providerId = "anthropic",
            modelId = "claude-opus-4-8",
            contextWindow = 200_000,
        )
        assertEquals(2.0, ContextCalibration(history = history, profile = sameModel).scale)
    }

    private fun completedAssistant(
        text: String,
        toolName: String,
        toolOutput: String,
        context: TurnContextSnapshotDTO?,
    ): AgentChatMessageDTO {
        val callId = "call_$toolName"
        val input = """{"days":30}"""
        return AgentChatMessageDTO(
            role = AgentChatMessageDTO.Role.ASSISTANT,
            text = text,
            toolCalls = listOf(
                ToolCallRecordDTO(
                    id = callId,
                    name = toolName,
                    input = input,
                    output = AgentToolOutput(kind = AgentToolOutput.Kind.TABLE, text = toolOutput),
                ),
            ),
            storedTurn = StoredAgentTurn(
                exactTranscript = AgentTranscript(
                    messages = listOf(
                        AgentTranscript.Message(
                            role = AgentTranscript.Role.ASSISTANT,
                            parts = listOf(
                                AgentTranscript.Part.Text(text),
                                AgentTranscript.Part.ToolCallPart(
                                    AgentTranscript.ToolCall(
                                        toolCallId = callId,
                                        toolName = toolName,
                                        input = input,
                                    ),
                                ),
                            ),
                        ),
                        AgentTranscript.Message.toolResult(
                            toolCallId = callId,
                            toolName = toolName,
                            result = RuntimeJSONValue.string(toolOutput),
                        ),
                    ),
                ),
                state = StoredAgentTurn.State.COMPLETED,
                context = context,
            ),
        )
    }
}
