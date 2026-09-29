package com.pinapia.vana.agent

import com.pinapia.vana.agentruntime.AgentModelProfile
import com.pinapia.vana.agentruntime.AgentModelRequest
import com.pinapia.vana.agentruntime.AgentTranscript
import com.pinapia.vana.agentruntime.CapabilityDefinition
import com.pinapia.vana.agentruntime.RuntimeJSONValue
import com.pinapia.vana.settings.CloudCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelClientEstimateTest {
    private val profile = AgentModelProfile(providerId = "p", modelId = "m", contextWindow = 128_000)

    private fun client(wire: CloudCatalog.WireProtocol = CloudCatalog.WireProtocol.OPENAI) = OpenAICompatibleModelClient(
        profile = profile,
        apiKey = "k",
        baseUrl = "https://example.invalid",
        wireProtocol = wire,
    )

    private fun request(messages: List<AgentTranscript.Message>, capabilities: List<CapabilityDefinition> = emptyList()) =
        AgentModelRequest(profile = profile, prompt = AgentTranscript(messages), capabilities = capabilities)

    @Test
    fun chineseIsCountedAboutOneTokenPerCharacterNotAQuarterOfThat() {
        val estimate = client().estimateTokens(request(listOf(AgentTranscript.Message.user("字".repeat(400)))))
        assertEquals(400, estimate)
    }

    @Test
    fun theToolSurfaceIncludesTheSchemaThatIsSent() {
        val schema = RuntimeJSONValue.obj(
            mapOf(
                "type" to RuntimeJSONValue.string("object"),
                "properties" to RuntimeJSONValue.obj(
                    mapOf("query" to RuntimeJSONValue.obj(mapOf("type" to RuntimeJSONValue.string("string"), "description" to RuntimeJSONValue.string("要搜的内容")))),
                ),
            ),
        )
        val bare = CapabilityDefinition(name = "web_search", description = "搜索网页", inputSchema = RuntimeJSONValue.obj(emptyMap()))
        val full = bare.copy(inputSchema = schema)
        val none = client().estimateTokens(request(emptyList()))
        assertTrue(client().estimateTokens(request(emptyList(), listOf(full))) > client().estimateTokens(request(emptyList(), listOf(bare))))
        assertEquals("空请求也不为零,规划器拿它当工具面的常量去减", 1, none)
    }

    private val thoughtful = AgentTranscript.Message(
        role = AgentTranscript.Role.ASSISTANT,
        parts = listOf(AgentTranscript.Part.Reasoning("想".repeat(300)), AgentTranscript.Part.Text("答")),
    )

    @Test
    fun protocolsThatReplayReasoningCountItAndAnthropicDoesNot() {
        val withThought = request(listOf(AgentTranscript.Message.user("问"), thoughtful))
        val openai = client(CloudCatalog.WireProtocol.OPENAI).estimateTokens(withThought)
        val gemini = client(CloudCatalog.WireProtocol.GEMINI).estimateTokens(withThought)
        val anthropic = client(CloudCatalog.WireProtocol.ANTHROPIC).estimateTokens(withThought)
        assertEquals(openai, gemini)
        assertEquals("思考 300 个字不会被 Anthropic 发出去", openai - 300, anthropic)
    }

    @Test
    fun replaysReasoningMatchesWhatEachWireBodyActuallySends() {
        assertTrue(OpenAICompatibleModelClient.replaysReasoning(CloudCatalog.WireProtocol.OPENAI))
        assertTrue(OpenAICompatibleModelClient.replaysReasoning(CloudCatalog.WireProtocol.GEMINI))
        assertFalse(OpenAICompatibleModelClient.replaysReasoning(CloudCatalog.WireProtocol.ANTHROPIC))
        assertTrue("不知道协议时按会回放算,宁可高估", OpenAICompatibleModelClient.replaysReasoning(null))
    }
}
