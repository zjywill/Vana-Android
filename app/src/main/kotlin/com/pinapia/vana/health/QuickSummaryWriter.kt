package com.pinapia.vana.health

import com.pinapia.vana.agentruntime.AgentModelProfile
import com.pinapia.vana.agentruntime.AgentModelRequest
import com.pinapia.vana.agentruntime.AgentModelStreamEvent
import com.pinapia.vana.agentruntime.AgentTranscript
import com.pinapia.vana.agent.OpenAICompatibleModelClient
import com.pinapia.vana.settings.CloudCatalog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * 首屏那段话。
 *
 * 本地已经算出了「现在是多少」和「有什么变了」,这一步只把它们写成人话。
 * 失败即放弃——调用方手里已经有一句能显示的话了。thinking OFF, cheap call。
 */
class QuickSummaryWriter(
    private val providerId: String,
    private val model: String,
    private val apiKey: String,
    private val situation: HealthSituation,
) {
    /** 一边写一边往外送,每次给的是到此刻为止的全文。 */
    fun stream(): Flow<String> = flow {
        if (!situation.hasSummaryFacts) return@flow
        val key = apiKey.trim()
        if (key.isEmpty()) return@flow

        val provider = CloudCatalog.provider(providerId) ?: return@flow
        val modelInfo = CloudCatalog.model(model, providerId)
        val client = OpenAICompatibleModelClient(
            profile = AgentModelProfile(
                providerId = providerId,
                modelId = model,
                contextWindow = modelInfo?.contextWindow,
                maxOutputTokens = 400,
            ),
            apiKey = key,
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
                    AgentTranscript.Message.user(request(forSituation = situation)),
                ),
            ),
            capabilities = emptyList(),
        )
        var text = ""
        client.stream(request).collect { event ->
            if (event is AgentModelStreamEvent.TextDelta) {
                text += event.text
                emit(text)
            }
        }
    }

    companion object {
        const val MAX_CHARACTERS = 160

        private val INSTRUCTIONS = """
            你在为一个健康 app 的首屏写一小段话。用户刚打开它，还没开口问任何事，\
            这是他看到的第一段字。

            要求：
            - 先说清楚**他现在是什么状况**，把给出的关键数值说出来；然后才说要不要在意。
            - 两到四句中文，写成一段，不要换行，总共不超过 160 个字。
            - 只能用下面给出的事实。**不许出现事实里没有的数字**，也不要把给出的数字换算成别的说法。
            - 数据平稳就照实说平稳，不要为了有话说把常态写成异常；也不要反过来宣布「一切正常」——\
            没有给出的项目你并不知道。
            - 不要提问，不要给建议、行动方案或者安慰。
            - 口气平静，像一个刚看过数据的人随口说的第一句，不是播报。不要用「您」。
            - 不做诊断，不提疾病名。
            - 不要编号、不要引号、不要任何解释。
        """.trimIndent().replace("\\\n", "")

        fun request(forSituation: HealthSituation): String {
            var text = "现在是${forSituation.period.label}。"
            val readings = forSituation.vitals.measured.mapNotNull { it.brief }
            if (readings.isNotEmpty()) {
                text += "\n\n他现在的几个值：\n" + readings.joinToString("\n") { "- $it" }
            }
            val facts = forSituation.notableTriggers.take(3).map { "- ${it.brief}" }
            text += if (facts.isEmpty()) {
                "\n\n数据里没有读到值得特别留意的波动。"
            } else {
                "\n\n其中值得说一说的（按重要性排好，第一条最该被说到）：\n" +
                    facts.joinToString("\n")
            }
            return text
        }

        /** 流式期间显示用:只剥壳,不判长短。 */
        fun partial(text: String): String =
            text.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString("")

        /** 写完之后的校验。写超了整段作废,退回本地那句。 */
        fun parse(text: String): String? {
            val shell = "0123456789.、-–—*·「」\"“” "
            val joined = text.lineSequence()
                .map { line ->
                    line.trim().trimStart { it in shell }.trimEnd { it in shell }.trim()
                }
                .filter { it.isNotEmpty() }
                .joinToString("")
            return joined.takeIf { it.length in 8..MAX_CHARACTERS }
        }
    }
}
