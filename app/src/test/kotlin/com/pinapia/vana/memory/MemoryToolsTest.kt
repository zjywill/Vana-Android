package com.pinapia.vana.memory

import com.pinapia.vana.agentruntime.CapabilityExecutionResult
import com.pinapia.vana.agentruntime.CapabilityInvocation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MemoryToolsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val store by lazy { MemoryStore(folder.root) }

    private fun call(
        name: String,
        input: String,
        snapshot: MemorySnapshot = store.snapshot(),
    ): CapabilityExecutionResult = runBlocking {
        MemoryTools.registry(store = store, snapshot = snapshot)
            .execute(CapabilityInvocation(toolCallId = "t", name = name, input = input))
    }

    private fun seed(text: String, kind: MemoryItem.Kind = MemoryItem.Kind.PROFILE, origin: MemoryItem.Origin = MemoryItem.Origin.ASKED) =
        store.remember(text, kind, origin = origin)!!

    // ---- remember ----

    @Test
    fun rememberStoresAnEpisodeWithItsFadeOutDelay() {
        val result = call("remember", """{"text":"下周三面试","kind":"episode","days":7}""")
        assertFalse(result.output.text, result.isError)
        assertTrue(result.output.text, result.output.text.contains("7 天后淡出"))
        val stored = store.load().single()
        assertEquals(MemoryItem.Kind.EPISODE, stored.kind)
        assertTrue(stored.dueAt != null)
    }

    @Test
    fun rememberAFollowUpStillSaysWhenItComesBack() {
        val result = call("remember", """{"text":"看看维 D 有没有效果","kind":"followUp","days":14}""")
        assertTrue(result.output.text, result.output.text.contains("14 天后回头看"))
    }

    @Test
    fun aLongSentenceIsRefusedWithAHintToCompressIt() {
        val result = call("remember", """{"text":"${"很长".repeat(80)}","kind":"profile"}""")
        assertTrue(result.isError)
        assertTrue(result.output.text.contains("压成一句话"))
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun rememberNeedsATextAndAKnownKind() {
        assertTrue(call("remember", """{"text":"","kind":"profile"}""").isError)
        assertTrue(call("remember", """{"text":"x","kind":"nonsense"}""").isError)
    }

    // ---- forget ----

    @Test
    fun forgetRemovesTheItemTheHandlePointsAt() {
        seed("他不吃香菜", MemoryItem.Kind.PREFERENCE)
        val keep = seed("他上夜班")
        val snapshot = store.snapshot()
        val target = snapshot.items.first { it.text == "他不吃香菜" }
        val handle = MemorySnapshot.handle(snapshot.items.indexOf(target))

        val result = call("forget_memory", """{"handle":"$handle"}""", snapshot)

        assertFalse(result.output.text, result.isError)
        assertEquals("已忘掉：他不吃香菜", result.output.text)
        assertEquals(listOf(keep.id), store.load().map { it.id })
    }

    @Test
    fun forgetWorksOnWhatTheUserWroteThemselvesBecauseTheyAskedForIt() {
        seed("我自己写的", origin = MemoryItem.Origin.MANUAL)
        val result = call("forget_memory", """{"handle":"M1"}""")
        assertFalse(result.isError)
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun forgetResolvesAgainstTheSnapshotTheModelSawNotTheCurrentDiskOrder() {
        val first = seed("第一条")
        val snapshot = store.snapshot() // 模型此刻读到的:M1 = 第一条
        // 之后盘上多了一条排在前面的(同一种类,创建更早),盘上的顺序变了。
        store.save(
            store.load() + MemoryItem(text = "后来插进来的", kind = MemoryItem.Kind.PROFILE, createdAt = first.createdAt - kotlin.time.Duration.parse("1d")),
        )
        assertEquals("后来插进来的", store.load().first().text)

        val result = call("forget_memory", """{"handle":"M1"}""", snapshot)

        assertEquals("已忘掉：第一条", result.output.text)
        assertEquals(listOf("后来插进来的"), store.load().map { it.text })
    }

    @Test
    fun anUnknownHandleIsAnErrorThatPointsBackAtTheBlock() {
        seed("一条")
        val result = call("forget_memory", """{"handle":"M9"}""")
        assertTrue(result.isError)
        assertTrue(result.output.text.contains("没有编号为 M9"))
        assertEquals(1, store.load().size)
    }

    @Test
    fun forgettingSomethingAlreadyGoneSaysSo() {
        val item = seed("一条")
        val snapshot = store.snapshot()
        store.delete(item.id)
        val result = call("forget_memory", """{"handle":"M1"}""", snapshot)
        assertTrue(result.isError)
        assertTrue(result.output.text.contains("已经不在了"))
    }

    // ---- revise ----

    @Test
    fun reviseRewritesTheItemAndKeepsItsKind() {
        seed("他周三开会", MemoryItem.Kind.EPISODE)
        val result = call("revise_memory", """{"handle":"M1","text":"他周四开会"}""")
        assertFalse(result.output.text, result.isError)
        val stored = store.load().single()
        assertEquals("他周四开会", stored.text)
        assertEquals(MemoryItem.Kind.EPISODE, stored.kind)
    }

    @Test
    fun aCorrectedExtractedItemBecomesTheUsersOwnAndIsNoLongerTouchedByExtraction() {
        seed("他喜欢咖啡", MemoryItem.Kind.PREFERENCE, origin = MemoryItem.Origin.EXTRACTED)
        call("revise_memory", """{"handle":"M1","text":"他不喝咖啡"}""")

        val stored = store.load().single()
        assertEquals(MemoryItem.Origin.ASKED, stored.origin)
        assertTrue(stored.pinned)

        store.apply(listOf(MemoryOperation.Update(stored.id, "被后台改回去"), MemoryOperation.Delete(stored.id)))
        assertEquals("他不喝咖啡", store.load().single().text)
    }

    @Test
    fun reviseNeedsTheNewTextAndRefusesALongOne() {
        seed("一条")
        assertTrue(call("revise_memory", """{"handle":"M1","text":""}""").isError)
        assertTrue(call("revise_memory", """{"handle":"M1","text":"${"很长".repeat(80)}"}""").isError)
        assertEquals("一条", store.load().single().text)
    }

    // ---- 描述与说明 ----

    @Test
    fun theGuideMentionsAllThreeToolsAndOneOffRequests() {
        val guide = MemoryTools.guide()
        listOf("remember", "forget_memory", "revise_memory", "一次性的要求").forEach {
            assertTrue("说明里少了 $it", guide.contains(it))
        }
    }

    @Test
    fun theRegistryOffersRememberForgetAndRevise() {
        val names = MemoryTools.registry(store).definitions.map { it.name }
        assertEquals(listOf("remember", "forget_memory", "revise_memory"), names)
    }
}
