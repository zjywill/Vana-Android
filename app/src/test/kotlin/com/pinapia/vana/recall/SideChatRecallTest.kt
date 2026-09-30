package com.pinapia.vana.recall

import com.pinapia.vana.agentruntime.CapabilityInvocation
import com.pinapia.vana.session.ChatMessage
import com.pinapia.vana.thread.SideChatStore
import com.pinapia.vana.thread.ThreadStore
import com.pinapia.vana.thread.ThreadWriter
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 跨线程召回:侧聊和主对话窗口各管各的,互通靠召回。搜出来、读回来的每一处标上在哪条线上;删掉的侧聊下一轮
 * 就翻不到。口径同 iOS `SideChatTests` 的 S3 那几条。
 */
class SideChatRecallTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val main by lazy { ThreadWriter(ThreadStore(File(folder.root, "thread"))) }
    private val sides by lazy { SideChatStore(File(folder.root, SideChatStore.DIRECTORY_NAME)) }

    private suspend fun ThreadWriter.say(user: String, reply: String = "好的") = write {
        it.sync(
            listOf(ChatMessage(role = ChatMessage.Role.USER, text = user), ChatMessage(role = ChatMessage.Role.ASSISTANT, text = reply)),
            emptySet(),
            emptySet(),
        )
    }

    private suspend fun search(sources: List<HistoryRecallTools.Source>, query: String): String =
        HistoryRecallTools.registry(sources)
            .execute(CapabilityInvocation(toolCallId = "t", name = HistoryRecallTools.SEARCH_TOOL_NAME, input = """{"query":"$query"}"""))
            .output.text.orEmpty()

    @Test
    fun theMainThreadReachesSideChatsWithContentAndListsThem() = runBlocking {
        val kyoto = sides.create("京都")
        sides.writer(kyoto.id).say("十月去京都住四条")
        sides.create("空的") // 没说过话的不算

        val (others, scope) = SideChatRecall.gather(main, sides, current = null)
        assertEquals(listOf("侧聊「京都」"), others.map { it.label })
        assertEquals("他开的侧聊", scope?.others)
        assertEquals(listOf("京都"), scope?.sideChats?.map { it.title })

        val hits = search(others, "京都住哪")
        assertTrue(hits, hits.contains("· 侧聊「京都」 ·"))
        assertTrue(hits.contains("十月去京都住四条"))
    }

    @Test
    fun aSideChatReachesTheMainThreadAndTheOtherSideChatsButNotItself() = runBlocking {
        main.say("我在准备搬家")
        val kyoto = sides.create("京都")
        sides.writer(kyoto.id).say("十月去京都")
        val move = sides.create("搬家")
        sides.writer(move.id).say("纸箱买多少")

        val (others, scope) = SideChatRecall.gather(main, sides, current = move.id)
        assertEquals(setOf("主对话", "侧聊「京都」"), others.map { it.label }.toSet())
        assertEquals("主对话和别的侧聊", scope?.others)
        assertTrue("侧聊里不挂名单", scope!!.sideChats.isEmpty())

        val onlyMain = SideChatRecall.gather(main, SideChatStore(File(folder.root, "other-sides")), current = move.id)
        assertEquals("主对话", onlyMain.second?.others)
    }

    @Test
    fun aDeletedSideChatIsOutOfReachOnTheNextTurn() = runBlocking {
        val kyoto = sides.create("京都")
        sides.writer(kyoto.id).say("十月去京都住四条")
        assertEquals(1, SideChatRecall.gather(main, sides, current = null).first.size)

        sides.delete(kyoto.id)
        val (others, scope) = SideChatRecall.gather(main, sides, current = null)
        assertTrue(others.isEmpty())
        assertNull(scope)
    }

    @Test
    fun readingFromAnotherThreadSaysWhichThreadItIs() = runBlocking {
        val kyoto = sides.create("京都")
        sides.writer(kyoto.id).say("十月去京都住四条", "四条交通方便")
        val (others, _) = SideChatRecall.gather(main, sides, current = null)
        val listing = search(others, "京都")
        val handle = Regex("""- (H[0-9A-Z]+) ·""").find(listing)!!.groupValues[1]
        val read = HistoryRecallTools.registry(others)
            .execute(CapabilityInvocation(toolCallId = "t", name = HistoryRecallTools.READ_TOOL_NAME, input = """{"id":"$handle"}"""))
            .output.text.orEmpty()
        assertTrue(read, read.lineSequence().first().contains("（侧聊「京都」）"))
        assertTrue(read.contains("四条交通方便"))
    }

    /** 只有这条对话自己时,工具说明和线上一直以来的那份逐字一样;能翻别的线时才多说一句。 */
    @Test
    fun theSearchDescriptionOnlyChangesWhenOtherThreadsAreReachable() = runBlocking {
        main.say("以前的事")
        val own = HistoryRecallTools.registry(main.archive) { Double.MAX_VALUE }.definitions.first().description.orEmpty()
        assertFalse(own.contains("别的对话线"))
        val kyoto = sides.create("京都")
        sides.writer(kyoto.id).say("十月去京都")
        val (others, _) = SideChatRecall.gather(main, sides, current = null)
        val wide = HistoryRecallTools.registry(others).definitions.first().description.orEmpty()
        assertTrue(wide.contains("以及别的对话线（主对话、侧聊）里说过的"))
        assertTrue(wide.startsWith(own.substringBefore("。")))
    }
}
