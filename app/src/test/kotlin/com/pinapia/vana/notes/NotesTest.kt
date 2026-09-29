package com.pinapia.vana.notes

import com.pinapia.vana.agentruntime.CapabilityInvocation
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NoteStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun store() = NoteStore(folder.root)

    @Test
    fun addedNotesComeBackNewestTouchedFirst() {
        val store = store()
        val a = store.add(Note(kind = NoteKind.NOTE, title = "甲"))!!
        store.add(Note(kind = NoteKind.LIST, title = "乙"))
        store.update(a.id) { it.copy(body = "改了") }
        assertEquals(listOf("甲", "乙"), NoteStore(folder.root).all().map { it.title })
    }

    @Test
    fun findByUniquePrefixOnly() {
        val store = store()
        val n = store.add(Note(kind = NoteKind.NOTE, title = "x"))!!
        assertEquals(n.id, store.find(n.handle)!!.id)
        assertNull(store.find("zzzz"))
        assertNull(store.find(""))
    }

    @Test
    fun theStoreStopsAtTheNoteLimit() {
        val store = store()
        repeat(Note.MAX_NOTES) { assertTrue(store.add(Note(kind = NoteKind.NOTE, title = "n$it")) != null) }
        assertNull(store.add(Note(kind = NoteKind.NOTE, title = "多了")))
    }

    @Test
    fun unreadableEntriesAreKeptAndAnUnreadableFileIsBackedUp() {
        val file = File(folder.root, "notes.json")
        file.writeText("""{"notes":[{"id":"a","kind":"note","title":"好的","createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-01T00:00:00Z"},{"id":"b","kind":"hologram","title":"未来的种类"}]}""")
        val store = store()
        assertEquals(listOf("好的"), store.all().map { it.title })
        store.add(Note(kind = NoteKind.NOTE, title = "新的"))
        assertTrue("读不懂的条目应原样留着", file.readText().contains("hologram"))

        file.writeText("{ 坏了")
        val second = NoteStore(folder.root)
        assertTrue(second.all().isEmpty())
        second.add(Note(kind = NoteKind.NOTE, title = "之后"))
        assertEquals("{ 坏了", File(folder.root, "notes.json.bak").readText())
    }
}

class NotesToolsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val store by lazy { NoteStore(folder.root) }
    private val registry by lazy { NotesTools.registry(store) }

    private fun call(name: String, json: String) = runBlocking {
        registry.execute(CapabilityInvocation(toolCallId = "1", name = name, input = json))
    }

    @Test
    fun aListWithItemsIsSavedAsAListAndReadBackWithCheckboxes() {
        val saved = call("save_note", """{"title":"购物","items":["牛奶","鸡蛋"]}""")
        assertFalse(saved.isError)
        val note = store.all().single()
        assertEquals(NoteKind.LIST, note.kind)
        val text = call("read_note", """{"id":"${note.handle}"}""").output.text
        assertTrue(text.contains("- [ ] 牛奶"))
        assertTrue(text.contains("- [ ] 鸡蛋"))
    }

    @Test
    fun withoutItemsItIsAPlainNote() {
        call("save_note", """{"title":"想法","body":"写一篇关于跑步的文章"}""")
        val note = store.all().single()
        assertEquals(NoteKind.NOTE, note.kind)
        assertEquals("写一篇关于跑步的文章", note.body)
    }

    @Test
    fun updatingAListChecksAddsAndRemovesByPartOfTheText() {
        call("save_note", """{"title":"购物","items":["牛奶","鸡蛋","面包"]}""")
        val handle = store.all().single().handle
        val result = call("update_note", """{"id":"$handle","check_items":["牛"],"add_items":["香蕉"],"remove_items":["面包"],"uncheck_items":["不存在"]}""")
        assertFalse(result.isError)
        assertTrue("没找到的要说出来", result.output.text.contains("不存在"))
        val items = store.all().single().items
        assertEquals(listOf("牛奶", "鸡蛋", "香蕉"), items.map { it.text })
        assertEquals(listOf(true, false, false), items.map { it.done })
    }

    @Test
    fun appendAddsToTheEndAndBodyReplaces() {
        call("save_note", """{"title":"想法","body":"第一段"}""")
        val handle = store.all().single().handle
        call("update_note", """{"id":"$handle","append":"第二段"}""")
        assertEquals("第一段\n第二段", store.all().single().body)
        call("update_note", """{"id":"$handle","body":"重写了"}""")
        assertEquals("重写了", store.all().single().body)
    }

    @Test
    fun itemEditsOnAPlainNoteAreRefusedAndNothingChanges() {
        call("save_note", """{"title":"想法","body":"文字"}""")
        val note = store.all().single()
        val result = call("update_note", """{"id":"${note.handle}","add_items":["x"]}""")
        assertTrue(result.isError)
        assertEquals(note, store.get(note.id))
    }

    @Test
    fun listFiltersByQueryAndSaysWhenThereIsNothing() {
        assertTrue(call("list_notes", "{}").output.text.contains("还没有"))
        call("save_note", """{"title":"购物","items":["牛奶"]}""")
        call("save_note", """{"title":"想法","body":"跑步"}""")
        assertEquals(1, call("list_notes", """{"query":"牛奶"}""").output.text.lines().count { it.startsWith("- ") })
        assertTrue(call("list_notes", """{"query":"没有这个词"}""").output.text.contains("没有含"))
    }

    @Test
    fun badInputIsRefused() {
        assertTrue(call("save_note", """{"title":""}""").isError)
        assertTrue(call("save_note", """{"title":"${"长".repeat(Note.MAX_TITLE + 1)}"}""").isError)
        assertTrue(call("read_note", """{"id":"nope"}""").isError)
        assertTrue("没有删除工具，模型不能删用户的东西", NotesTools.registry(store).definitions.none { it.name.contains("delete") })
    }
}
