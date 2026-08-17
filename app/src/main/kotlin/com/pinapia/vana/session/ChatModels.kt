package com.pinapia.vana.session

import com.pinapia.vana.agentruntime.AgentChatMessageDTO
import com.pinapia.vana.agentruntime.AgentPendingInput
import com.pinapia.vana.agentruntime.AgentFinishReason
import com.pinapia.vana.agentruntime.AgentToolOutput
import com.pinapia.vana.agentruntime.AgentTranscript
import com.pinapia.vana.agentruntime.AgentTurnSink
import com.pinapia.vana.agentruntime.AgentUsage
import com.pinapia.vana.agentruntime.CompactionArtifact
import com.pinapia.vana.agentruntime.StoredAgentTurn
import com.pinapia.vana.agentruntime.ToolCallRecordDTO
import com.pinapia.vana.agentruntime.TurnContextSnapshotDTO
import com.pinapia.vana.ask.AskUserAnswer
import com.pinapia.vana.ask.AskUserQuestion
import com.pinapia.vana.ask.AskUserTools
import com.pinapia.vana.exercises.ExerciseSelection
import com.pinapia.vana.exercises.ExerciseTools
import com.pinapia.vana.vision.ChatAttachment
import java.util.UUID
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class ToolCallRecord(
    val id: String,
    val name: String,
    val input: String,
    var output: String? = null,
    var askQuestion: AskUserQuestion? = null,
    var askAnswer: AskUserAnswer? = null,
    var exerciseIDs: List<String> = emptyList(),
    var isError: Boolean = false,
    var textOffset: Int? = null,
    var reasoningOffset: Int? = null,
) {
    /** 成功的 `ask_user` 只出问题卡，不出胶囊——胶囊会在正文里切一刀，而那张卡排在整条回复最后。 */
    val showsChip: Boolean get() = askQuestion == null

    fun toDTO(): ToolCallRecordDTO = ToolCallRecordDTO(
        id = id,
        name = name,
        input = input,
        output = output?.let {
            AgentToolOutput(
                kind = AgentToolOutput.Kind.TEXT,
                text = it,
                metadata = when {
                    askQuestion != null -> AskUserQuestion.encodeForToolMetadata(askQuestion!!)
                    exerciseIDs.isNotEmpty() -> ExerciseSelection.encodeForToolMetadata(
                        ExerciseSelection(moveIDs = exerciseIDs),
                    )
                    else -> null
                },
            )
        },
        isError = isError,
    )

    companion object {
        fun fromDTO(dto: ToolCallRecordDTO): ToolCallRecord = ToolCallRecord(
            id = dto.id,
            name = dto.name,
            input = dto.input,
            output = dto.output?.text,
            askQuestion = AskUserQuestion.decode(dto.output?.metadata),
            exerciseIDs = ExerciseSelection.decode(dto.output?.metadata)?.moveIDs.orEmpty(),
            isError = dto.isError,
        )
    }
}

