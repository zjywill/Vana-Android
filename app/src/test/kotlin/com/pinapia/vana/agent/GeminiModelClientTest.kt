package com.pinapia.vana.agent

import com.pinapia.vana.agentruntime.AgentModelProfile
import com.pinapia.vana.agentruntime.AgentModelRequest
import com.pinapia.vana.agentruntime.AgentTranscript
import com.pinapia.vana.agentruntime.CapabilityDefinition
import com.pinapia.vana.agentruntime.RuntimeJSONValue
import com.pinapia.vana.settings.CloudCatalog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiModelClientTest {
    private val profile = AgentModelProfile(
        providerId = "google",
        modelId = "gemini-3.5-flash",
        contextWindow = 1_000_000,
        maxOutputTokens = 64_000,
    )

    private fun client(thinkingEnabled: Boolean = true) = OpenAICompatibleModelClient(
        profile = profile,
        apiKey = "google-key",
        baseUrl = "https://generativelanguage.googleapis.com",
        wireProtocol = CloudCatalog.WireProtocol.GEMINI,
        thinkingEnabled = thinkingEnabled,
        supportsReasoning = true,
    )

    @Test
    fun geminiRequestUsesContentsToolsAndFunctionResponses() {
        val request = AgentModelRequest(
            profile = profile,
            prompt = AgentTranscript(
                messages = listOf(
                    AgentTranscript.Message.system("You are concise."),
                    AgentTranscript.Message.user("How am I doing?"),
                    AgentTranscript.Message(
                        role = AgentTranscript.Role.ASSISTANT,
                        parts = listOf(
                            AgentTranscript.Part.ToolCallPart(
                                AgentTranscript.ToolCall(
                                    toolCallId = "read_steps-1",
                                    toolName = "read_steps",
                                    input = """{"days":7}""",
                                    metadata = mapOf(
                                        "google" to mapOf(
                                            "thoughtSignature" to RuntimeJSONValue.string("signed"),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                    AgentTranscript.Message.toolResult(
                        toolCallId = "read_steps-1",
                        toolName = "read_steps",
                        result = RuntimeJSONValue.int(1234),
                    ),
                ),
            ),
            capabilities = listOf(
                CapabilityDefinition(
                    name = "read_steps",
                    description = "Read step totals",
                    inputSchema = RuntimeJSONValue.obj(
                        mapOf(
                            "type" to RuntimeJSONValue.string("object"),
                            "properties" to RuntimeJSONValue.obj(
                                mapOf(
                                    "entries" to RuntimeJSONValue.obj(
                                        mapOf(
                                            "type" to RuntimeJSONValue.string("array"),
                                            "items" to RuntimeJSONValue.obj(
                                                mapOf(
                                                    "type" to RuntimeJSONValue.string("object"),
                                                    "properties" to RuntimeJSONValue.obj(
                                                        mapOf(
                                                            "days" to RuntimeJSONValue.obj(
                                                                mapOf(
                                                                    "type" to RuntimeJSONValue.string("integer"),
                                                                ),
                                                            ),
                                                        ),
                                                    ),
                                                    "additionalProperties" to RuntimeJSONValue.bool(false),
                                                ),
                                            ),
                                        ),
                                    ),
                                ),
                            ),
                            "additionalProperties" to RuntimeJSONValue.bool(false),
                        ),
                    ),
                ),
            ),
        )

        val root = Json.parseToJsonElement(client().geminiBody(request)).jsonObject
        assertEquals(
            "You are concise.",
            root["systemInstruction"]!!.jsonObject["parts"]!!
                .jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content,
        )
        val declaration = root["tools"]!!.jsonArray.single().jsonObject["functionDeclarations"]!!
            .jsonArray.single().jsonObject
        assertEquals("read_steps", declaration["name"]!!.jsonPrimitive.content)
        assertTrue("parameters" !in declaration)
        val schema = declaration["parametersJsonSchema"]!!.jsonObject
        assertEquals("false", schema["additionalProperties"]!!.jsonPrimitive.content)
        assertEquals(
            "false",
            schema["properties"]!!.jsonObject["entries"]!!.jsonObject["items"]!!
                .jsonObject["additionalProperties"]!!.jsonPrimitive.content,
        )
        val contents = root["contents"]!!.jsonArray
        assertEquals("model", contents[1].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals(
            "signed",
            contents[1].jsonObject["parts"]!!.jsonArray.single()
                .jsonObject["thoughtSignature"]!!.jsonPrimitive.content,
        )
        assertEquals(
            1234,
            contents[2].jsonObject["parts"]!!.jsonArray.single()
                .jsonObject["functionResponse"]!!.jsonObject["response"]!!
                .jsonObject["result"]!!.jsonPrimitive.content.toInt(),
        )
        assertEquals(
            "high",
            root["generationConfig"]!!.jsonObject["thinkingConfig"]!!
                .jsonObject["thinkingLevel"]!!.jsonPrimitive.content,
        )
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:streamGenerateContent?alt=sse",
            OpenAICompatibleModelClient.geminiEndpoint(
                "https://generativelanguage.googleapis.com",
                "gemini-3.5-flash",
            ),
        )
    }

    @Test
    fun geminiChunkPreservesThoughtToolSignatureAndUsage() {
        val chunk = client().parseGeminiChunk(
            """
            {
              "modelVersion": "gemini-3.5-flash-001",
              "candidates": [{
                "content": {"parts": [
                  {"text": "checking", "thought": true},
                  {"functionCall": {"name": "read_steps", "args": {"days": 7}}, "thoughtSignature": "signed"},
                  {"text": "done"}
                ]},
                "finishReason": "STOP"
              }],
              "usageMetadata": {
                "promptTokenCount": 100,
                "candidatesTokenCount": 20,
                "thoughtsTokenCount": 30
              }
            }
            """.trimIndent(),
        )

        assertEquals(listOf("checking"), chunk.reasoningDeltas)
        assertEquals(listOf("done"), chunk.textDeltas)
        assertEquals("read_steps", chunk.toolCalls.single().name)
        assertEquals("""{"days":7}""", chunk.toolCalls.single().arguments)
        assertEquals(
            "signed",
            chunk.toolCalls.single().metadata["google"]
                ?.get("thoughtSignature")?.stringValue,
        )
        assertEquals(100, chunk.usage?.inputTokens?.total)
        assertEquals(50, chunk.usage?.outputTokens?.total)
        assertEquals("STOP", chunk.finishReason)
        assertEquals("gemini-3.5-flash-001", chunk.modelVersion)
        assertTrue(chunk.failure == null)
    }
}
