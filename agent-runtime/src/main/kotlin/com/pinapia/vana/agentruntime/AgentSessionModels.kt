package com.pinapia.vana.agentruntime

import java.util.UUID
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AgentUsage(
    val inputTokens: Input = Input(),
    val outputTokens: Output = Output(),
    val raw: RuntimeJSONValue? = null,
) {
    @Serializable
    data class Input(
        val total: Int? = null,
        val noCache: Int? = null,
        val cacheRead: Int? = null,
        val cacheWrite: Int? = null,
    )

    @Serializable
    data class Output(
        val total: Int? = null,
        val text: Int? = null,
        val reasoning: Int? = null,
    )
}

@Serializable
data class AgentFinishReason(
    val unified: Unified,
    val raw: String? = null,
) {
    @Serializable
    enum class Unified {
        @SerialName("stop") STOP,
        @SerialName("length") LENGTH,
        @SerialName("content-filter") CONTENT_FILTER,
        @SerialName("tool-calls") TOOL_CALLS,
        @SerialName("error") ERROR,
        @SerialName("other") OTHER,
    }
}

@Serializable
data class AgentToolOutput(
    val kind: Kind,
    /** 送进模型上下文的那段文本。 */
    val text: String,
    /** 只给界面看的结构化负载(图表、原始行)。不进上下文。 */
    val metadata: RuntimeJSONValue? = null,
) {
    @Serializable
    enum class Kind {
        @SerialName("table") TABLE,
        @SerialName("text") TEXT,
    }

    /**
     * 截到 `maxCharacters` 以内。
     *
     * 这是上下文预算的**源头闸门**:一次工具调用返回几万字符,后面所有的压缩都只是在
     * 亡羊补牢——那一轮的原始输出在被压之前必须先原样发出去一次。截断放在 loop 里而不是
     * 每个能力里,是因为能力是可以随便加的,防线不能指望每个作者都记得。
     *
     * 只截 `text`(进模型的那份),`metadata` 原样留着——图表和原始行只给界面看,不占预算。
     */
    fun limited(to: Int, notice: String): AgentToolOutput {
        if (to <= 0 || text.length <= to) return this

        // 按行切。半行数字比没有数字更危险:模型会把 "08-0" 当成一个日期读下去。
        var kept = ""
        for (line in text.split("\n")) {
            if (kept.length + line.length + 1 > to) break
            kept = if (kept.isEmpty()) line else "$kept\n$line"
        }
        if (kept.isEmpty()) {
            kept = text.take(to)
        }

        val formatted = try {
            String.format(notice, text.length - kept.length, text.length)
        } catch (_: Exception) {
            notice
        }
        return copy(text = kept + "\n" + formatted)
    }
}

@Serializable
data class ToolCallRecordDTO(
    val id: String,
    val name: String,
    val input: String,
    val output: AgentToolOutput? = null,
    val isError: Boolean = false,
)

/**
 * 一轮被压缩后留下的东西。
 *
 * 显式区分两个读者:`visibleSummary` 给用户,`replaySummary` 给模型。生成方式见
 * [TranscriptCompactor]。
 */
@Serializable
data class CompactionArtifact(
    val kind: Kind = Kind.ANSWER_ONLY,
    val visibleSummary: String,
    val replaySummary: AgentTranscript.Message,
    val sourceMessageIDs: List<String> = emptyList(),
    val foldedToolCalls: Int = 0,
    val createdAt: Instant = Clock.System.now(),
) {
    @Serializable
    enum class Kind {
        /** 只留可见回答——这轮没跑工具。 */
        @SerialName("answerOnly") ANSWER_ONLY,
        /** 可见回答 + 工具轨迹摘要。 */
        @SerialName("toolDigest") TOOL_DIGEST,
        /** 模型自己写的 summary(还没接,占位:接上以后 artifact 的形状不用变)。 */
        @SerialName("modelGenerated") MODEL_GENERATED,
    }

    companion object {
        /** 没有工具轨迹时的最简形态。 */
        fun answerOnly(summary: String, sourceMessageIDs: List<UUID>): CompactionArtifact =
            CompactionArtifact(
                kind = Kind.ANSWER_ONLY,
                visibleSummary = summary,
                replaySummary = AgentTranscript.Message.assistantSummary(summary, sourceMessageIDs),
                sourceMessageIDs = sourceMessageIDs.map { it.toString() },
            )
    }
}

fun CompactionArtifact.sourceUUIDs(): List<UUID> =
    sourceMessageIDs.mapNotNull {
        runCatching { UUID.fromString(it) }.getOrNull()
    }

@Serializable
data class TurnContextSnapshotDTO(
    val providerId: String,
    val requestedModelId: String,
    val servedModelId: String? = null,
    val contextWindow: Int? = null,
    val reservedOutputTokens: Int? = null,
    val estimatedPromptTokens: Int? = null,
    val actualPromptTokens: Int? = null,
    val compactedAssistantMessages: Int = 0,
    val droppedConversationTurns: Int = 0,
    /** 这一轮的 prompt 里有几段是整段摘要(而不是原样回放)。 */
    val summarizedSpans: Int = 0,
    val migrationNotes: List<String> = emptyList(),
) {
    fun matches(providerId: String, requestedModelId: String): Boolean {
        if (this.providerId != providerId) return false
        val effectiveModelId = servedModelId ?: this.requestedModelId
        return effectiveModelId == requestedModelId
    }

    fun matches(profile: AgentModelProfile): Boolean =
        matches(providerId = profile.providerId, requestedModelId = profile.modelId)
}

