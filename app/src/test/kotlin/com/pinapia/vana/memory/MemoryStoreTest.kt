package com.pinapia.vana.memory

import java.io.File
import kotlinx.datetime.Clock
import kotlinx.datetime.plus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MemoryStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun store() = MemoryStore(directory = folder.root)
    private val file get() = File(folder.root, "memory.json")

    @Test
    fun rememberThenLoadRoundTrips() {
        val store = store()
        store.remember("他上夜班，白天补觉", MemoryItem.Kind.PROFILE)
        val items = store.load()
        assertEquals(1, items.size)
        assertEquals("他上夜班，白天补觉", items.single().text)
        assertEquals(MemoryItem.Origin.ASKED, items.single().origin)
    }

    @Test
    fun deleteRemovesOnlyThatItem() {
        val store = store()
        val keep = store.remember("保留", MemoryItem.Kind.PREFERENCE)!!
        val drop = store.remember("删掉", MemoryItem.Kind.PROFILE)!!
        store.delete(drop.id)
        assertEquals(listOf(keep.id), store.load().map { it.id })
    }

    @Test
    fun unknownKindIsNotWipedByALaterSave() {
        // 更新版本写下的新 Kind(这里用一个永远不会存在的名字):本版本读不懂,但保存别的条目时不能把它丢掉。
        file.writeText(
            """{"items":[
              {"id":"a","text":"我读得懂","kind":"profile","origin":"asked","createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-01T00:00:00Z"},
              {"id":"b","text":"我读不懂","kind":"hobby","origin":"extracted","createdAt":"2026-01-02T00:00:00Z","updatedAt":"2026-01-02T00:00:00Z"}
            ]}""",
        )
        val store = store()
        assertEquals(listOf("a"), store.load().map { it.id })

        store.remember("新增一条", MemoryItem.Kind.PREFERENCE)

        val raw = file.readText()
        assertTrue("读不懂的条目应该原样留在文件里", raw.contains("\"hobby\""))
        assertEquals(2, store.load().size)
    }

    @Test
    fun unreadableFileIsBackedUpBeforeItCanBeOverwritten() {
        file.writeText("{ 这不是 JSON")
        val store = store()
        assertTrue(store.load().isEmpty())

        store.remember("之后写进来的", MemoryItem.Kind.PROFILE)

        val backup = File(folder.root, "memory.json.bak")
        assertTrue("坏文件应该先备份", backup.exists())
        assertEquals("{ 这不是 JSON", backup.readText())
        assertEquals(1, store.load().size)
    }

    @Test
    fun backupKeepsTheFirstUnreadableCopy() {
        file.writeText("first-bad")
        val store = store()
        store.load()
        file.writeText("second-bad")
        store.load()
        assertEquals("first-bad", File(folder.root, "memory.json.bak").readText())
    }

    @Test
    fun removeAllAlsoDropsItemsThisBuildCannotRead() {
        file.writeText(
            """{"items":[{"id":"b","text":"x","kind":"hobby","origin":"extracted","createdAt":"2026-01-02T00:00:00Z","updatedAt":"2026-01-02T00:00:00Z"}]}""",
        )
        val store = store()
        store.removeAll()
        assertFalse(file.readText().contains("hobby"))
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun saveLeavesNoTemporaryFileBehind() {
        val store = store()
        store.remember("一条", MemoryItem.Kind.PROFILE)
        assertFalse(File(folder.root, "memory.json.tmp").exists())
    }

    @Test
    fun evictionNeverDropsPinnedItems() {
        val store = store()
        val now = Clock.System.now()
        val pinned = (1..MemorySnapshot.MAX_ITEMS).map { i ->
            MemoryItem(text = "pinned-$i", kind = MemoryItem.Kind.PROFILE, origin = MemoryItem.Origin.MANUAL)
        }
        store.save(pinned, now)
        store.apply(listOf(MemoryOperation.Add(MemoryItem.Kind.PROFILE, "extracted")), now)
        val texts = store.load(now).map { it.text }
        assertTrue(texts.containsAll(pinned.map { it.text }))
        assertFalse("抽取的应该先被挤掉", texts.contains("extracted"))
    }

    @Test
    fun extractionCannotUpdateOrDeleteWhatTheUserWrote() {
        val store = store()
        val mine = store.remember("我自己写的", MemoryItem.Kind.PROFILE, origin = MemoryItem.Origin.MANUAL)!!
        store.apply(
            listOf(
                MemoryOperation.Update(mine.id, "被改写"),
                MemoryOperation.Delete(mine.id),
            ),
        )
        assertEquals("我自己写的", store.load().single().text)
    }

    @Test
    fun extractionSkipsExactDuplicates() {
        val store = store()
        store.apply(listOf(MemoryOperation.Add(MemoryItem.Kind.PREFERENCE, "喜欢简短")))
        store.apply(listOf(MemoryOperation.Add(MemoryItem.Kind.PREFERENCE, "喜欢简短")))
        assertEquals(1, store.load().size)
    }

    @Test
    fun concurrentWritersDoNotLoseEachOthersItems() {
        val store = store()
        val threads = (1..8).map { t ->
            Thread {
                repeat(3) { n ->
                    store.remember("t$t-n$n", MemoryItem.Kind.PROFILE, origin = MemoryItem.Origin.MANUAL)
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(24, store.load().size)
    }

    // ---- 近况 ----

    @Test
    fun anEpisodeDefaultsToFourteenDaysAndVanishesAfterwardsWithoutGrace() {
        val store = store()
        val now = Clock.System.now()
        val item = store.remember("下周三面试", MemoryItem.Kind.EPISODE, now = now)!!
        assertTrue(item.dueAt != null)
        assertEquals(1, store.load(now).size)
        assertTrue("过了十四天就没了，不留宽限期", store.load(now + kotlin.time.Duration.parse("15d")).isEmpty())
    }

    @Test
    fun aFollowUpIsStillReadableInItsGracePeriodButAnEpisodeIsNot() {
        val store = store()
        val now = Clock.System.now()
        store.remember("回头看维 D", MemoryItem.Kind.FOLLOW_UP, days = 1, now = now)
        store.remember("最近在装修", MemoryItem.Kind.EPISODE, days = 1, now = now)
        val later = now + kotlin.time.Duration.parse("2d")
        assertEquals(listOf(MemoryItem.Kind.FOLLOW_UP), store.load(later).map { it.kind })
    }

    @Test
    fun theEpisodeCapFadesTheOldestUnprotectedOnesFirst() {
        val store = store()
        val now = Clock.System.now()
        val ops = (1..12).map { i -> MemoryOperation.Add(MemoryItem.Kind.EPISODE, "近况$i", expiresInDays = 14) }
        // 逐条加,updatedAt 递增,最旧的应当先被淡掉。
        ops.forEachIndexed { i, op -> store.apply(listOf(op), now + kotlin.time.Duration.parse("${i}s")) }
        val texts = store.load(now).map { it.text }
        assertEquals(MemoryItem.MAX_EPISODES, texts.size)
        assertFalse(texts.contains("近况1"))
        assertFalse(texts.contains("近况2"))
        assertTrue(texts.contains("近况12"))
    }

    @Test
    fun episodesTheUserWroteThemselvesAreNeverFadedByTheCap() {
        val store = store()
        val now = Clock.System.now()
        repeat(MemoryItem.MAX_EPISODES + 2) { i ->
            store.remember("我写的近况$i", MemoryItem.Kind.EPISODE, origin = MemoryItem.Origin.MANUAL, now = now)
        }
        assertEquals(MemoryItem.MAX_EPISODES + 2, store.load(now).count { it.kind == MemoryItem.Kind.EPISODE })
    }

    @Test
    fun extractionTreatsTheSameSentenceWithDifferentPunctuationAsADuplicate() {
        val store = store()
        store.apply(listOf(MemoryOperation.Add(MemoryItem.Kind.PREFERENCE, "不吃香菜。")))
        store.apply(listOf(MemoryOperation.Add(MemoryItem.Kind.PREFERENCE, "不吃 香菜")))
        assertEquals(1, store.load().size)
    }

    @Test
    fun theSameSentenceUnderAnotherKindIsNotADuplicate() {
        val store = store()
        store.apply(listOf(MemoryOperation.Add(MemoryItem.Kind.PROFILE, "在备考")))
        store.apply(listOf(MemoryOperation.Add(MemoryItem.Kind.EPISODE, "在备考", expiresInDays = 7)))
        assertEquals(2, store.load().size)
    }

    @Test
    fun extractionAddsAnEpisodeWithItsOwnExpiry() {
        val store = store()
        val now = Clock.System.now()
        store.apply(listOf(MemoryOperation.Add(MemoryItem.Kind.EPISODE, "下周三面试", expiresInDays = 7)), now)
        val stored = store.load(now).single()
        assertEquals(MemoryItem.Origin.EXTRACTED, stored.origin)
        assertEquals(7, (stored.dueAt!! - now).inWholeDays)
    }
}
