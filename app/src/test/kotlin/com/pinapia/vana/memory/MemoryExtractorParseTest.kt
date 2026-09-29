package com.pinapia.vana.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryExtractorParseTest {
    private val snapshot = MemorySnapshot(
        listOf(
            MemoryItem(text = "他上夜班", kind = MemoryItem.Kind.PROFILE),
            MemoryItem(text = "喜欢简短", kind = MemoryItem.Kind.PREFERENCE),
        ),
    )

    private fun parse(json: String) = MemoryExtractor.parse(json, snapshot)

    @Test
    fun anEpisodeCarriesItsDays() {
        val ops = parse("""{"operations":[{"op":"add","kind":"episode","text":"下周三面试","days":10}]}""")
        val add = ops.single() as MemoryOperation.Add
        assertEquals(MemoryItem.Kind.EPISODE, add.kind)
        assertEquals(10, add.expiresInDays)
    }

    @Test
    fun anEpisodeOrFollowUpWithoutDaysGetsTheDefault() {
        val ops = parse(
            """{"operations":[
              {"op":"add","kind":"episode","text":"最近在装修"},
              {"op":"add","kind":"followUp","text":"看看维 D"}
            ]}""",
        )
        assertEquals(listOf(14, 14), ops.map { (it as MemoryOperation.Add).expiresInDays })
    }

    @Test
    fun kindsWithoutExpiryIgnoreDays() {
        val add = parse("""{"operations":[{"op":"add","kind":"profile","text":"他上夜班","days":30}]}""").single() as MemoryOperation.Add
        assertEquals(null, add.expiresInDays)
    }

    @Test
    fun aTooLongOrEmptyTextAndAnUnknownKindAreDropped() {
        val long = "字".repeat(MemoryItem.MAX_TEXT_CHARS + 1)
        val ops = parse(
            """{"operations":[
              {"op":"add","kind":"profile","text":"$long"},
              {"op":"add","kind":"profile","text":"  "},
              {"op":"add","kind":"hobby","text":"喜欢钓鱼"},
              {"op":"add","kind":"profile","text":"他住在上海"}
            ]}""",
        )
        assertEquals(1, ops.size)
        assertEquals("他住在上海", (ops.single() as MemoryOperation.Add).text)
    }

    @Test
    fun updateAndDeleteResolveShortHandlesAgainstTheSnapshot() {
        val ops = parse(
            """{"operations":[
              {"op":"update","id":"M2","text":"喜欢详细"},
              {"op":"delete","id":"M1"},
              {"op":"delete","id":"M9"}
            ]}""",
        )
        assertEquals(2, ops.size)
        assertEquals(snapshot.items[1].id, (ops[0] as MemoryOperation.Update).id)
        assertEquals(snapshot.items[0].id, (ops[1] as MemoryOperation.Delete).id)
    }

    @Test
    fun proseAroundTheJsonIsIgnoredAndGarbageYieldsNothing() {
        val ops = parse("好的，这是结果：\n" + """{"operations":[{"op":"add","kind":"profile","text":"他住在上海"}]}""" + "\n以上。")
        assertEquals(1, ops.size)
        assertTrue(parse("我觉得没什么值得记的").isEmpty())
        assertTrue(parse("{ 坏掉的").isEmpty())
    }
}
