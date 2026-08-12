package com.pinapia.vana.agent

import com.pinapia.vana.agentruntime.AgentHook
import com.pinapia.vana.agentruntime.AgentHookNotice
import com.pinapia.vana.agentruntime.AgentHookTurnOutcome
import com.pinapia.vana.agentruntime.AgentModelProfile
import com.pinapia.vana.agentruntime.AgentModelRequest
import com.pinapia.vana.agentruntime.AgentModelStreamEvent
import com.pinapia.vana.agentruntime.AgentTranscript
import com.pinapia.vana.settings.CloudCatalog
import com.pinapia.vana.settings.SecureKeyStore
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive

/**
 * 答完之后生成追问 chip。第一颗「详细一点」由 UI 固定,这里只产出后面几条。
 */
class FollowUpSuggestionHook(
    private val providerId: String,
    private val model: String,
    private val apiKey: String,
    private val onSuggestions: (List<String>) -> Unit,
) : AgentHook {
    private var pendingQuestion: String? = null
    private var generationJob: Job? = null
    private var activeTurnId: UUID? = null

    override suspend fun observe(notice: AgentHookNotice) {
        when (val kind = notice.kind) {
            is AgentHookNotice.Kind.TurnStarted -> {
                generationJob?.cancel()
                generationJob = null
                activeTurnId = notice.turnId
                pendingQuestion = kind.start.history.lastOrNull {
                    it.role == com.pinapia.vana.agentruntime.AgentChatMessageDTO.Role.USER
                }?.text?.takeIf { it.isNotBlank() }
                onSuggestions(emptyList())
            }
            is AgentHookNotice.Kind.TurnFinished -> {
                if (notice.turnId != activeTurnId) return
                if (kind.outcome.state !is AgentHookTurnOutcome.State.Completed) return
                val answer = kind.outcome.transcript.messages
                    .asReversed()
                    .firstOrNull { it.role == AgentTranscript.Role.ASSISTANT }
                    ?.text
                    ?.trim()
                    .orEmpty()
                if (answer.isEmpty()) return
                val question = pendingQuestion.orEmpty()
                val toolNames = kind.outcome.transcript.messages.flatMap { message ->
                    message.parts.mapNotNull { part ->
                        (part as? AgentTranscript.Part.ToolCallPart)?.toolCall?.toolName
                    }
                }.distinct()
                val suggestions = runCatching {
                    FollowUpSuggester(
                        providerId = providerId,
                        model = model,
                        apiKey = apiKey,
                    ).suggestions(question = question, answer = answer, toolNames = toolNames)
                }.getOrDefault(emptyList())
                currentCoroutineContext().ensureActive()
                if (suggestions.size >= 2) {
                    onSuggestions(suggestions.take(3))
                } else {
                    onSuggestions(emptyList())
                }
            }
            is AgentHookNotice.Kind.ToolFinished -> Unit
        }
    }
}

class FollowUpSuggester(
    private val providerId: String,
    private val model: String,
    private val apiKey: String,
) {
    suspend fun suggestions(question: String, answer: String, toolNames: List<String>): List<String> {
        val provider = CloudCatalog.provider(providerId) ?: return emptyList()
        val modelInfo = CloudCatalog.model(model, providerId)
        val client = OpenAICompatibleModelClient(
            profile = AgentModelProfile(
                providerId = providerId,
                modelId = model,
                contextWindow = modelInfo?.contextWindow,
                maxOutputTokens = 120,
            ),
            apiKey = apiKey,
            baseUrl = provider.apiBaseUrl,
            wireProtocol = provider.requireWireProtocol(),
            thinkingEnabled = false,
            supportsReasoning = modelInfo?.supportsReasoning == true,
        )
        val clippedAnswer = if (answer.length <= 1600) {
            answer
        } else {
            answer.take(600) + "\n…\n" + answer.takeLast(600)
        }
        val toolsLine = if (toolNames.isEmpty()) "（无）" else toolNames.joinToString("、")
        val prompt = """
            根据下面这段健康对话，写出 3 条用户可能接着问的短句。
            每条 3–12 个字，只要问句本身，不要编号，不要解释。
            显式关闭思考，直接给出三行。

            用户问：$question
            助手答：$clippedAnswer
            查过：$toolsLine
        """.trimIndent()
        val request = AgentModelRequest(
            profile = client.profile,
            prompt = AgentTranscript(
                messages = listOf(
                    AgentTranscript.Message.system("你只输出三行中文追问，不要其它内容。"),
                    AgentTranscript.Message.user(prompt),
                ),
            ),
            capabilities = emptyList(),
        )
        var text = ""
        client.stream(request).collect { event ->
            if (event is AgentModelStreamEvent.TextDelta) text += event.text
        }
        return parse(text)
    }

    companion object {
        val ALWAYS_ON = "详细一点"
        val FALLBACKS = listOf("有什么建议？", "和上周比呢？", "可能是什么原因？")

        fun parse(raw: String): List<String> {
            val lines = raw.lineSequence()
                .map { it.trim().trimStart('1', '2', '3', '4', '5', '.', '、', ')', '）', '-', '•') }
                .map { it.trim() }
                .filter { it.length in 3..12 }
                .filter { it != ALWAYS_ON }
                .distinct()
                .take(3)
                .toList()
            return if (lines.size >= 2) lines else emptyList()
        }

        fun displayChips(generated: List<String>): List<String> {
            val rest = if (generated.size >= 2) generated else FALLBACKS
            return listOf(ALWAYS_ON) + rest.filter { it != ALWAYS_ON }.take(3)
        }
    }
}
