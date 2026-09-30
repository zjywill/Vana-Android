package com.pinapia.vana.tasks

import java.io.File
import kotlinx.datetime.Clock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TaskStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun store() = TaskStore(folder.root)
    private fun reminder(title: String) = Task(kind = TaskKind.REMINDER, title = title, status = TaskStatus.QUEUED, dueAt = Clock.System.now())

    @Test
    fun addedTasksComeBackFromAFreshInstanceInCreationOrder() {
        val store = store()
        store.add(reminder("一"))
        store.add(reminder("二"))
        assertEquals(listOf("一", "二"), TaskStore(folder.root).all().map { it.title })
    }

    @Test
    fun updateChangesOnlyThatTaskAndBumpsUpdatedAt() {
        val store = store()
        val a = store.add(reminder("甲"))
        val b = store.add(reminder("乙"))
        val later = Clock.System.now()

        val updated = store.update(a.id, now = later) { it.copy(title = "甲改", status = TaskStatus.DONE) }!!

        assertEquals("甲改", updated.title)
        assertEquals(later, updated.updatedAt)
        assertEquals(a.id, updated.id)
        assertEquals("乙", store.get(b.id)!!.title)
        assertFalse(store.active().any { it.id == a.id })
    }

    @Test
    fun updateCannotChangeTheId() {
        val store = store()
        val a = store.add(reminder("甲"))
        val updated = store.update(a.id) { it.copy(id = "别的") }!!
        assertEquals(a.id, updated.id)
    }

    @Test
    fun deleteRemovesAndReportsWhetherAnythingWasThere() {
        val store = store()
        val a = store.add(reminder("甲"))
        assertTrue(store.delete(a.id))
        assertFalse(store.delete(a.id))
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun findAcceptsAFullIdOrAnUnambiguousPrefix() {
        val store = store()
        val a = store.add(reminder("甲").copy(id = "aaaa1111-0000"))
        store.add(reminder("乙").copy(id = "aaaa2222-0000"))
        val c = store.add(reminder("丙").copy(id = "bbbb3333-0000"))

        assertEquals(a.id, store.find("aaaa1111-0000")!!.id)
        assertEquals(a.id, store.find("aaaa1")!!.id)
        assertEquals(c.id, store.find("bbbb")!!.id)
        assertNull("前缀不唯一宁可找不到，也别动错一条", store.find("aaaa"))
        assertNull(store.find(""))
        assertNull(store.find("zzzz"))
    }

    @Test
    fun theRevisionCountsEveryWrite() {
        val store = store()
        val before = store.revision.value
        val a = store.add(reminder("甲"))
        store.update(a.id) { it.copy(title = "改") }
        store.delete(a.id)
        assertEquals(before + 3, store.revision.value)
    }

    @Test
    fun aTaskKindThisBuildCannotReadIsPreservedAcrossSaves() {
        File(folder.root, "tasks.json").writeText(
            """{"tasks":[
              {"id":"x","kind":"hologram","title":"未来的种类","status":"queued","createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-01T00:00:00Z"}
            ]}""",
        )
        val store = store()
        assertTrue(store.all().isEmpty())

        store.add(reminder("新的"))

        assertTrue(File(folder.root, "tasks.json").readText().contains("hologram"))
        assertEquals(1, store.all().size)
    }

    /**
     * 后台任务(子 agent)2026-09-30 撤掉了,`job` 这一类跟着没了。以前存下来的那几条认不出 kind:原样留着
     * (下一次保存不会悄悄丢掉),只是不再显示;同一份文件里的提醒和目标照常读得出来。
     */
    @Test
    fun anOldBackgroundJobIsKeptOnDiskButNoLongerShown() {
        File(folder.root, "tasks.json").writeText(
            """{"tasks":[
              {"id":"j1","kind":"job","title":"比较三款净化器","status":"proposed","brief":"x","createdAt":"2026-09-29T00:00:00Z","updatedAt":"2026-09-29T00:00:00Z"},
              {"id":"g1","kind":"goal","title":"备半马","status":"running","digestEnabled":true,"createdAt":"2026-09-29T00:00:00Z","updatedAt":"2026-09-29T00:00:00Z"}
            ]}""",
        )
        val store = store()
        assertEquals(listOf("备半马"), store.all().map { it.title })

        store.add(reminder("新的"))
        assertTrue(File(folder.root, "tasks.json").readText().contains("比较三款净化器"))
        assertEquals(2, store.all().size)
    }

    @Test
    fun anUnreadableFileIsBackedUpBeforeItCanBeOverwritten() {
        File(folder.root, "tasks.json").writeText("{ 坏了")
        val store = store()
        assertTrue(store.all().isEmpty())
        store.add(reminder("之后写的"))
        assertEquals("{ 坏了", File(folder.root, "tasks.json.bak").readText())
    }

    @Test
    fun removeAllClearsEverythingIncludingWhatThisBuildCannotRead() {
        File(folder.root, "tasks.json").writeText(
            """{"tasks":[{"id":"x","kind":"hologram","title":"t","status":"queued","createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-01T00:00:00Z"}]}""",
        )
        val store = store()
        store.removeAll()
        assertFalse(File(folder.root, "tasks.json").readText().contains("hologram"))
    }

    @Test
    fun handleIsTheFirstEightCharactersOfTheId() {
        assertEquals("abcdef12", reminder("x").copy(id = "abcdef12-3456").handle)
    }
}
