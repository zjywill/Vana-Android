package com.pinapia.vana.agentruntime

import java.util.UUID
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

sealed class AgentLoopError : Exception() {
    /** provider 侧的错误,原文透出。重试用完了才会走到这儿。 */
    data class Service(val detail: String) : AgentLoopError() {
        override val message: String get() = detail
    }

    data object ContentFilter : AgentLoopError() {
        private fun readResolve(): Any = ContentFilter
        override val message: String get() = "content filter"
    }

    /** 流结束了但没拿到 finish reason。 */
    data object IncompleteResponse : AgentLoopError() {
        private fun readResolve(): Any = IncompleteResponse
        override val message: String get() = "stream ended without a finish reason"
    }

    /** 压缩、丢弃、溢出恢复都做完了还是塞不进窗口。 */
    data object ContextWindowExceeded : AgentLoopError() {
        private fun readResolve(): Any = ContextWindowExceeded
        override val message: String get() = "context window exceeded"
    }
}

/**
 * 通用 agent loop。
 *
 * 它只认三样东西:一个能流式跑一轮的 [AgentModelClient]、一组 [CapabilityRegistry] 里的
 * 能力、一份 app 自己的会话历史。HealthKit、Calendar、Files 对它没有区别,AIKit 和别的
 * SDK 也没有区别。
 *
 * 失败处理分三层,越靠外越少见:
 * 1. 工具失败 → 变成一条 `isError` 的结果继续跑。模型自己会换个问法。
 * 2. 模型失败 → 按 [ModelFailure] 分类。拥塞和网络抖动退避重试;上下文超限压缩后重跑
 *    这一轮;鉴权、额度这些确定性的错误立刻上报,重试只是把同一句话说三遍。
 * 3. 都救不回来 → 抛出去。这时用户看到的是原因,不是一个转圈。
 */
