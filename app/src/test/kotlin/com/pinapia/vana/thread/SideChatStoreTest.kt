package com.pinapia.vana.thread

import com.pinapia.vana.memory.MemoryHarvester
import com.pinapia.vana.memory.MemoryOperation
import com.pinapia.vana.memory.MemoryStore
import com.pinapia.vana.plugins.PluginEnvironment
import com.pinapia.vana.session.ChatMessage
import com.pinapia.vana.tenant.Tenant
import com.pinapia.vana.tenant.TenantPaths
import com.pinapia.vana.tenant.TenantStores
import com.pinapia.vana.vision.AttachmentStore
import com.pinapia.vana.vision.ChatAttachment
import java.io.File
import java.util.UUID
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 侧聊:名单怎么存、删的顺序、孤儿目录、名字怎么来、隔离,以及「对话历史」和记忆收割覆盖侧聊。
 * 断言的口径和 iOS 的 `VanaTests/SideChatTests` 一致。
 */
class SideChatStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val sidesDir get() = File(folder.root, SideChatStore.DIRECTORY_NAME)
    private fun store(attachments: AttachmentStore? = null) = SideChatStore(sidesDir, attachments)

    private fun user(text: String, at: kotlinx.datetime.Instant = Clock.System.now()) =
        ChatMessage(role = ChatMessage.Role.USER, text = text, createdAt = at)

    private fun assistant(text: String) = ChatMessage(role = ChatMessage.Role.ASSISTANT, text = text)

    private suspend fun ThreadWriter.put(vararg messages: ChatMessage) =
        write { it.sync(messages.toList(), emptySet(), emptySet()) }

    private suspend fun ThreadWriter.texts() =
        write { it.loadTail(minMessages = 10_000, maxSegments = 10_000).messages.map { m -> m.text } }

    // ================= 名字 =================

    @Test
    fun aSideChatIsNamedFromTheFirstLineOfTheFirstThingSaid() {
        assertEquals("十月去京都", SideChatTitle.make("  十月去京都\n先看看住哪"))
        assertNull(SideChatTitle.make("   \n  "))
        val title = SideChatTitle.make("长".repeat(40))!!
        assertEquals(SideChatTitle.MAX_LENGTH, SideChatTitle.length(title))
        assertTrue(title.endsWith("…"))
    }

    @Test
    fun aNameHeTypesIsFlattenedToOneLineAndCappedMoreGenerously() {
        assertEquals("装修 比价", SideChatTitle.clean("  装修\n 比价  "))
        assertEquals(SideChatTitle.MAX_TYPED_LENGTH, SideChatTitle.clean("字".repeat(50)).length)
        // 按中文的 20 字上限截,英文名字会被切掉半个词(iOS 上踩过)。
        assertEquals("Kyoto in October trip", SideChatTitle.clean("Kyoto in October trip"))
        assertEquals("", SideChat.new("  ").title)
        assertTrue(SideChat.new("  ").autoTitled)
        assertFalse(SideChat.new("京都").autoTitled)
    }

    @Test
    fun lengthsAreCountedInCharactersNotCodeUnits() {
        // 一个表情是两个 UTF-16 码元;按码元截会把它劈成半个,标题栏上出一个方块。
        val title = SideChatTitle.make("🏔".repeat(30))!!
        assertEquals(SideChatTitle.MAX_LENGTH, SideChatTitle.length(title))
        assertTrue(title.removeSuffix("…").all { it.isSurrogate() })
    }

    // ================= 名单 =================

    @Test
    fun theListSurvivesAFreshInstanceAndIsOrderedByLastActivity() = runBlocking {
        val start = Clock.System.now() - 600.seconds
        val older = store().create("京都", now = start)
        val newer = store().let { it.create("装修", now = start + 60.seconds) }
        assertEquals(listOf(newer.id, older.id), store().all().map { it.id })

        val first = store()
        first.noteActivity(older.id, "再看一眼", now = start + 120.seconds)
        val reloaded = store().all()
        assertEquals(listOf(older.id, newer.id), reloaded.map { it.id })
        assertEquals(listOf("京都", "装修"), reloaded.map { it.title })
    }

    /** 没起名的拿第一句带字的话起名;他自己起过或改过的名字,再也不替他改。 */
    @Test
    fun onlyAnUnnamedSideChatIsNamedByWhatIsSaidInIt() = runBlocking {
        val store = store()
        val unnamed = store.create("")
        store.noteActivity(unnamed.id, "")
        assertTrue(store.get(unnamed.id)!!.autoTitled)
        store.noteActivity(unnamed.id, "周末带孩子去哪")
        assertEquals("周末带孩子去哪", store.get(unnamed.id)!!.title)
        store.noteActivity(unnamed.id, "换个话题")
        assertEquals("周末带孩子去哪", store.get(unnamed.id)!!.title)

        val named = store.create("京都")
        store.noteActivity(named.id, "先看看住哪")
        assertEquals("京都", store.get(named.id)!!.title)

        val renamedToEmpty = store.create("")
        store.rename(renamedToEmpty.id, "")
        store.noteActivity(renamedToEmpty.id, "这句不该拿来起名")
        assertEquals("", store.get(renamedToEmpty.id)!!.title)
    }

    /** 同一条侧聊永远是同一个线程写者:两个实例就是两个写者。 */
    @Test
    fun theSameSideChatAlwaysGetsTheSameWriter() = runBlocking {
        val store = store()
        val chat = store.create("京都")
        assertSame(store.writer(chat.id), store.writer(chat.id))
        assertEquals(chat.id, store.writer(chat.id).store.directory.name)
        assertSame(SideChatStore.instance(sidesDir, null), SideChatStore.instance(File(sidesDir, "../${sidesDir.name}"), null))
    }

    // ================= 删 =================

    @Test
    fun deletingASideChatTakesItOffTheListAndRemovesItsThreadAndOnlyItsPhotos() = runBlocking {
        val attachments = AttachmentStore(folder.root)
        val main = ThreadWriter(ThreadStore(File(folder.root, "thread"), attachments))
        val mainPhoto = attachments.store(byteArrayOf(1))
        main.put(user("主对话里的照片").copy(attachments = listOf(ChatAttachment(imageFileName = mainPhoto))))

        val store = store(attachments)
        val chat = store.create("京都")
        val sidePhoto = attachments.store(byteArrayOf(2))
        store.writer(chat.id).put(user("住哪").copy(attachments = listOf(ChatAttachment(imageFileName = sidePhoto))))
        val threadDir = File(sidesDir, chat.id)
        assertTrue(threadDir.exists())

        store.delete(chat.id)
        assertTrue(store.all().isEmpty())
        assertTrue(store().all().isEmpty())
        assertFalse(threadDir.exists())
        assertFalse("侧聊自己的照片跟着走", File(folder.root, "attachments/$sidePhoto").exists())
        assertTrue("主对话的照片不能被带走", File(folder.root, "attachments/$mainPhoto").exists())
    }

    /** 删到一半崩了留下的目录(名单上已经没有它),下一次读名单时清掉。 */
    @Test
    fun aThreadDirectoryMissingFromTheListIsSweptOnTheNextRead() = runBlocking {
        val kept = store().let { s -> s.create("留着").also { s.writer(it.id).put(user("在")) } }
        val orphan = File(sidesDir, UUID.randomUUID().toString())
        ThreadStore(orphan).sync(listOf(user("孤儿")), emptySet(), emptySet())
        assertTrue(orphan.exists())

        val fresh = store()
        assertEquals(listOf(kept.id), fresh.all().map { it.id })
        assertFalse(orphan.exists())
        assertTrue(File(sidesDir, kept.id).exists())
    }

    /** 名单读不懂的时候,每个目录看起来都是孤儿——那时候一个都不许动。 */
    @Test
    fun anUnreadableListSweepsNothingAndIsBackedUp() = runBlocking {
        val chat = store().let { s -> s.create("京都").also { s.writer(it.id).put(user("在")) } }
        File(sidesDir, SideChatStore.INDEX_NAME).writeText("{不是 json")

        val fresh = store()
        assertTrue(fresh.all().isEmpty())
        assertTrue(File(sidesDir, chat.id).exists())
        assertTrue(File(sidesDir, "${SideChatStore.INDEX_NAME}.bak").exists())

        // 读不懂之后他又建了一条,名单被新的覆盖:这个进程里不清,下一次启动(新实例)也不清——
        // 备份里点过名的目录只是还没被找回来。
        fresh.create("新的")
        fresh.all()
        assertTrue(File(sidesDir, chat.id).exists())
        store().all()
        assertTrue(File(sidesDir, chat.id).exists())
    }

    /** 读不懂的那一条原样留着,它的目录也放过:那是还没被理解的数据,不是垃圾。 */
    @Test
    fun anEntryItCannotReadIsKeptAndSoIsItsDirectory() = runBlocking {
        val foreignId = UUID.randomUUID().toString()
        sidesDir.mkdirs()
        File(sidesDir, SideChatStore.INDEX_NAME).writeText("""[{"id":"$foreignId","title":"未来的格式","createdAt":42}]""")
        val foreignThread = File(sidesDir, foreignId)
        ThreadStore(foreignThread).sync(listOf(user("在")), emptySet(), emptySet())

        val store = store()
        assertTrue(store.all().isEmpty())
        store.create("京都")
        assertTrue(foreignThread.exists())
        assertTrue(File(sidesDir, SideChatStore.INDEX_NAME).readText().contains(foreignId))
    }

    /** 「清掉 30 天前的」:侧聊里旧的消息一起清;整条都在那之前的侧聊连名单一起删。 */
    @Test
    fun clearingOldHistoryReachesIntoSideChats() = runBlocking {
        val store = store()
        val old = Clock.System.now() - 40.days
        val stale = store.create("旧的", now = old)
        store.writer(stale.id).put(user("很久以前", old))
        val live = store.create("新的", now = old)
        val yesterday = Clock.System.now() - 1.days
        store.writer(live.id).put(user("很久以前", old), user("昨天", yesterday))
        store.noteActivity(live.id, "昨天", now = yesterday)

        val removed = store.deleteOlderThan(Clock.System.now() - 30.days)
        assertEquals(2, removed)
        assertEquals(listOf(live.id), store.all().map { it.id })
        assertEquals(listOf("昨天"), store.writer(live.id).texts())
    }

    // ================= 隔离 =================

    /** 那份清单就是「隔离」的定义:漏了 `sides`,建成员时不建、迁移时不搬。 */
    @Test
    fun sideChatsLiveInsideTheMembersDirectoryAndOnTheIsolationList() {
        assertTrue(TenantPaths.perTenantItems.any { it.name == SideChatStore.DIRECTORY_NAME && it.isDirectory })
        val root = folder.newFolder("tenant")
        val stores = TenantStores(root)
        assertEquals(root.absoluteFile.normalize(), stores.sides.directory.absoluteFile.normalize().parentFile)
        // 聊天界面没被告知用哪份名单时,推出来的必须是同一位成员的那一份、同一个实例——
        // 测试里传进来的是临时目录的线程,推出来的也就碰不到手机上那份真的。
        assertSame(stores.sides, SideChatStore.beside(stores.thread))
    }

    // ================= 对话历史 =================

    /** 「清空全部对话」里的「全部」包括侧聊;占用空间和清理的范围对得上。 */
    @Test
    fun conversationHistoryCoversSideChats() = runBlocking {
        val attachments = AttachmentStore(folder.root)
        val main = ThreadWriter(ThreadStore(File(folder.root, "thread"), attachments))
        val sides = store(attachments)
        val history = ConversationHistory(main, sides)
        main.put(user("主对话"))
        val mainOnly = history.sizeBytes()
        val chat = sides.create("京都")
        val photo = attachments.store(byteArrayOf(3))
        sides.writer(chat.id).put(user("住哪").copy(attachments = listOf(ChatAttachment(imageFileName = photo))))
        assertTrue("侧聊算进占用空间", history.sizeBytes() > mainOnly)

        history.clearAll()
        assertTrue(sides.all().isEmpty())
        assertEquals(0L, history.sizeBytes())
        assertFalse(File(sidesDir, chat.id).exists())
        assertFalse(File(folder.root, "attachments/$photo").exists())
        assertTrue(main.texts().isEmpty())
    }

    // ================= 收割 =================

    /** 记忆只有一份,主对话和侧聊都往里收;水位线各记各的。 */
    @Test
    fun harvestingWalksTheMainThreadAndEverySideChat() = runBlocking {
        val main = ThreadWriter(ThreadStore(File(folder.root, "thread")))
        val sides = store()
        fun conversation() = arrayOf(user("我在准备搬家"), assistant("好。"), user("月底之前要搬完"), assistant("明白。"))
        main.put(*conversation())
        val chat = sides.create("搬家")
        sides.writer(chat.id).put(*conversation())

        val seen = ArrayList<Int>()
        val outcomes = MemoryHarvester.runIfDue(
            writers = listOf(main) + sides.allWriters(),
            memory = MemoryStore(folder.newFolder("memory")),
            env = PluginEnvironment(
                isEnabled = { true },
                tenant = Tenant(name = "我", kind = Tenant.Kind.OWNER),
                archive = null,
                memoryStore = null,
            ),
            extract = { _, _, chunk ->
                seen += chunk.size
                emptyList<MemoryOperation>()
            },
        )
        assertEquals(listOf(MemoryHarvester.Outcome.DONE, MemoryHarvester.Outcome.DONE), outcomes)
        assertEquals(listOf(4, 4), seen)
        assertNotNull(main.write { it.meta().harvestedUpToPos })
        assertNotNull(sides.writer(chat.id).write { it.meta().harvestedUpToPos })
    }
}
