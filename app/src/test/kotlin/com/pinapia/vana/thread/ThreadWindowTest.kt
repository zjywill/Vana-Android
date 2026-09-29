package com.pinapia.vana.thread

import com.pinapia.vana.agentruntime.WindowPolicy
import com.pinapia.vana.session.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadWindowTest {
    private fun user(text: String) = ChatMessage(role = ChatMessage.Role.USER, text = text)
    private fun assistant(text: String) = ChatMessage(role = ChatMessage.Role.ASSISTANT, text = text)

    /** 每一轮 [tokens] 大约 token 的对话:一问一答。 */
    private fun conversation(turns: Int, charsPerTurn: Int): List<ChatMessage> =
        (1..turns).flatMap { listOf(user("问$it" + "字".repeat(charsPerTurn / 2)), assistant("答$it" + "字".repeat(charsPerTurn / 2))) }

    @Test
    fun cjkCountsAboutOneTokenPerCharacterAndOtherTextAboutAQuarter() {
        assertEquals(10, ThreadWindow.estimateTokens("一二三四五六七八九十"))
        assertEquals(3, ThreadWindow.estimateTokens("hello world!")) // 12 个非 CJK 字符 → 3
        assertEquals(0, ThreadWindow.estimateTokens(""))
    }

    @Test
    fun aTurnIsAUserMessageAndWhatFollowsUntilTheNextUserMessage() {
        val messages = listOf(user("一"), assistant("答一"), assistant("补充"), user("二"), assistant("答二"))
        val turns = ThreadWindow.turns(messages, startIndex = 0)
        assertEquals(listOf(0, 3), turns.map { it.startIndex })
    }

    @Test
    fun turnsStartAtTheGivenIndexAndAnEmptyOrOutOfRangeStartHasNoTurns() {
        val messages = conversation(4, 20)
        assertEquals(listOf(4, 6), ThreadWindow.turns(messages, startIndex = 4).map { it.startIndex })
        assertTrue(ThreadWindow.turns(messages, startIndex = 99).isEmpty())
        assertTrue(ThreadWindow.turns(emptyList(), startIndex = 0).isEmpty())
    }

    @Test
    fun theWindowDoesNotMoveUntilItReachesTheHighWatermark() {
        val messages = conversation(turns = 8, charsPerTurn = 40)
        val policy = WindowPolicy(budgetTokens = 10_000, minTailTurns = 3)
        assertEquals(0, ThreadWindow.evict(messages, startIndex = 0, policy = policy))
    }

    @Test
    fun onceOverBudgetItMovesToATurnBoundaryInOneStep() {
        val messages = conversation(turns = 20, charsPerTurn = 200)
        val policy = WindowPolicy(budgetTokens = 1_500, lowRatio = 0.4, minTailTurns = 3)
        val newStart = ThreadWindow.evict(messages, startIndex = 0, policy = policy)

        assertTrue("应当前移了", newStart > 0)
        assertEquals("只能落在一轮的开头，也就是用户消息", ChatMessage.Role.USER, messages[newStart].role)
        val remaining = ThreadWindow.turns(messages, newStart).sumOf { it.tokens }
        assertTrue("砍到低水位以下：$remaining", remaining <= policy.lowWatermark + 300)
    }

    @Test
    fun theFixedRequestOverheadIsSubtractedFromTheBudget() {
        val messages = conversation(turns = 10, charsPerTurn = 200) // 约 2100 token
        val policy = WindowPolicy(budgetTokens = 4_000, minTailTurns = 2)
        assertEquals("没有开销时不用滑", 0, ThreadWindow.evict(messages, 0, policy, overheadTokens = 0))
        assertTrue("system 段和工具定义占掉一大半，就该滑了", ThreadWindow.evict(messages, 0, policy, overheadTokens = 2_500) > 0)
    }

    @Test
    fun theNewestTurnsAreNeverEvicted() {
        val messages = conversation(turns = 10, charsPerTurn = 4_000)
        val policy = WindowPolicy(budgetTokens = 1_000, minTailTurns = 4)
        val newStart = ThreadWindow.evict(messages, 0, policy)
        assertEquals("最近四轮必须留下", 4, ThreadWindow.turns(messages, newStart).size)
    }

    @Test
    fun forceEvictKeepsOnlyTheLastFewTurnsRegardlessOfBudget() {
        val messages = conversation(turns = 10, charsPerTurn = 20)
        val newStart = ThreadWindow.forceEvict(messages, startIndex = 0, keepTurns = 2)
        assertEquals(2, ThreadWindow.turns(messages, newStart).size)
        assertEquals("已经只剩两轮时不再动", newStart, ThreadWindow.forceEvict(messages, newStart, keepTurns = 2))
    }

    @Test
    fun aWindowThatHasAlreadyMovedIsMeasuredFromItsOwnStart() {
        val messages = conversation(turns = 20, charsPerTurn = 200)
        val policy = WindowPolicy(budgetTokens = 1_500, lowRatio = 0.4, minTailTurns = 3)
        val first = ThreadWindow.evict(messages, 0, policy)
        // 又追加了几轮:从新的起点算,还没涨到高水位就不该再动。
        assertEquals(first, ThreadWindow.evict(messages, first, policy))
    }
}