@Serializable
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: Role,
    var text: String,
    var attachments: List<ChatAttachment> = emptyList(),
    var reasoning: String = "",
    var toolCalls: List<ToolCallRecord> = emptyList(),
    var storedTurn: StoredAgentTurn = StoredAgentTurn(),
    var textIsPlaceholder: Boolean = false,
    @Transient var isQueued: Boolean = false,
    var errorDescription: String? = null,
    val createdAt: Instant = Clock.System.now(),
) : AgentTurnSink {
    @Serializable
    enum class Role {
        @SerialName("user") USER,
        @SerialName("assistant") ASSISTANT,
    }

    val uuid: UUID get() = UUID.fromString(id)

    val modelText: String
        get() = ChatAttachment.modelText(typed = text, attachments = attachments)

    val modelFiles: List<AgentTranscript.FilePart>
        get() = attachments.mapNotNull { attachment ->
            if (!attachment.carriesImage) return@mapNotNull null
            val payload = attachment.imagePayload ?: return@mapNotNull null
            AgentTranscript.FilePart(
                mediaType = "image/jpeg",
                data = AgentTranscript.FilePart.Payload.Base64(payload),
            )
        }

    fun toDTO(): AgentChatMessageDTO = AgentChatMessageDTO(
        id = id,
        role = when (role) {
            Role.USER -> AgentChatMessageDTO.Role.USER
            Role.ASSISTANT -> AgentChatMessageDTO.Role.ASSISTANT
        },
        text = if (role == Role.USER) modelText else text,
        files = if (role == Role.USER) modelFiles else emptyList(),
        textIsPlaceholder = textIsPlaceholder,
        reasoning = reasoning,
        toolCalls = toolCalls.map { it.toDTO() },
        storedTurn = storedTurn,
        errorDescription = errorDescription,
        createdAt = createdAt,
    )

    override fun appendText(delta: String) {
        if (textIsPlaceholder) {
            text = ""
            textIsPlaceholder = false
        }
        text += delta
    }

    override fun appendReasoning(delta: String) {
        reasoning += delta
    }

    override fun startToolCall(record: ToolCallRecordDTO) {
        toolCalls = toolCalls + ToolCallRecord.fromDTO(record).copy(
            textOffset = text.length,
            reasoningOffset = reasoning.length,
        )
    }

    override fun finishToolCall(id: String, output: AgentToolOutput, isError: Boolean) {
        toolCalls = toolCalls.map {
            if (it.id != id) {
                it
            } else {
                it.copy(
                    output = output.text,
                    isError = isError,
                    askQuestion = if (it.name == AskUserTools.ASK_TOOL_NAME) {
                        AskUserQuestion.decode(output.metadata)
                    } else {
                        it.askQuestion
                    },
                    exerciseIDs = if (it.name == ExerciseTools.SUGGEST_TOOL_NAME) {
                        ExerciseSelection.decode(output.metadata)?.moveIDs.orEmpty()
                    } else {
                        it.exerciseIDs
                    },
                )
            }
        }
    }

    override fun completeTurn(
        transcript: AgentTranscript,
        finishReason: AgentFinishReason?,
        usage: AgentUsage?,
        context: TurnContextSnapshotDTO?,
    ) {
        storedTurn = storedTurn.copy(
            exactTranscript = transcript,
            finishReason = finishReason,
            usage = usage,
            state = StoredAgentTurn.State.COMPLETED,
            context = context,
        )
    }

    override fun rollBackText(characterCount: Int) {
        if (characterCount <= 0) return
        text = text.dropLast(minOf(characterCount, text.length))
    }

    override fun rollBackReasoning(characterCount: Int) {
        if (characterCount <= 0) return
        reasoning = reasoning.dropLast(minOf(characterCount, reasoning.length))
    }

    override fun acceptPendingInput(inputs: List<AgentPendingInput>) {
        storedTurn = storedTurn.copy(
            inlinedMessageIDs = storedTurn.inlinedMessageIDs + inputs.map { it.id.toString() },
        )
    }

    override fun markStopped() {
        if (text.isBlank()) {
            text = "已停止回复"
            textIsPlaceholder = true
        }
        storedTurn = storedTurn.copy(state = StoredAgentTurn.State.STOPPED)
    }

    override fun markFailed(description: String) {
        if (text.isBlank()) {
            text = "无法回复：$description"
            textIsPlaceholder = true
        }
        errorDescription = description
        storedTurn = storedTurn.copy(state = StoredAgentTurn.State.FAILED)
    }

    fun applyCompaction(artifact: CompactionArtifact) {
        storedTurn = storedTurn.copy(compaction = artifact)
    }

    val hasRunningToolCall: Boolean
        get() = toolCalls.any { it.output == null }

    val hasVisibleTurnContent: Boolean
        get() = text.isNotEmpty() || reasoning.isNotEmpty() || toolCalls.isNotEmpty()

    /**
     * 这一轮按**发生顺序**摊平:说了一段、查了一次、又说了一段。
     *
     * 气泡原来是写死的三段——思考 chip、**所有**工具 chip、**整段**正文——而模型这一轮实际
     * 是交错的。两处代价:
     *
     * - **跳动**。每发起一次调用,一颗 chip 插进正文**上面**,底下已经写好的十几行整个往下挪。
     * - **因果反了**。模型写完「现在查这三项：」才去查,而那三颗 chip 在这句话**上面**。
     *
     * 按顺序摊开之后,新东西永远追加在**末尾**,上面的一个像素都不动。
     * 没有 [ToolCallRecord.textOffset] 的旧会话退回老排法,不去猜切点。
     */
    val turnSegments: List<TurnSegment>
        get() = TurnSegmenter.segments(this)

    companion object {
        fun fromDTO(dto: AgentChatMessageDTO): ChatMessage = ChatMessage(
            id = dto.id,
            role = when (dto.role) {
                AgentChatMessageDTO.Role.USER -> Role.USER
                AgentChatMessageDTO.Role.ASSISTANT -> Role.ASSISTANT
            },
            text = dto.text,
            reasoning = dto.reasoning,
            toolCalls = dto.toolCalls.map { ToolCallRecord.fromDTO(it) },
            storedTurn = dto.storedTurn,
            textIsPlaceholder = dto.textIsPlaceholder,
            errorDescription = dto.errorDescription,
            createdAt = dto.createdAt ?: Clock.System.now(),
        )
    }
}

