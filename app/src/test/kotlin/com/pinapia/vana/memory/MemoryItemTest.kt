package com.pinapia.vana.memory

import kotlin.time.Duration.Companion.days
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryItemTest {
    private val now: Instant = Clock.System.now()

    private fun item(
        text: String,
        kind: MemoryItem.Kind = MemoryItem.Kind.PROFILE,
        dueAt: Instant? = null,
        origin: MemoryItem.Origin = MemoryItem.Origin.ASKED,
    ) = MemoryItem(text = text, kind = kind, dueAt = dueAt, origin = origin)

    // ---- 过期:近况到点就没,待跟进还留宽限期 ----

    @Test
    fun anEpisodeVanishesTheMomentItIsDueButAFollowUpStaysForItsGracePeriod() {
        val due = now - 1.days
        assertTrue(item("上周的面试", MemoryItem.Kind.EPISODE, dueAt = due).hasExpired(now))
        assertFalse(item("回头看看维 D", MemoryItem.Kind.FOLLOW_UP, dueAt = due).hasExpired(now))
        assertTrue(item("回头看看维 D", MemoryItem.Kind.FOLLOW_UP, dueAt = now - 4.days).hasExpired(now))
    }

    @Test
    fun aDueFollowUpIsStillDueDuringItsGracePeriod() {
        val followUp = item("回头看看", MemoryItem.Kind.FOLLOW_UP, dueAt = now - 1.days)
        assertTrue(followUp.isDue(now))
        assertFalse(followUp.hasExpired(now))
    }

    @Test
    fun itemsWithoutADueDateNeverExpire() {
        assertFalse(item("不吃香菜", MemoryItem.Kind.PREFERENCE).hasExpired(now))
    }

    @Test
    fun dueForClampsPerKindAndIgnoresKindsWithoutExpiry() {
        fun days(kind: MemoryItem.Kind, requested: Int?): Long =
            (MemoryItem.dueFor(kind, requested, now)!! - now).inWholeDays
        assertEquals(14, days(MemoryItem.Kind.EPISODE, null))
        assertEquals(60, days(MemoryItem.Kind.EPISODE, 999))
        assertEquals(1, days(MemoryItem.Kind.EPISODE, 0))
        assertEquals(180, days(MemoryItem.Kind.FOLLOW_UP, 999))
        assertNull(MemoryItem.dueFor(MemoryItem.Kind.PROFILE, 30, now))
        assertNull(MemoryItem.dueFor(MemoryItem.Kind.INTERPRETATION, 30, now))
    }

    @Test
    fun normalizedIgnoresWhitespacePunctuationAndCase() {
        assertEquals(MemoryItem.normalized("不吃香菜。"), MemoryItem.normalized("不吃 香菜"))
        assertEquals(MemoryItem.normalized("Likes Tea!"), MemoryItem.normalized("likes tea"))
        assertTrue(MemoryItem.normalized("不吃香菜") != MemoryItem.normalized("不吃葱"))
    }

    // ---- 记忆块 ----

    @Test
    fun theBlockLabelsDoNotDependOnTheUiLanguage() {
        val previous = java.util.Locale.getDefault()
        try {
            val items = listOf(item("他上夜班"), item("喜欢简短", MemoryItem.Kind.PREFERENCE))
            java.util.Locale.setDefault(java.util.Locale.ENGLISH)
            val english = MemorySnapshot(items).instructionBlock
            java.util.Locale.setDefault(java.util.Locale.SIMPLIFIED_CHINESE)
            val chinese = MemorySnapshot(items).instructionBlock
            assertEquals("提示词不能随界面语言变，否则缓存前缀多出一个变量", chinese, english)
        } finally {
            java.util.Locale.setDefault(previous)
        }
    }

    @Test
    fun theBlockNumbersEveryLineSoTheModelCanPointAtOne() {
        val block = MemorySnapshot(listOf(item("他上夜班"), item("喜欢简短", MemoryItem.Kind.PREFERENCE))).instructionBlock!!
        assertTrue(block, block.contains("- M1 [长期情况] 他上夜班"))
        assertTrue(block, block.contains("- M2 [表达偏好] 喜欢简短"))
    }

    @Test
    fun noLineOfTheBlockStartsWithStrayIndentation() {
        val block = MemorySnapshot(listOf(item("一"), item("二"), item("三"))).instructionBlock!!
        block.lines().forEach { assertFalse("多出来的缩进：「$it」", it.startsWith(" ")) }
        assertTrue(block.startsWith("关于这位用户（来自过往对话）："))
        assertTrue(block.lines().first { it.startsWith("- M1") }.startsWith("- M1 [长期情况] 一"))
    }

    @Test
    fun theBlockNoLongerMentionsHealthOrDiagnosis() {
        val block = MemorySnapshot(listOf(item("他上夜班"))).instructionBlock!!
        listOf("健康", "诊断").forEach { assertFalse("块里不该有「$it」", block.contains(it)) }
    }

    @Test
    fun theBlockIsCutOnLineBoundariesNeverMidLine() {
        // 40 条、每条 80 字:渲染出来远超预算,必须整行地丢,不能把最后一行切在半截。
        val long = (1..60).map { i -> item("第${i}条".padEnd(80, '字')) }
        val block = MemorySnapshot(long).instructionBlock!!
        val lines = block.lines().filter { it.startsWith("- M") }
        assertTrue(lines.isNotEmpty())
        assertTrue("应当丢掉了一部分", lines.size < MemorySnapshot.MAX_ITEMS)
        lines.forEach { line ->
            assertTrue("被截断的行：$line", long.any { line.endsWith(it.text) })
        }
        assertTrue(lines.joinToString("\n").length <= MemorySnapshot.BLOCK_BUDGET)
    }

    @Test
    fun aFullStoreOfNormalItemsFitsInTheBlockWhole() {
        val items = (1..MemorySnapshot.MAX_ITEMS).map { i -> item("第${i}条：".padEnd(50, '字')) }
        val block = MemorySnapshot(items).instructionBlock!!
        assertEquals(MemorySnapshot.MAX_ITEMS, block.lines().count { it.startsWith("- M") })
    }

    @Test
    fun aDueFollowUpIsKeptEvenWhenTheBudgetIsSpent() {
        val filler = (1..39).map { i -> item("第${i}条".padEnd(80, '字')) }
        val due = item("说好今天回头看维 D", MemoryItem.Kind.FOLLOW_UP, dueAt = now - 1.days)
        val block = MemorySnapshot(filler + due).instructionBlock!!
        assertTrue(block, block.contains("说好今天回头看维 D（说好的时间已经到了）"))
        assertTrue("编号仍是它在列表里的位置", block.contains("- M40 [待跟进]"))
    }

    @Test
    fun handlesStayTheItemsPositionEvenWhenSomeLinesWereDropped() {
        val items = (1..40).map { i -> item("第${i}条".padEnd(80, '字')) }
        val kept = MemorySnapshot(items).instructionBlock!!.lines().filter { it.startsWith("- M") }
        kept.forEach { line ->
            val number = line.substringAfter("- M").substringBefore(" ").toInt()
            assertTrue("$line 的编号该是它在列表里的位置", line.contains("第${number}条"))
        }
    }

    @Test
    fun filteringKeepsTheHandleNumberingOfWhatIsLeft() {
        val snapshot = MemorySnapshot(
            listOf(
                item("他上夜班"),
                item("某指标对他而言正常", MemoryItem.Kind.INTERPRETATION),
                item("喜欢简短", MemoryItem.Kind.PREFERENCE),
            ),
        ).filtered { it.kind != MemoryItem.Kind.INTERPRETATION }
        val block = snapshot.instructionBlock!!
        assertTrue(block.contains("- M1 [长期情况] 他上夜班"))
        assertTrue("过滤之后编号按剩下的重排，和工具读到的是同一份", block.contains("- M2 [表达偏好] 喜欢简短"))
        assertEquals(snapshot.items[1].id, snapshot.resolve("M2"))
        assertNotNull(snapshot.resolve("M2"))
    }
}
