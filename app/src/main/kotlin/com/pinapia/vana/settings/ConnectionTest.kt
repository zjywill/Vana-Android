package com.pinapia.vana.settings

import com.pinapia.vana.agent.OpenAICompatibleModelClient
import com.pinapia.vana.agent.UserFacingModelFailure
import com.pinapia.vana.agentruntime.AgentModelProfile
import com.pinapia.vana.agentruntime.AgentModelRequest
import com.pinapia.vana.agentruntime.AgentModelStreamEvent
import com.pinapia.vana.agentruntime.AgentTranscript
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

object ConnectionTest {
    sealed interface Result {
        data object Ok : Result
        data class Failed(val message: String) : Result
    }

    suspend fun run(providerId: String, modelId: String, rawApiKey: String): Result {
        val key = ApiKeyNormalizer.normalize(rawApiKey)
        if (!key.isValid) return Result.Failed(key.error ?: "先填写 API 密钥。")
        val provider = CloudCatalog.provider(providerId)
            ?: return Result.Failed("先选择一个可用的 Provider。")
        val wire = provider.wireProtocol
            ?: return Result.Failed("当前 Provider 的协议还不受支持。")
        if (modelId.isBlank()) return Result.Failed("先选择一个模型。")
        val model = CloudCatalog.model(modelId, providerId)

        return try {
            val client = OpenAICompatibleModelClient(
                profile = AgentModelProfile(
                    providerId = providerId,
                    modelId = modelId,
                    contextWindow = model?.contextWindow,
                    maxOutputTokens = 4,
                ),
                apiKey = key.value,
                baseUrl = provider.apiBaseUrl,
                wireProtocol = wire,
                thinkingEnabled = false,
                supportsReasoning = model?.supportsReasoning == true,
            )
            val completed = withTimeout(30_000) {
                client.stream(
                    AgentModelRequest(
                        profile = client.profile,
                        prompt = AgentTranscript(
                            listOf(AgentTranscript.Message.user("只回复 OK")),
                        ),
                        capabilities = emptyList(),
                    ),
                ).first { it is AgentModelStreamEvent.Completed } as AgentModelStreamEvent.Completed
            }
            val failure = completed.response.failureMessage
            if (failure == null) Result.Ok else Result.Failed(UserFacingModelFailure.message(failure))
        } catch (error: Throwable) {
            Result.Failed(UserFacingModelFailure.message(error))
        }
    }
}
