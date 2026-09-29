package com.pinapia.vana.recall

import com.pinapia.vana.agentruntime.CapabilityExecutionResult
import com.pinapia.vana.agentruntime.CapabilityInvocation
import com.pinapia.vana.session.ChatMessage
import com.pinapia.vana.thread.ThreadArchive
import com.pinapia.vana.thread.ThreadStore
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HistoryRecallToolsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val store by lazy { ThreadStore(folder.newFolder("thread")) }
    private val archive by lazy { ThreadArchive(store).also { runBlocking { it.await() } } }

    /** 写进线程的消息,按调用顺序排,返回各自的 id。 */
    private var written = emptyList<ChatMessage>()
    private var known = emptySet<String>()

    private fun say(user: String, reply: String? = "好的", ageDays: Long = 0): ChatMessage {
        val at = Clock.System.now() - ageDays.days
        val u = ChatMessage(role = ChatMessage.Role.USER, text = user, createdAt = at)
        val list = written + u + listOfNotNull(reply?.let { ChatMessage(role = ChatMessage.Role.ASSISTANT, text = it, createdAt = at) })
        known = store.sync(list, emptySet(), known)
        written = list
        return u
    }

    /** 窗口起点在 [first] 那条消息:它之前的都算「滑出去了」。 */
    private fun hiddenBefore(first: ChatMessage): () -> Double? = { store.positionOf(first.id) }

    private fun call(name: String, input: String, hidden: () -> Double?): CapabilityExecutionResult = runBlocking {
        HistoryRecallTools.registry(archive, hidden)
            .execute(CapabilityInvocation(toolCallId = "t", name = name, input = input))
    }

    private fun search(query: String, hidden: () -> Double?) =
        call(HistoryRecallTools.SEARCH_TOOL_NAME, """{"query":"$query"}""", hidden).output.text

    @Test
    fun findsAnOlderExchangeByTheUsersWords() {
        say("我最近跑步以后膝盖有点疼")
        say("帮我看看这份体检报告")
        val windowStart = say("今天天气不错")

        val result = search("膝盖疼", hiddenBefore(windowStart))

        assertTrue(result, result.contains("找到 1 处"))
        assertTrue(result, result.contains("膝盖"))
        assertFalse(result, result.contains("体检"))
    }

    @Test
    fun whatIsStillInsideTheWindowIsNeverSearched() {
        val windowStart = say("窗口里的话题是咖啡")
        say("后面又聊了咖啡")

        val result = search("咖啡", hiddenBefore(windowStart))

        assertEquals("窗口起点之前什么都没有", "还没有可以回顾的过往对话。", result)
    }

    @Test
    fun withoutAWindowStartThereIsNothingToRecall() {
        say("聊聊咖啡")
        assertEquals("还没有可以回顾的过往对话。", search("咖啡") { null })
    }

    @Test
    fun aWeakMatchIsDroppedWhenAStrongOneExists() {
        say("跑步 膝盖 半马 备赛")
        say("只是随口提到跑步")
        val windowStart = say("换个话题")

        val result = search("跑步 膝盖 半马", hiddenBefore(windowStart))

        assertTrue(result, result.contains("找到 1 处"))
        assertTrue(result, result.contains("半马"))
    }

    @Test
    fun noMatchSaysSoInsteadOfInventingAnything() {
        say("聊聊天气")
        val windowStart = say("现在的话题")
        val result = call(HistoryRecallTools.SEARCH_TOOL_NAME, """{"query":"量子力学"}""", hiddenBefore(windowStart))
        assertFalse(result.isError)
        assertEquals("没有找到相关的过往对话。", result.output.text)
    }

    @Test
    fun sinceDaysLimitsHowFarBackToLook() {
        say("很久以前聊过吉他", ageDays = 100)
        say("上周聊过吉他", ageDays = 7)
        val windowStart = say("现在")

        val result = call(
            HistoryRecallTools.SEARCH_TOOL_NAME,
            """{"query":"吉他","since_days":30}""",
            hiddenBefore(windowStart),
        ).output.text

        assertTrue(result, result.contains("找到 1 处"))
        assertTrue(result, result.contains("上周"))
    }

    @Test
    fun readingAHandleReturnsTheExchangeAndTheStaleNumbersFooter() {
        say("我想聊聊睡眠", reply = "好的，先说说你的作息。")
        val windowStart = say("现在的话题")

        val hits = search("睡眠", hiddenBefore(windowStart))
        val handle = Regex("H[0-9A-Z]+").find(hits)!!.value
        val result = call(HistoryRecallTools.READ_TOOL_NAME, """{"id":"$handle"}""", hiddenBefore(windowStart))

        assertFalse(result.output.text, result.isError)
        assertTrue(result.output.text.contains("他：我想聊聊睡眠"))
        assertTrue(result.output.text.contains("Vana：好的，先说说你的作息。"))
        assertTrue(result.output.text.endsWith(HistoryRecallTools.footer))
    }

    @Test
    fun aHandleDoesNotChangeWhenAnEarlierMessageIsDeleted() {
        val target = say("我在准备考试")
        val handleBefore = HistoryRecallTools.handleOf(target.id)
        // 删掉它前面的一条:编号是 id 的散列,不是「第几条」,不会错位。
        val early = say("更早的一句").also { }
        assertEquals(handleBefore, HistoryRecallTools.handleOf(target.id))
        assertTrue(early.id != target.id)
    }

    @Test
    fun readingAnUnknownHandleIsAnErrorThatPointsBackToSearch() {
        say("随便")
        val windowStart = say("现在")
        val result = call(HistoryRecallTools.READ_TOOL_NAME, """{"id":"HZZZZ"}""", hiddenBefore(windowStart))
        assertTrue(result.isError)
        assertTrue(result.output.text.contains("search_sessions"))
    }

    @Test
    fun anEmptyQueryReturnsTheMostRecentHistory() {
        (1..8).forEach { say("第${it}天的话题") }
        val windowStart = say("窗口起点")
        val result = call(HistoryRecallTools.SEARCH_TOOL_NAME, """{"query":""}""", hiddenBefore(windowStart)).output.text
        assertTrue(result, result.contains("找到 6 处"))
    }
}