sealed class TurnSegment {
    abstract val stableId: String

    data class Reasoning(val text: String, val index: Int) : TurnSegment() {
        override val stableId: String get() = "reasoning-$index"
    }

    data class Text(val text: String, val index: Int) : TurnSegment() {
        override val stableId: String get() = "text-$index"
    }

    data class Tool(val call: ToolCallRecord) : TurnSegment() {
        override val stableId: String get() = call.id
    }
}

/**
 * 按字符位置把累积的思考/正文切成几段,和工具 chip 交错。
 * 思考和正文是两条独立的流,各走一份 cursor。
 */
private object TurnSegmenter {
    fun segments(message: ChatMessage): List<TurnSegment> {
        val chips = message.toolCalls.filter { it.showsChip }
        val segments = mutableListOf<TurnSegment>()
        val text = Splitter(message.text) { chunk, index -> TurnSegment.Text(chunk, index) }
        val think = Splitter(message.reasoning) { chunk, index -> TurnSegment.Reasoning(chunk, index) }

        val anchored = chips.any { it.reasoningOffset != null }
        if (!anchored) {
            think.appendRest(to = segments)
        }
        segments += chips.filter { it.textOffset == null }.map { TurnSegment.Tool(it) }

        for (call in chips) {
            val textOffset = call.textOffset ?: continue
            if (anchored) {
                call.reasoningOffset?.let { think.append(upTo = it, to = segments) }
            }
            text.append(upTo = textOffset, to = segments)
            segments += TurnSegment.Tool(call)
        }

        if (anchored) {
            think.appendRest(to = segments)
        }
        text.appendRest(to = segments)
        return segments
    }

    private class Splitter(
        private val source: String,
        private val kind: (String, Int) -> TurnSegment,
    ) {
        private var cursor = 0
        private var placed = 0
        private var index = 0

        fun append(upTo: Int, to: MutableList<TurnSegment>) {
            val clamped = upTo.coerceIn(placed, source.length)
            placed = clamped
            emit(through = clamped, to = to)
        }

        fun appendRest(to: MutableList<TurnSegment>) {
            emit(through = source.length, to = to)
        }

        private fun emit(through: Int, to: MutableList<TurnSegment>) {
            if (cursor >= through) return
            val chunk = source.substring(cursor, through).trim { it == '\n' || it == '\r' }
            cursor = through
            if (chunk.isEmpty()) return
            to += kind(chunk, index)
            index += 1
        }
    }
}

@Serializable
data class ChatSession(
    val id: String = UUID.randomUUID().toString(),
    var messages: List<ChatMessage> = emptyList(),
    var threadId: String? = null,
    var isDerived: Boolean = false,
    var threadTitle: String? = null,
    @Transient var isPrivate: Boolean = false,
    var memoryHarvestedMessageCount: Int = 0,
    val createdAt: Instant = Clock.System.now(),
    var updatedAt: Instant = Clock.System.now(),
) {
    val isEmpty: Boolean get() = messages.isEmpty()

    val title: String
        get() {
            val firstUser = messages.firstOrNull { it.role == ChatMessage.Role.USER }
            return SessionTitle.make(
                threadId = threadId,
                threadTitle = threadTitle,
                firstUserText = firstUser?.text,
                firstUserHasAttachments = firstUser?.attachments?.isNotEmpty() == true,
                createdAt = createdAt,
            )
        }
}

data class SessionSummary(
    val id: String,
    val title: String,
    val updatedAt: Instant,
    val messageCount: Int,
    val threadId: String? = null,
    val isDerived: Boolean = false,
) {
    val thread: SessionThread? get() = SessionThread.parse(threadId)
}

val ChatMessage.foldedSpan: Int?
    get() {
        val ids = storedTurn.compaction?.sourceMessageIDs.orEmpty()
        return if (ids.size > 1) ids.size else null
    }

val ChatMessage.compactionSummary: String?
    get() = storedTurn.compaction?.visibleSummary?.takeIf { it.isNotBlank() }