@Serializable
data class StoredAgentTurn(
    /** 这轮真正发生的一切,原样存着——回放的首选。 */
    val exactTranscript: AgentTranscript = AgentTranscript(),
    /** 上下文不够时用它顶替 `exactTranscript`。 */
    val compaction: CompactionArtifact? = null,
    val finishReason: AgentFinishReason? = null,
    val usage: AgentUsage? = null,
    val state: State? = null,
    val context: TurnContextSnapshotDTO? = null,
    /**
     * 列表上另有气泡、但内容已经在这份 `exactTranscript` 里的那几条。
     *
     * 两种来源,同一个问题:用户在工具轮边界补的话(列表里要有气泡,transcript 中间也有
     * 一份),以及被那句话劈开的前半段回复(界面上另起了一条气泡,transcript 里它和后半段
     * 是连着的一轮)。回放时都得跳过列表上那一条,否则同一段内容在 prompt 里出现两遍。
     */
    val inlinedMessageIDs: List<String> = emptyList(),
) {
    @Serializable
    enum class State {
        @SerialName("completed") COMPLETED,
        @SerialName("stopped") STOPPED,
        @SerialName("failed") FAILED,
    }
}

fun StoredAgentTurn.inlinedUUIDs(): List<UUID> =
    inlinedMessageIDs.mapNotNull {
        runCatching { UUID.fromString(it) }.getOrNull()
    }

@Serializable
data class AgentChatMessageDTO(
    val id: String = UUID.randomUUID().toString(),
    val role: Role,
    val text: String,
    /**
     * 随这条用户消息一起发给模型的东西(图片、文档)。assistant 那边永远是空的。
     *
     * **和 `text` 并列,不是它的替代**:一张图配一句「这是什么」,两样都要到模型手里。
     * 顺序上 file 排在 text 后面,让那句话先把图的来历说清楚。
     *
     * 摘要那条路上不带它(`SummarizationPlan` 只取 `text`):压缩的产物是文字,再把原图
     * 附一遍等于压了个寂寞,而这几张图的钱是每一轮都要重付的。
     */
    val files: List<AgentTranscript.FilePart> = emptyList(),
    /**
     * `text` 是 app 写给用户的占位("已停止回复"之类),不是模型说的话。
     *
     * 有这个标记,runtime 就不用靠比对某句中文来判断该不该回放——那句话属于 app,
     * 不属于通用 core。
     */
    val textIsPlaceholder: Boolean = false,
    /**
     * 模型这一轮的思考,几段拼在一起。
     *
     * 只给界面看。回放给模型的那一份在 `storedTurn.exactTranscript` 的 `.reasoning` 里,
     * 两份不能互相顶替:transcript 那份要带 provider metadata 原样发回去,这份是纯文本。
     */
    val reasoning: String = "",
    val toolCalls: List<ToolCallRecordDTO> = emptyList(),
    val storedTurn: StoredAgentTurn = StoredAgentTurn(),
    val errorDescription: String? = null,
    val createdAt: Instant? = Clock.System.now(),
) {
    @Serializable
    enum class Role {
        @SerialName("user") USER,
        @SerialName("assistant") ASSISTANT,
    }

    val uuid: UUID
        get() = UUID.fromString(id)

    val exactReplayMessages: List<AgentTranscript.Message>
        get() = storedTurn.exactTranscript.messages

    /** 没有 exact transcript 时(老会话、或工具跑完就被停掉的那轮)从 app 侧记录重建。 */
    val reconstructedReplayMessages: List<AgentTranscript.Message>
        get() {
            val completed = toolCalls.mapNotNull { call ->
                val output = call.output ?: return@mapNotNull null
                call to output
            }
            val messages = mutableListOf<AgentTranscript.Message>()
            val parts = mutableListOf<AgentTranscript.Part>()
            if (text.isNotEmpty() && !textIsPlaceholder) {
                parts += AgentTranscript.Part.Text(text)
            }
            parts += completed.map { (call, _) ->
                AgentTranscript.Part.ToolCallPart(
                    AgentTranscript.ToolCall(
                        toolCallId = call.id,
                        toolName = call.name,
                        input = call.input,
                    ),
                )
            }
            if (parts.isNotEmpty()) {
                messages += AgentTranscript.Message(role = AgentTranscript.Role.ASSISTANT, parts = parts)
            }
            messages += completed.map { (call, output) ->
                AgentTranscript.Message.toolResult(
                    toolCallId = call.id,
                    toolName = call.name,
                    result = RuntimeJSONValue.string(output.text),
                    isError = call.isError,
                )
            }
            return messages
        }

    /** 压缩形态。存了 artifact 就用存的,没存就现算一个。 */
    fun compactReplayMessages(compactor: TranscriptCompactor = TranscriptCompactor.default): List<AgentTranscript.Message> {
        storedTurn.compaction?.let { return listOf(it.replaySummary) }
        val artifact = compactor.artifact(forMessage = this) ?: return emptyList()
        return listOf(artifact.replaySummary)
    }

    val hasReplayableContent: Boolean
        get() = exactReplayMessages.isNotEmpty() || reconstructedReplayMessages.isNotEmpty()
}
