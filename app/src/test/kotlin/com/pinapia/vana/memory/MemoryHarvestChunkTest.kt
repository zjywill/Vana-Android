package com.pinapia.vana.memory

import com.pinapia.vana.session.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryHarvestChunkTest {
    private fun user(text: String) = ChatMessage(role = ChatMessage.Role.USER, text = text)
    private fun assistant(text: String) = ChatMessage(role = ChatMessage.Role.ASSISTANT, text = text)

    @Test
    fun countsOnlyRealUserMessages() {
        val messages = listOf(user("一"), assistant("答"), user("  "), user("二").copy(textIsPlaceholder = true), user("三"))
        assertEquals(2, MemoryHarvest.userMessageCount(messages))
    }

    @Test
    fun aSmallBacklogFitsInOneChunk() {
        val messages = (1..6).map { if (it % 2 == 1) user("问$it") else assistant("答$it") }
        assertEquals(messages, MemoryHarvest.chunk(messages))
    }

    @Test
    fun aBigBacklogIsCutFromTheOldestSideAndTheRestWaitsForTheNextRun() {
        val messages = (1..60).map { user("第${it}条：" + "字".repeat(300)) }
        val chunk = MemoryHarvest.chunk(messages)

        assertTrue(chunk.size in 1 until messages.size)
        assertEquals("从最旧的开始取，不是取末尾", messages.take(chunk.size), chunk)
        val transcriptLength = MemoryExtractor.transcript(chunk).length
        assertTrue("转写不该超预算：$transcriptLength", transcriptLength <= MemoryHarvest.MAX_TRANSCRIPT_CHARACTERS + chunk.size * 10)
    }

    @Test
    fun aHugeMessageCostsNoMoreThanItsClippedLengthSoItNeverBlocksTheWatermark() {
        // 单条在转写里最多按 400 字算,再长的消息也占不满一块——水位线不会卡在它身上。
        val messages = listOf(user("巨".repeat(50_000)), user("后面的"))
        assertEquals(2, MemoryHarvest.chunk(messages).size)
        assertTrue(MemoryHarvest.cost(messages[0])!! <= MemoryHarvest.MAX_MESSAGE_CHARACTERS + 5)
    }

    @Test
    fun theTranscriptCarriesOnlyWhatWasSaidNeverToolOutput() {
        val withTool = assistant("我查了一下").copy(
            toolCalls = listOf(
                com.pinapia.vana.session.ToolCallRecord(id = "1", name = "list_measurements", input = "{}", output = "体重 68.5 kg"),
            ),
        )
        val transcript = MemoryExtractor.transcript(listOf(user("我最近怎么样"), withTool))
        assertTrue(transcript.contains("用户：我最近怎么样"))
        assertTrue(transcript.contains("助手：我查了一下"))
        assertFalse("工具输出一条都不给，抽取器无从记起会过期的数字", transcript.contains("68.5"))
    }

    @Test
    fun placeholdersAndEmptyTextAreSkippedInTheTranscript() {
        val transcript = MemoryExtractor.transcript(
            listOf(user("有内容"), assistant(""), assistant("已停止回复").copy(textIsPlaceholder = true)),
        )
        assertEquals("用户：有内容", transcript)
    }

    @Test
    fun aLongMessageIsClippedInTheTranscript() {
        val transcript = MemoryExtractor.transcript(listOf(user("长".repeat(1_000))))
        assertTrue(transcript.length <= "用户：".length + MemoryHarvest.MAX_MESSAGE_CHARACTERS + 1)
        assertTrue(transcript.endsWith("…"))
    }
}
