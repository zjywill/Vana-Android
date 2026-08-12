package com.pinapia.vana.agentruntime

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * 一次请求打给哪个模型,以及这个模型的窗口有多大。
 *
 * 预算和校准都按 provider+model 归档:换了模型,之前那套估算就不作数了。
 */
@Serializable
data class AgentModelProfile(
    val providerId: String,
    val modelId: String,
    val contextWindow: Int? = null,
    val maxOutputTokens: Int? = null,
)

data class AgentModelRequest(
    val profile: AgentModelProfile,
    val prompt: AgentTranscript,
    val capabilities: List<CapabilityDefinition>,
)

/**
 * 一轮请求结束时模型给出的全部东西。
 *
 * `assistantMessage` 是要原样存进 transcript 的那条(可能同时含文本和 tool_use);
 * `pendingCalls` 是本轮要执行的调用。两者分开:前者管回放,后者管执行。
 */
data class AgentModelResponse(
    val assistantMessage: AgentTranscript.Message? = null,
    val pendingCalls: List<CapabilityInvocation> = emptyList(),
    val finishReason: AgentFinishReason? = null,
    val usage: AgentUsage? = null,
    val servedModelId: String? = null,
    /** provider 在流里报的错。有值就当这轮失败,不看 finishReason。 */
    val failureMessage: String? = null,
)

sealed class AgentModelStreamEvent {
    data class TextDelta(val text: String) : AgentModelStreamEvent()

    /**
     * 模型的思考。和 [TextDelta] 分开发:两者在界面上不是一回事,思考默认要能折起来。
     *
     * 不是所有模型都有,有的模型只给摘要而不给原文——这两种情况都表现为这个事件不出现,
     * 上层不该把「没有思考事件」当成异常。
     */
    data class ReasoningDelta(val text: String) : AgentModelStreamEvent()

    data class Completed(val response: AgentModelResponse) : AgentModelStreamEvent()
}

/**
 * runtime 眼里的「模型」就这么多:能估 token,能流式跑一轮。
 *
 * AIKit、FoundationModels 或任何别的 SDK 都各写一个适配器实现它——runtime 本身不 import
 * 任何模型 SDK,这是「通用 agent core」和「某个 SDK 的封装」的分界线。
 */
interface AgentModelClient {
    val profile: AgentModelProfile

    /** 估算这次请求的 prompt token 数。tokenizer 因 provider 而异,所以放在适配层。 */
    fun estimateTokens(request: AgentModelRequest): Int

    fun stream(request: AgentModelRequest): Flow<AgentModelStreamEvent>
}
