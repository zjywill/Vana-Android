package com.pinapia.vana.thread

import com.pinapia.vana.session.ChatMessage
import com.pinapia.vana.vision.AttachmentStore
import com.pinapia.vana.vision.ChatAttachment
import java.io.File
import kotlin.time.Duration.Companion.days
import kotlinx.datetime.Clock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ThreadStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val dir get() = File(folder.root, "thread")
    private fun store(attachments: AttachmentStore? = null) = ThreadStore(dir, attachments)

    private fun user(text: String) = ChatMessage(role = ChatMessage.Role.USER, text = text)
    private fun assistant(text: String) = ChatMessage(role = ChatMessage.Role.ASSISTANT, text = text)

    private fun ThreadStore.texts() = loadTail(minMessages = 10_000, maxSegments = 10_000).messages.map { it.text }

    @Test
    fun anEmptyThreadLoadsAsAnEmptyPage() {
        val page = store().loadTail()
        assertTrue(page.messages.isEmpty())
        assertFalse(page.hasOlder)
    }

    @Test
    fun whatIsSyncedComesBackInOrderFromAFreshInstance() {
        val messages = listOf(user("一"), assistant("二"), user("三"))
        store().sync(messages, dirty = emptySet(), known = emptySet())

        assertEquals(listOf("一", "二", "三"), store().texts())
    }

    @Test
    fun theLatestPutOfAMessageWins() {
        val store = store()
        val first = assistant("回答到一半")
        var known = store.sync(listOf(user("问"), first), emptySet(), emptySet())
        val finished = first.copy(text = "完整的回答")
        known = store.sync(listOf(user("问").copy(id = store.loadTail().messages.first().id), finished), setOf(finished.id), known)

        assertEquals(listOf("问", "完整的回答"), store().texts())
    }

    @Test
    fun aMessageMissingFromTheListIsDeletedButOnlyIfTheListKnewIt() {
        val store = store()
        val a = user("要留的")
        val b = assistant("要删的")
        val known = store.sync(listOf(a, b), emptySet(), emptySet())

        // 后台追加了一条界面还不认识的
        val background = assistant("后台来的")
        store.appendAtEnd(background)

        store.sync(listOf(a), emptySet(), known)

        assertEquals(listOf("要留的", "后台来的"), store().texts())
    }

    @Test
    fun aMessageInsertedBetweenTwoPersistedOnesLandsBetweenThem() {
        // 助手还在回答时用户补了一句:补的话先落盘,回复(排在它前面)后落盘。
        val store = store()
        val first = user("第一句")
        val interjection = user("补的一句")
        var known = store.sync(listOf(first, interjection), emptySet(), emptySet())

        val reply = assistant("回复")
        known = store.sync(listOf(first, reply, interjection), emptySet(), known)

        assertEquals(listOf("第一句", "回复", "补的一句"), store().texts())
        assertEquals(3, known.size)
    }

    @Test
    fun manyInsertsInTheSameGapKeepTheirOrder() {
        val store = store()
        val head = user("头")
        val tail = user("尾")
        var known = store.sync(listOf(head, tail), emptySet(), emptySet())
        val middle = (1..12).map { assistant("中$it") }
        val list = mutableListOf(head, tail)
        // 每次都插在同一个缝里,位置一路对半,顺序必须始终对。
        middle.forEach { m ->
            list.add(list.size - 1, m)
            known = store.sync(list, emptySet(), known)
        }

        assertEquals(listOf("头") + (1..12).map { "中$it" } + "尾", store().texts())
    }

    @Test
    fun theThreadRollsToANewSegmentAndTheTailLoadsOnlyTheNewestOnes() {
        val store = store()
        val all = (1..(ThreadStore.SEGMENT_MAX_RECORDS * 2 + 50)).map { assistant("消息$it") }
        var known = emptySet<String>()
        all.chunked(100).forEachIndexed { i, _ ->
            known = store.sync(all.take((i + 1) * 100).let { if (it.size > all.size) all else it }, emptySet(), known)
        }
        store.sync(all, emptySet(), known)

        val segments = dir.listFiles()!!.count { it.name.startsWith("seg-") }
        assertTrue("应当滚出了多段：$segments", segments >= 3)

        val page = ThreadStore(dir).loadTail(minMessages = 10, maxSegments = 1)
        assertTrue(page.hasOlder)
        assertEquals("消息${all.size}", page.messages.last().text)
    }

    @Test
    fun pagingBackwardGivesTheRestWithoutDuplicatesOrResurrectingUpdatedOrDeletedOnes() {
        val store = store()
        val all = (1..(ThreadStore.SEGMENT_MAX_RECORDS + 100)).map { assistant("消息$it") }
        var known = store.sync(all, emptySet(), emptySet())

        // 第一段里的一条,在新段里被更新;另一条被删除。
        val updated = all[5].copy(text = "改过的")
        val deleted = all[6]
        val remaining = all.filterNot { it.id == deleted.id }.map { if (it.id == updated.id) updated else it }
        known = store.sync(remaining, setOf(updated.id), known)

        val fresh = ThreadStore(dir)
        val tail = fresh.loadTail(minMessages = 20, maxSegments = 1)
        val older = generateSequence(tail) { page ->
            if (page.hasOlder) fresh.loadOlder(page.oldestSegment, minMessages = 20) else null
        }.toList()
        val everything = older.flatMap { it.messages }

        assertEquals("不该有重复", everything.size, everything.map { it.id }.toSet().size)
        assertTrue(everything.any { it.text == "改过的" })
        assertFalse("被更新盖过的旧版本不该复活", everything.any { it.id == updated.id && it.text == "消息6" })
        assertFalse("被删除的不该复活", everything.any { it.id == deleted.id })
        assertEquals(all.size - 1, everything.size)
    }

    @Test
    fun aHalfWrittenLastLineIsSkippedAndDoesNotCorruptTheNextRecord() {
        val store = store()
        store.sync(listOf(user("好的一条")), emptySet(), emptySet())
        val segment = dir.listFiles()!!.first { it.name.startsWith("seg-") }
        segment.appendText("""{"p":9.0,"m":{"id":"半""")  // 进程死在这一行中间,没有换行

        val reopened = ThreadStore(dir)
        reopened.sync(listOf(user("好的一条").copy(), user("之后的一条")), emptySet(), emptySet())

        val texts = ThreadStore(dir).texts()
        assertTrue(texts.contains("好的一条"))
        assertTrue("半行不该拖坏后面的记录：$texts", texts.contains("之后的一条"))
    }

    @Test
    fun messagesAfterTheWatermarkAreTheOnesNotYetHarvested() {
        val store = store()
        val messages = (1..6).map { user("第$it") }
        store.sync(messages, emptySet(), emptySet())
        val positions = store.messagesAfter(null).map { it.first }
        assertEquals(6, positions.size)

        val after = store.messagesAfter(positions[2])
        assertEquals(listOf("第4", "第5", "第6"), after.map { it.second.text })
        assertTrue(store.messagesAfter(positions.last()).isEmpty())
    }

    @Test
    fun aBackgroundAppendGoesToTheEndAndSurvivesTheChatListSyncing() {
        val store = store()
        val a = user("问")
        var known = store.sync(listOf(a), emptySet(), emptySet())
        store.appendAtEnd(assistant("主动说的话"))
        val b = assistant("答")
        known = store.sync(listOf(a, b), emptySet(), known)

        // 界面这一份里没有那条主动消息,但它没被删,而且排在前面已有的之后。
        val texts = ThreadStore(dir).texts()
        assertTrue(texts.containsAll(listOf("问", "答", "主动说的话")))
        assertEquals(3, known.size + 1)
    }

    @Test
    fun deletingAMessageRemovesItsPhotoButKeepsOnesStillUsedElsewhere() {
        val attachments = AttachmentStore(folder.root)
        val store = store(attachments)
        val shared = attachments.store(byteArrayOf(1))
        val own = attachments.store(byteArrayOf(2))
        fun withPhoto(text: String, vararg names: String) = user(text).copy(
            attachments = names.map { ChatAttachment(imageFileName = it) },
        )
        val a = withPhoto("a", shared, own)
        val b = withPhoto("b", shared)
        store.sync(listOf(a, b), emptySet(), emptySet())

        store.delete(setOf(a.id))

        assertFalse(File(folder.root, "attachments/$own").exists())
        assertTrue("b 还引用着", File(folder.root, "attachments/$shared").exists())
        assertEquals(listOf("b"), ThreadStore(dir, attachments).texts())
    }

    @Test
    fun deleteOlderThanRemovesOnlyOldMessages() {
        val store = store()
        val old = user("很久以前").copy(createdAt = Clock.System.now() - 40.days)
        val recent = user("刚才")
        store.sync(listOf(old, recent), emptySet(), emptySet())

        val removed = store.deleteOlderThan(Clock.System.now() - 30.days)

        assertEquals(1, removed)
        assertEquals(listOf("刚才"), ThreadStore(dir).texts())
    }

    @Test
    fun deleteAllClearsTheThreadAndEveryPhoto() {
        val attachments = AttachmentStore(folder.root)
        val store = store(attachments)
        val photo = attachments.store(byteArrayOf(1))
        store.sync(listOf(user("x").copy(attachments = listOf(ChatAttachment(imageFileName = photo)))), emptySet(), emptySet())

        store.deleteAll()

        assertTrue(ThreadStore(dir).texts().isEmpty())
        assertFalse(File(folder.root, "attachments/$photo").exists())
    }

    @Test
    fun metaRoundTripsAndDefaultsWhenMissingOrBroken() {
        val store = store()
        assertEquals(null, store.meta().harvestedUpToPos)
        store.updateMeta { it.copy(harvestedUpToPos = 12.5) }
        assertEquals(12.5, ThreadStore(dir).meta().harvestedUpToPos!!, 0.0)

        File(dir, ThreadStore.META_NAME).writeText("{ 坏了")
        assertEquals(null, ThreadStore(dir).meta().harvestedUpToPos)
    }

    @Test
    fun scanVisitsEveryLiveMessageExactlyOnce() {
        val store = store()
        val messages = (1..5).map { user("第$it") }
        var known = store.sync(messages, emptySet(), emptySet())
        val updated = messages[1].copy(text = "改")
        store.sync(messages.map { if (it.id == updated.id) updated else it }.filterNot { it.id == messages[3].id }, setOf(updated.id), known)

        val seen = mutableListOf<String>()
        ThreadStore(dir).scan { _, m -> seen += m.text }

        assertEquals(setOf("第1", "改", "第3", "第5"), seen.toSet())
        assertEquals(seen.size, seen.toSet().size)
    }

    // ---- 旧会话存储:清一次,不迁移 ----

    @Test
    fun legacySessionsAndTheirPhotosAreClearedExactlyOnce() {
        File(folder.root, "sessions").mkdirs()
        File(folder.root, "sessions/old.json").writeText("{}")
        File(folder.root, "attachments").mkdirs()
        File(folder.root, "attachments/old.jpg").writeText("x")
        File(folder.root, "memory.json").writeText("""{"items":[]}""")

        assertTrue(LegacySessions.clearIfNeeded(folder.root))

        assertFalse(File(folder.root, "sessions").exists())
        assertFalse(File(folder.root, "attachments/old.jpg").exists())
        assertTrue("记忆不在清理范围", File(folder.root, "memory.json").exists())

        // 之后再出现的东西(比如新线程引用的照片)不会再被清。
        File(folder.root, "attachments").mkdirs()
        File(folder.root, "attachments/new.jpg").writeText("y")
        assertFalse(LegacySessions.clearIfNeeded(folder.root))
        assertTrue(File(folder.root, "attachments/new.jpg").exists())
    }

    @Test
    fun aFreshInstallJustWritesTheMarker() {
        assertTrue(LegacySessions.clearIfNeeded(folder.root))
        assertTrue(File(folder.root, "thread/${ThreadStore.LEGACY_MARKER}").exists())
        assertFalse(LegacySessions.clearIfNeeded(folder.root))
    }
}
