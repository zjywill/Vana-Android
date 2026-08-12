package com.pinapia.vana.medications

import com.pinapia.vana.agent.OpenAICompatibleModelClient
import com.pinapia.vana.agentruntime.AgentModelProfile
import com.pinapia.vana.agentruntime.AgentModelRequest
import com.pinapia.vana.agentruntime.AgentModelStreamEvent
import com.pinapia.vana.agentruntime.AgentTranscript
import com.pinapia.vana.settings.CloudCatalog
import com.pinapia.vana.settings.EngineSettings
import com.pinapia.vana.settings.SecureKeyStore

/**
 * 给一条药或补剂写一句「这东西一般是干什么的」。
 */
class MedicationBriefer(
    private val providerId: String,
    private val model: String,
    private val apiKey: String,
) {
    suspend fun brief(forName: String): String? {
        val trimmed = forName.trim()
        if (trimmed.isEmpty()) return null
        val provider = CloudCatalog.provider(providerId) ?: return null
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
        val request = AgentModelRequest(
            profile = client.profile,
            prompt = AgentTranscript(
                messages = listOf(
                    AgentTranscript.Message.system(INSTRUCTIONS),
                    AgentTranscript.Message.user(trimmed),
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
        const val MAX_CHARACTERS = 60

        private val INSTRUCTIONS = """
            你在为一个健康 app 的用药清单写一句「这东西一般是干什么的」。用户会在他自己的清单里看到这句话，旁边标着「自动生成，不是给你的建议」。

            要求：
            - 只输出一句话，中文，不超过 40 个字，不要引号、不要编号、不要任何解释。
            - 只说这类药或补剂**通常**用于什么，需要的话补一句最常见的注意点。
            - 绝对不要写剂量、用法、疗程，也不要写该不该吃、什么时候吃——那是医生和药师的事。
            - 不要针对这位用户说话（不要出现「你」「建议你」），这是一句通用说明。
            - 不认识这个名字，就只输出「无」这一个字，不要猜。
        """.trimIndent()

        fun parse(text: String): String? {
            val cleaned = text
                .trim()
                .replace("\n", " ")
                .trim('「', '」', '"', '\'', '。', ' ')
            if (cleaned.isEmpty() || cleaned == "无" || cleaned.length > MAX_CHARACTERS) return null
            return cleaned
        }

        /** 用当前云端设置跑一次，写回 store。返回是否写成。 */
        suspend fun fill(
            item: MedicationItem,
            store: MedicationStore,
            engineSettings: EngineSettings,
            secureKeyStore: SecureKeyStore,
        ): Boolean {
            if (item.briefIsUserWritten) return false
            val model = engineSettings.model.trim()
            if (model.isEmpty()) return false
            val key = secureKeyStore.apiKey?.trim().orEmpty()
            if (key.isEmpty()) return false
            val provider = engineSettings.providerId.ifBlank { EngineSettings.DEFAULT_PROVIDER }
            val text = runCatching {
                MedicationBriefer(provider, model, key).brief(item.name)
            }.getOrNull() ?: return false
            return store.setGeneratedBrief(item.id, text)
        }
    }
}
