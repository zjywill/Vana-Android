package com.pinapia.vana.thread

import com.pinapia.vana.session.ChatMessage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ThreadArchiveTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun user(text: String) = ChatMessage(role = ChatMessage.Role.USER, text = text)
    private fun assistant(text: String) = ChatMessage(role = ChatMessage.Role.ASSISTANT, text = text)

    private fun archiveOver(store: ThreadStore): ThreadArchive =
        ThreadArchive(store).also { runBlocking { it.await() } }

    @Test
    fun theIndexIsBuiltFromWhatWasAlreadyOnDisk() {
        val first = ThreadStore(folder.newFolder("t"))
        first.sync(listOf(user("我想学吉他"), assistant("好的")), emptySet(), emptySet())

        val archive = archiveOver(ThreadStore(java.io.File(folder.root, "t")))

        assertEquals(2, archive.size)
    }

    @Test
    fun laterWritesAreAppliedIncrementallyThroughTheChangeListener() {
        val store = ThreadStore(folder.newFolder("t"))
        val archive = archiveOver(store)
        assertEquals(0, archive.size)

        val a = user("第一句")
        var known = store.sync(listOf(a), emptySet(), emptySet())
        assertEquals(1, archive.size)

        val updated = a.copy(text = "改过的第一句")
        known = store.sync(listOf(updated), setOf(a.id), known)
        assertEquals("同一条更新不该多出一行", 1, archive.size)
        assertEquals("改过的第一句", archive.rowsBefore(Double.MAX_VALUE).single().text)

        store.sync(emptyList(), emptySet(), known)
        assertEquals("删掉的从索引里消失", 0, archive.size)
    }

    @Test
    fun clearingTheThreadClearsTheIndex() {
        val store = ThreadStore(folder.newFolder("t"))
        val archive = archiveOver(store)
        store.sync(listOf(user("x")), emptySet(), emptySet())
        assertEquals(1, archive.size)

        store.deleteAll()

        assertEquals(0, archive.size)
    }

    @Test
    fun onlyRowsBeforeTheWindowStartAreHistory() {
        val store = ThreadStore(folder.newFolder("t"))
        val archive = archiveOver(store)
        val messages = (1..6).map { user("第${it}句") }
        store.sync(messages, emptySet(), emptySet())
        val windowStart = store.positionOf(messages[3].id)!!

        assertEquals(listOf("第1句", "第2句", "第3句"), archive.rowsBefore(windowStart).map { it.text })
        assertTrue(archive.hasRowsBefore(windowStart))
        assertFalse("窗口起点就是第一条时没有滑出去的历史", archive.hasRowsBefore(store.positionOf(messages[0].id)!!))
    }

    @Test
    fun placeholderAndEmptyMessagesAreNotIndexed() {
        val store = ThreadStore(folder.newFolder("t"))
        val archive = archiveOver(store)
        store.sync(
            listOf(
                user("有内容"),
                assistant(""),
                assistant("已停止回复").copy(textIsPlaceholder = true),
            ),
            emptySet(),
            emptySet(),
        )
        assertEquals(1, archive.size)
    }

    @Test
    fun aroundReturnsTheNeighboursOfAMessageInOrder() {
        val store = ThreadStore(folder.newFolder("t"))
        val archive = archiveOver(store)
        val messages = (1..10).map { if (it % 2 == 1) user("问$it") else assistant("答$it") }
        store.sync(messages, emptySet(), emptySet())

        val rows = archive.around(messages[4].id, before = 1, after = 2)!!

        assertEquals(listOf("答4", "问5", "答6", "问7"), rows.map { it.text })
        assertNull(archive.around("不存在"))
    }

    @Test
    fun longTextIsStoredTruncatedSoTheIndexStaysSmall() {
        val store = ThreadStore(folder.newFolder("t"))
        val archive = archiveOver(store)
        store.sync(listOf(user("长".repeat(5_000)), assistant("答".repeat(5_000))), emptySet(), emptySet())
        val rows = archive.rowsBefore(Double.MAX_VALUE)
        assertEquals(ThreadArchive.USER_LIMIT, rows[0].text.length)
        assertEquals(ThreadArchive.ASSISTANT_LIMIT, rows[1].text.length)
    }
}