class AgentLoop(
    var client: AgentModelClient,
    var capabilities: CapabilityRegistry,
    var systemInstruction: String,
    var compactor: TranscriptCompactor = TranscriptCompactor.default,
    /** 谁来写整段摘要。给 null 就退回纯机械压缩(还能跑,只是省不了那么多)。 */
    var summarizer: AgentSummarizer? = null,
    var migrationPolicy: HistoryMigrationPolicy = HistoryMigrationPolicy.WHEN_WINDOW_SHRINKS,
    var policy: ContextPolicy = ContextPolicy.default,
    var retryPolicy: RetryPolicy = RetryPolicy.default,
    var maxToolRounds: Int = 6,
    /**
     * 用户在这一轮跑的过程中补的话从哪儿来。给 null 就是老行为:一轮里只有开头那一句。
     *
     * 只在**工具轮边界**问,不在流中途问。中途打断要撤掉已经吐出去的半句(用户正读着的
     * 那段字会当场消失),还得把刚花掉的那次生成整个扔了;而边界处什么都不用撤,模型
     * 下一句就能改道。真要立刻停下,用户手上一直有停止按钮。
     */
    var pendingInput: AgentPendingInputProvider? = null,
    /**
     * 模型在吐 tool_use 的中途被输出上限截断时,顶替执行结果的那句话。
     *
     * 这句是给模型看的,所以要写清楚「没执行」和「该怎么办」——它读完得知道重发一次,
     * 而不是以为工具坏了。
     */
    var truncatedToolCallNotice: String = DEFAULT_TRUNCATED_TOOL_CALL_NOTICE,
    /**
     * 生命周期上的旁观者。给 null 就一次派发都不发生,和没有这套东西时一样贵。
     *
     * 宿主由 app 持有(见 [AgentHookDispatcher]):hook 跨轮有状态,而 loop 每次回复现造。
     */
    var hooks: AgentHookDispatcher? = null,
) {
    fun run(history: List<AgentChatMessageDTO>): Flow<AgentTurnEvent> = flow {
        execute(history) { emit(it) }
    }

    private suspend fun execute(
        history: List<AgentChatMessageDTO>,
        yield: suspend (AgentTurnEvent) -> Unit,
    ) {
        val hooks = this.hooks
        if (hooks == null) {
            val progress = TurnProgress()
            runRounds(history = history, progress = progress, yield = yield)
            return
        }

        val turnId = UUID.randomUUID()
        hooks.post(
            AgentHookNotice(
                turnId = turnId,
                kind = AgentHookNotice.Kind.TurnStarted(
                    AgentHookTurnStart(history = history, profile = client.profile),
                ),
            ),
        )

        val progress = TurnProgress()
        try {
            runRounds(
                history = history,
                progress = progress,
                turnId = turnId,
                yield = yield,
            )
        } catch (error: Throwable) {
            // 出口只有这一个:救不回来的错误和用户按停止都走这儿。少一条出口,hook 就得靠
            // 超时去猜自己在等的那一轮是不是已经不会来了。
            val state = if (error is CancellationException) {
                AgentHookTurnOutcome.State.Stopped
            } else {
                AgentHookTurnOutcome.State.Failed(describe(error))
            }
            hooks.post(
                AgentHookNotice(
                    turnId = turnId,
                    kind = AgentHookNotice.Kind.TurnFinished(
                        AgentHookTurnOutcome(
                            state = state,
                            transcript = progress.transcript,
                            usage = progress.usage,
                            context = progress.snapshot,
                        ),
                    ),
                ),
            )
            throw error
        }
    }

    private suspend fun runRounds(
        history: List<AgentChatMessageDTO>,
        progress: TurnProgress,
        turnId: UUID? = null,
        yield: suspend (AgentTurnEvent) -> Unit,
    ) {
        val profile = client.profile
        val definitions = capabilities.definitions
        val reserved = reservedOutputTokens(profile)

        var calibration = ContextCalibration(history = history, profile = profile)
        // 本轮用的历史。整段摘要生成后就地写进去,同时通过事件让 app 存下来。
        var workingHistory = history
        // 一轮里最多总结一次。工具轮之间再压也只能压历史,压不动这一轮刚拿到的大结果,
        // 白花调用。溢出恢复会把它放开一次——那时候是 provider 说了算,不是水位线。
        var didSummarize = false
        var overflowRecoveryUsed = false
        var isRecoveringFromOverflow = false
        var attempt = 0
        var round = 0

        while (round < maxToolRounds) {
            currentCoroutineContext().ensureActive()

            // 用户在上一次请求跑的时候补的话,在这儿接进来。
            //
            // 排在总结前面:这几句也要占预算,压缩得按包含它们的那份 prompt 来算。接在
            // `runtimeTranscript` 尾部而不是 `history` 里,因为它到得比这一轮已经发生的
            // 工具调用还晚——放进 history 会让模型看成「他先追问、我才去查」,而实际顺序
            // 正相反。
            val pendingProvider = pendingInput
            if (pendingProvider != null) {
                val pending = pendingProvider()
                if (pending.isNotEmpty()) {
                    progress.transcript = progress.transcript.copy(
                        messages = progress.transcript.messages +
                            pending.map { AgentTranscript.Message.user(it.text) },
                    )
                    yield(AgentTurnEvent.PendingInputAccepted(pending))
                }
            }

            // 过了水位线就叫模型把远处的对话总结掉,再去发这一轮。
            // 放在发请求之前,而不是撞墙之后——撞墙时已经没有从容处理的余地了。
            if (!didSummarize) {
                val compacted = summarize(
                    history = workingHistory,
                    runtimeTranscript = progress.transcript,
                    profile = profile,
                    definitions = definitions,
                    reserved = reserved,
                    calibrationScale = calibration.scale,
                    isRecoveringFromOverflow = isRecoveringFromOverflow,
                    yield = yield,
                )
                if (compacted != null) {
                    didSummarize = true
                    val index = workingHistory.indexOfFirst { it.id == compacted.messageID.toString() }
                    if (index >= 0) {
                        val msg = workingHistory[index]
                        workingHistory = workingHistory.toMutableList().also {
                            it[index] = msg.copy(
                                storedTurn = msg.storedTurn.copy(compaction = compacted.artifact),
                            )
                        }
                    }
                    yield(
                        AgentTurnEvent.HistoryCompacted(
                            messageID = compacted.messageID,
                            artifact = compacted.artifact,
                        ),
                    )
                }
            }

            val prepared = plan(
                history = workingHistory,
                runtimeTranscript = progress.transcript,
                profile = profile,
                definitions = definitions,
                reserved = reserved,
                calibrationScale = calibration.scale,
                isRecoveringFromOverflow = isRecoveringFromOverflow,
            )
            if (prepared.exceedsBudget) {
                throw AgentLoopError.ContextWindowExceeded
            }

            val request = AgentModelRequest(
                profile = profile,
                prompt = prepared.prompt,
                capabilities = definitions,
            )

            val response: AgentModelResponse
            when (val outcome = streamRound(request, yield)) {
                is RoundOutcome.Completed -> response = outcome.response
                is RoundOutcome.Failed -> {
                    if (outcome.error is CancellationException) throw outcome.error
                    currentCoroutineContext().ensureActive()
                    val description = describe(outcome.error)

                    // 上下文超限走压缩,不走重试:原样再发一次还是塞不下。
                    if (ModelFailure.isContextOverflow(description)) {
                        // 压过一次还是超,那就是真的放不下了。报一个用户能照着做的错(开新对话),
                        // 而不是把 provider 那句 "prompt is too long: 210000 tokens" 甩给他。
                        if (overflowRecoveryUsed) {
                            throw AgentLoopError.ContextWindowExceeded
                        }
                        overflowRecoveryUsed = true
                        isRecoveringFromOverflow = true
                        // provider 说装不下,那就是我们估小了。信 provider 的,把尺子放大,
                        // 让这一轮和后面几轮都压得更狠。
                        calibration.inflate(by = 1.25)
                        didSummarize = false
                        rollBack(outcome.emitted, yield)
                        continue
                    }

                    if (!retryPolicy.allowsRetry(attempt + 1) ||
                        !ModelFailure.isRetryable(outcome.error, description)
                    ) {
                        throw outcome.error
                    }
                    attempt += 1
                    val delayDuration = retryPolicy.delay(forAttempt = attempt)
                    rollBack(outcome.emitted, yield)
                    yield(
                        AgentTurnEvent.RetryScheduled(
                            AgentRetryNotice(
                                attempt = attempt,
                                maxAttempts = retryPolicy.maxRetries,
                                delay = delayDuration,
                                reason = description,
                            ),
                        ),
                    )
                    delay(delayDuration)
                    continue
                }
            }

            attempt = 0
            isRecoveringFromOverflow = false

            val assistant = response.assistantMessage
            if (assistant != null && assistant.parts.isNotEmpty()) {
                progress.transcript = progress.transcript.copy(
                    messages = progress.transcript.messages + assistant,
                )
            }

            calibration.note(
                actual = response.usage?.inputTokens?.total,
                estimated = prepared.estimatedPromptTokens,
            )
            val snapshot = prepared.snapshot(
                servedModelId = response.servedModelId,
                actualPromptTokens = response.usage?.inputTokens?.total,
            )
            progress.usage = response.usage
            progress.snapshot = snapshot

            if (response.pendingCalls.isEmpty()) {
                yield(
                    AgentTurnEvent.TurnCompleted(
                        transcript = progress.transcript,
                        finishReason = response.finishReason,
                        usage = response.usage,
                        context = snapshot,
                    ),
                )
                finish(
                    turnId = turnId,
                    state = AgentHookTurnOutcome.State.Completed,
                    progress = progress,
                    finishReason = response.finishReason,
                )
                return
            }

            // 被输出上限截断的那一轮,每个 tool_use 的参数都可能是半截的——JSON 抢救出来
            // 能解析不代表它完整。一个都不执行,原样报回去让模型重发,比执行一次错的查询
            // 再让用户看着不对劲的数字强。
            val wasTruncated = response.finishReason?.unified == AgentFinishReason.Unified.LENGTH

            for (call in response.pendingCalls) {
                currentCoroutineContext().ensureActive()
                yield(
                    AgentTurnEvent.ToolCallStarted(
                        ToolCallRecordDTO(
                            id = call.toolCallId,
                            name = call.name,
                            input = call.input,
                        ),
                    ),
                )

                val startedAt = TimeSource.Monotonic.markNow()
                val result = if (wasTruncated) {
                    CapabilityExecutionResult(
                        output = AgentToolOutput(
                            kind = AgentToolOutput.Kind.TEXT,
                            text = truncatedToolCallNotice,
                        ),
                        isError = true,
                    )
                } else {
                    executeCapability(call)
                }

                yield(
                    AgentTurnEvent.ToolCallFinished(
                        id = call.toolCallId,
                        output = result.output,
                        isError = result.isError,
                    ),
                )
                progress.transcript = progress.transcript.copy(
                    messages = progress.transcript.messages + AgentTranscript.Message.toolResult(
                        toolCallId = call.toolCallId,
                        toolName = call.name,
                        result = RuntimeJSONValue.string(result.output.text),
                        isError = result.isError,
                    ),
                )
                val activeHooks = hooks
                if (turnId != null && activeHooks != null) {
                    activeHooks.post(
                        AgentHookNotice(
                            turnId = turnId,
                            kind = AgentHookNotice.Kind.ToolFinished(
                                AgentHookToolOutcome(
                                    toolCallId = call.toolCallId,
                                    name = call.name,
                                    isError = result.isError,
                                    outputCharacters = result.output.text.length,
                                    duration = startedAt.elapsedNow(),
                                ),
                            ),
                        ),
                    )
                }
            }

            round += 1
        }

        // 轮数用光。已经查到的东西照常交出去——丢掉它们去报一个错,对用户是净损失。
        val limitReason = AgentFinishReason(
            unified = AgentFinishReason.Unified.OTHER,
            raw = TOOL_ROUND_LIMIT_REASON,
        )
        yield(
            AgentTurnEvent.TurnCompleted(
                transcript = progress.transcript,
                finishReason = limitReason,
                usage = progress.usage,
                context = progress.snapshot,
            ),
        )
        // 轮数用光对 hook 也**不是**失败,和答完走同一个 state:该查的多半已经查到了。要区分
        // 的看 `finishReason.raw`。
        finish(
            turnId = turnId,
            state = AgentHookTurnOutcome.State.Completed,
            progress = progress,
            finishReason = limitReason,
        )
    }

    /** 这一轮的收尾通知。`turnId` 为 null 就是没挂 hook,一次派发都不发生。 */
    private suspend fun finish(
        turnId: UUID?,
        state: AgentHookTurnOutcome.State,
        progress: TurnProgress,
        finishReason: AgentFinishReason?,
    ) {
        if (turnId == null || hooks == null) return
        hooks!!.post(
            AgentHookNotice(
                turnId = turnId,
                kind = AgentHookNotice.Kind.TurnFinished(
                    AgentHookTurnOutcome(
                        state = state,
                        transcript = progress.transcript,
                        finishReason = finishReason,
                        usage = progress.usage,
                        context = progress.snapshot,
                    ),
                ),
            ),
        )
    }

    /** 跑一次能力,并把进上下文的那段输出截到预算之内。 */
    private suspend fun executeCapability(call: CapabilityInvocation): CapabilityExecutionResult {
        var result = capabilities.execute(call)
        result = result.copy(
            output = result.output.limited(
                to = policy.maxToolOutputCharacters,
                notice = policy.toolOutputTruncationNotice,
            ),
        )
        return result
    }

    /** 把这一次尝试吐出去的东西撤回来。重跑会从头再说一遍,不撤就是半句接整句。 */
    private suspend fun rollBack(
        emitted: EmittedCharacters,
        yield: suspend (AgentTurnEvent) -> Unit,
    ) {
        if (emitted.text > 0) {
            yield(AgentTurnEvent.TextRolledBack(characterCount = emitted.text))
        }
        if (emitted.reasoning > 0) {
            yield(AgentTurnEvent.ReasoningRolledBack(characterCount = emitted.reasoning))
        }
    }

    /**
     * 流式跑一轮,顺带把 provider 报的错折成 [AgentLoopError]。
     *
     * 不抛错而是返回 [RoundOutcome]:调用方要按「已经吐了多少字」来撤回半截话,而这个数
     * 只有这里知道。
     */
    private suspend fun streamRound(
        request: AgentModelRequest,
        yield: suspend (AgentTurnEvent) -> Unit,
    ): RoundOutcome {
        val emitted = EmittedCharacters()
        return try {
            var response: AgentModelResponse? = null
            client.stream(request).collect { event ->
                currentCoroutineContext().ensureActive()
                when (event) {
                    is AgentModelStreamEvent.TextDelta -> {
                        if (event.text.isEmpty()) return@collect
                        emitted.text += event.text.length
                        yield(AgentTurnEvent.TextDelta(event.text))
                    }
                    is AgentModelStreamEvent.ReasoningDelta -> {
                        if (event.text.isEmpty()) return@collect
                        emitted.reasoning += event.text.length
                        yield(AgentTurnEvent.ReasoningDelta(event.text))
                    }
                    is AgentModelStreamEvent.Completed -> response = event.response
                }
            }

            val completed = response
            if (completed == null) {
                RoundOutcome.Failed(AgentLoopError.IncompleteResponse, emitted.copy())
            } else {
                val failure = validate(completed)
                if (failure != null) {
                    RoundOutcome.Failed(failure, emitted.copy())
                } else {
                    RoundOutcome.Completed(completed)
                }
            }
        } catch (error: Throwable) {
            RoundOutcome.Failed(error, emitted.copy())
        }
    }

    /**
     * 跨过水位线就叫 summarizer 写一份整段摘要。
     *
     * 失败不上抛:总结只是省 token 的手段,它挂了应该退回机械压缩,而不是让用户这一句
     * 问不出去。但要留下一个事件——静默降级等于线上出问题时无从查起。
     */
    private suspend fun summarize(
        history: List<AgentChatMessageDTO>,
        runtimeTranscript: AgentTranscript,
        profile: AgentModelProfile,
        definitions: List<CapabilityDefinition>,
        reserved: Int?,
        calibrationScale: Double?,
        isRecoveringFromOverflow: Boolean,
        yield: suspend (AgentTurnEvent) -> Unit,
    ): CompactedSpan? {
        val summarizer = this.summarizer ?: return null
        val planner = makePlanner(
            profile = profile,
            definitions = definitions,
            reserved = reserved,
            calibrationScale = calibrationScale,
            isRecoveringFromOverflow = isRecoveringFromOverflow,
        )
        val plan = planner.summarizationPlan(
            history = history,
            runtimeTranscript = runtimeTranscript,
            force = isRecoveringFromOverflow,
        ) ?: return null

        val reason = if (isRecoveringFromOverflow) {
            AgentCompactionReason.OVERFLOW_RECOVERY
        } else {
            AgentCompactionReason.THRESHOLD
        }
        yield(AgentTurnEvent.CompactionStarted(reason))
        return try {
            CompactedSpan(plan.ownerMessageID, summarizer.summarize(plan))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            yield(AgentTurnEvent.CompactionFailed(reason = reason, message = describe(error)))
            null
        }
    }

    private fun plan(
        history: List<AgentChatMessageDTO>,
        runtimeTranscript: AgentTranscript,
        profile: AgentModelProfile,
        definitions: List<CapabilityDefinition>,
        reserved: Int?,
        calibrationScale: Double?,
        isRecoveringFromOverflow: Boolean,
    ): ConversationHistoryPlanner.PreparedHistory =
        makePlanner(
            profile = profile,
            definitions = definitions,
            reserved = reserved,
            calibrationScale = calibrationScale,
            isRecoveringFromOverflow = isRecoveringFromOverflow,
        ).prepare(history = history, runtimeTranscript = runtimeTranscript)

    private fun makePlanner(
        profile: AgentModelProfile,
        definitions: List<CapabilityDefinition>,
        reserved: Int?,
        calibrationScale: Double?,
        isRecoveringFromOverflow: Boolean,
    ): ConversationHistoryPlanner {
        val client = this.client
        val calibration = ContextCalibration(scale = calibrationScale)
        return ConversationHistoryPlanner(
            systemInstruction = systemInstruction,
            profile = profile,
            reservedOutputTokens = reserved,
            compactor = compactor,
            migrationPolicy = migrationPolicy,
            policy = policy,
            isRecoveringFromOverflow = isRecoveringFromOverflow,
            // 逐条计价用不带工具的那份:工具 schema 每次请求只算一遍,不该跟着每条消息
            // 重新序列化。
            estimateMessageTokens = { transcript ->
                calibration.apply(
                    to = client.estimateTokens(
                        AgentModelRequest(
                            profile = profile,
                            prompt = transcript,
                            capabilities = emptyList(),
                        ),
                    ),
                )
            },
            estimateTokens = { transcript ->
                calibration.apply(
                    to = client.estimateTokens(
                        AgentModelRequest(
                            profile = profile,
                            prompt = transcript,
                            capabilities = definitions,
                        ),
                    ),
                )
            },
        )
    }

    /**
     * 这一轮算不算失败。算的话返回该报的错——注意 `.length` + 工具调用不在这里,
     * 那一档是可以自愈的,由主流程降级成一条 error 结果。
     */
    private fun validate(response: AgentModelResponse): Throwable? {
        response.failureMessage?.let { return AgentLoopError.Service(it) }
        val reason = response.finishReason ?: return AgentLoopError.IncompleteResponse
        return when (reason.unified) {
            AgentFinishReason.Unified.ERROR ->
                AgentLoopError.Service(reason.raw ?: "model execution failed")
            AgentFinishReason.Unified.CONTENT_FILTER -> AgentLoopError.ContentFilter
            else -> null
        }
    }

    /** 这一次尝试已经吐出去多少字。重跑之前要按它把半截话撤回来。 */
    private data class EmittedCharacters(
        var text: Int = 0,
        var reasoning: Int = 0,
    )

    private sealed class RoundOutcome {
        data class Completed(val response: AgentModelResponse) : RoundOutcome()
        data class Failed(val error: Throwable, val emitted: EmittedCharacters) : RoundOutcome()
    }

    private data class TurnProgress(
        var transcript: AgentTranscript = AgentTranscript(),
        var usage: AgentUsage? = null,
        var snapshot: TurnContextSnapshotDTO? = null,
    )

    private data class CompactedSpan(
        val messageID: UUID,
        val artifact: CompactionArtifact,
    )

    companion object {
        /**
         * 工具轮数用光时写进 finish reason 的记号。
         *
         * 用光轮数不是错误:该查的多半已经查到了,把已经拿到的东西全部丢掉去报一个「查询次数
         * 过多」,对用户来说是净损失。这一轮照常收尾,记号留给 app 决定要不要提示。
         */
        const val TOOL_ROUND_LIMIT_REASON = "tool-round-limit"

        private val DEFAULT_TRUNCATED_TOOL_CALL_NOTICE =
            "This tool call was not executed: the response hit the output token limit, so its " +
                "arguments may be incomplete. Re-issue it with complete arguments."

        /**
         * 留给窗口大小未知的 provider:按窗口的十分之一预留输出,夹在 1k–16k 之间,再被窗口的
         * 四分之一和模型自己的输出上限各压一道。
         *
         * 上限从 4k 提到 16k 是有原因的:20 万窗口只留 4k 给输出,意味着可以把 19.6 万塞满,
         * 然后模型没地方说话——尤其是带 reasoning 的模型,思考本身就要烧掉几千。
         * 小窗口那边由 `window / 4` 兜着,不会出现「预留比历史还多」。
         */
        fun reservedOutputTokens(profile: AgentModelProfile): Int? {
            val window = profile.contextWindow ?: return null
            val heuristic = minOf(maxOf(window / 10, 1_024), 16_384)
            val capped = minOf(heuristic, maxOf(1, window / 4))
            val limit = profile.maxOutputTokens
            if (limit == null || limit <= 0) return capped
            return maxOf(256, minOf(capped, limit))
        }

        /**
         * 拿去给分类器看的那段字符串。
         *
         * provider 的原文最有信息量,所以 `.service` 直接透出原文。其余几个是 runtime 自己造的
         * 错误,翻成分类器认得的英文描述——它只会看字符串,不认识具体类型。
         */
        fun describe(error: Throwable): String = when (error) {
            is AgentLoopError.Service -> error.detail
            is AgentLoopError.ContentFilter -> "content filter"
            is AgentLoopError.IncompleteResponse -> "stream ended without a finish reason"
            is AgentLoopError.ContextWindowExceeded -> "context window exceeded"
            else -> error.message ?: error.toString()
        }
    }
}
