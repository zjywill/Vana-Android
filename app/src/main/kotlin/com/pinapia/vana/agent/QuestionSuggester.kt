package com.pinapia.vana.agent

import com.pinapia.vana.agentruntime.AgentModelProfile
import com.pinapia.vana.agentruntime.AgentModelRequest
import com.pinapia.vana.agentruntime.AgentModelStreamEvent
import com.pinapia.vana.agentruntime.AgentTranscript
import com.pinapia.vana.health.HealthSituation
import com.pinapia.vana.health.HealthTools
import com.pinapia.vana.settings.CloudCatalog

/**
 * 用当下时间和最近几天的数据，让模型写三条首屏问题。
 *
 * 生成失败不算错误——调用方继续用 [HealthSituation.questions]。thinking OFF。
 */
class QuestionSuggester(
    private val providerId: String,
    private val model: String,
    private val apiKey: String,
    private val situation: HealthSituation,
    private val healthTools: HealthTools,
) {
    suspend fun suggestions(): List<String> {
        val key = apiKey.trim()
        if (key.isEmpty()) return situation.questions

        val provider = CloudCatalog.provider(providerId) ?: return situation.questions
        val modelInfo = CloudCatalog.model(model, providerId)
        val client = OpenAICompatibleModelClient(
            profile = AgentModelProfile(
                providerId = providerId,
                modelId = model,
                contextWindow = modelInfo?.contextWindow,
                maxOutputTokens = 200,
            ),
            apiKey = key,
            baseUrl = provider.apiBaseUrl,
            wireProtocol = provider.wireProtocol,
            thinkingEnabled = false,
            supportsReasoning = modelInfo?.supportsReasoning == true,
        )
        val prompt = "${situation.brief}\n\n最近数据：\n${healthTools.digestForSuggestions()}"
        val request = AgentModelRequest(
            profile = client.profile,
            prompt = AgentTranscript(
                messages = listOf(
                    AgentTranscript.Message.system(INSTRUCTIONS),
                    AgentTranscript.Message.user(prompt),
                ),
            ),
            capabilities = emptyList(),
        )
        var text = ""
        client.stream(request).collect { event ->
            if (event is AgentModelStreamEvent.TextDelta) text += event.text
        }
        val questions = ModelLines.parse(text, minCharacters = 4, maxCharacters = 14, limit = 3)
        // 少于三条说明模型没照格式写,与其拼一半不如整体退回本地判定出来的那几条。
        return if (questions.size == 3) questions else situation.questions
    }

    companion object {
        private val INSTRUCTIONS = """
            你在为一个健康分析 app 写首屏的问题建议，这些问题会直接显示成三个可点的按钮。
            用户通常是刚练完、觉得没睡好、感觉不太舒服，或者想知道自己最近怎么样，才会打开它。

            要求：
            - 只输出三行，每行一个问题，不要编号、不要引号、不要任何解释。
            - 中文，口语，每行不超过 14 个字，必须是用户会对自己健康数据提的问题。
            - 必须能用步数、睡眠、静息心率与 HRV、锻炼、体重体脂这几类数据回答。
            - 「从数据里读到的情况」按重要性排好了，第一条最该被问到；三个问题不要都问同一件事。
            - 给了「他平时的关注点」的话，在同样重要的几件事里优先问他关心的那一类；\
            但数据里刚发生的事更要紧，别为了迁就习惯把它挤掉。
            - 用第一人称的口气，像用户自己在问，不是像 app 在提示。
            - 不做诊断，也不要写成建议或结论，只写问题。
        """.trimIndent()
    }
}
