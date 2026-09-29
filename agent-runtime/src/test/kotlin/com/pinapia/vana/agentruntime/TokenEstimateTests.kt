package com.pinapia.vana.agentruntime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenEstimateTests {
    @Test
    fun cjkCountsAboutOneTokenPerCharacterAndOtherTextAboutAQuarter() {
        assertEquals(10, TokenEstimate.text("一二三四五六七八九十"))
        assertEquals(3, TokenEstimate.text("hello world!")) // 12 个非 CJK 字符 → 3
        assertEquals(0, TokenEstimate.text(""))
    }

    @Test
    fun chineseIsNotEstimatedAtAQuarterOfItsRealSize() {
        // 以前规划器一律按 chars/4:四百个汉字只估一百。
        assertEquals(400, TokenEstimate.text("字".repeat(400)))
    }

    @Test
    fun aDefinitionCountsTheSchemaThatIsActuallySent() {
        val schema = RuntimeJSONValue.obj(
            mapOf(
                "type" to RuntimeJSONValue.string("object"),
                "properties" to RuntimeJSONValue.obj(
                    mapOf("query" to RuntimeJSONValue.obj(mapOf("type" to RuntimeJSONValue.string("string")))),
                ),
            ),
        )
        val bare = CapabilityDefinition(name = "search", description = "搜索", inputSchema = RuntimeJSONValue.obj(emptyMap()))
        val withSchema = bare.copy(inputSchema = schema)
        assertTrue("参数说明也占位子", TokenEstimate.definition(withSchema) > TokenEstimate.definition(bare))
        // 按发出去的 JSON 算,不是 Kotlin 的 toString():后者带着 ObjectValue(value=… 这类外壳,长得多。
        assertTrue(schema.encodedString().length < schema.toString().length)
        assertEquals(
            TokenEstimate.text("search") + TokenEstimate.text("搜索") + TokenEstimate.text(schema.encodedString()),
            TokenEstimate.definition(withSchema),
        )
    }

    @Test
    fun aToolResultIsCountedTheWayItIsSentNotWrappedInJsonQuotes() {
        val text = "结果".repeat(50)
        val part = AgentTranscript.Part.ToolResultPart(
            AgentTranscript.ToolResult(
                toolCallId = "1",
                toolName = "t",
                result = RuntimeJSONValue.string(text),
            ),
        )
        assertEquals(TokenEstimate.text(text), TokenEstimate.part(part))
    }

    @Test
    fun aTranscriptIsTheSumOfItsParts() {
        val transcript = AgentTranscript(
            messages = listOf(
                AgentTranscript.Message.user("你好"),
                AgentTranscript.Message(
                    role = AgentTranscript.Role.ASSISTANT,
                    parts = listOf(
                        AgentTranscript.Part.Reasoning("想一想"),
                        AgentTranscript.Part.Text("你好呀"),
                    ),
                ),
            ),
        )
        assertEquals(2 + 3 + 3, TokenEstimate.transcript(transcript))
    }
}
