package com.pinapia.vana.agentruntime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowPolicyTests {
    private val policy = WindowPolicy(budgetTokens = 1000, lowRatio = 0.4, minTailTurns = 3)

    @Test
    fun nothingHappensUntilTheHighWatermarkIsReached() {
        assertEquals(0, policy.turnsToEvict(emptyList()))
        assertEquals(0, policy.turnsToEvict(listOf(200, 200, 200, 200, 200))) // 恰好 1000,没超
    }

    @Test
    fun onceOverItCutsDownToTheLowWatermarkInOneStepNotOneTurnAtATime() {
        // 1100 > 1000。低水位 400。最少要丢到剩 ≤400:丢 4 轮(剩 300+…)——一次到位,不是丢一轮就停在 1000 边上。
        val turns = listOf(200, 200, 200, 200, 100, 100, 100)
        val evicted = policy.turnsToEvict(turns)
        assertEquals(4, evicted)
        assertTrue(turns.drop(evicted).sum() <= policy.lowWatermark)
    }

    @Test
    fun theEvictionIsBatchedSoTheNextTurnsDoNotTriggerAnotherOneImmediately() {
        val turns = mutableListOf(200, 200, 200, 200, 100, 100, 100)
        val evicted = policy.turnsToEvict(turns)
        val after = turns.drop(evicted).toMutableList()
        // 接下来还能再追加不少轮才会碰到高水位——缓存前缀这段时间是纯追加。
        var appended = 0
        while (policy.turnsToEvict(after) == 0) {
            after += 100
            appended++
        }
        assertTrue("淘汰之后应当还有余量：只追加了 $appended 轮", appended >= 4)
    }

    @Test
    fun theNewestTurnsAreNeverEvictedEvenWhenTheyAloneExceedTheBudget() {
        // 最近三轮加起来就 2400,远超预算——但不能淘汰它们。
        val turns = listOf(100, 800, 800, 800)
        assertEquals(1, policy.turnsToEvict(turns))
        assertEquals(0, policy.turnsToEvict(listOf(800, 800, 800)))
    }

    @Test
    fun aSingleHugeCurrentTurnIsProtectedToo() {
        val single = WindowPolicy(budgetTokens = 1000, minTailTurns = 1)
        assertEquals(0, single.turnsToEvict(listOf(5000)))
        assertEquals(1, single.turnsToEvict(listOf(300, 5000)))
    }

    @Test
    fun budgetIsAboutAThirdOfTheContextClampedAndHasADefaultWhenUnknown() {
        assertEquals(16_000, WindowPolicy.budgetFor(null))
        assertEquals(16_000, WindowPolicy.budgetFor(0))
        assertEquals(12_000, WindowPolicy.budgetFor(8_000)) // 下限
        assertEquals(44_800.coerceAtMost(32_000), WindowPolicy.budgetFor(128_000)) // 封顶
        assertEquals(32_000, WindowPolicy.budgetFor(1_000_000))
        assertEquals(35_000 * 0 + 21_000, WindowPolicy.budgetFor(60_000))
    }

    @Test
    fun invalidParametersAreRejected() {
        val failures = listOf<() -> Unit>(
            { WindowPolicy(budgetTokens = 0) },
            { WindowPolicy(budgetTokens = 100, lowRatio = 1.0) },
            { WindowPolicy(budgetTokens = 100, minTailTurns = 0) },
        ).count { block -> runCatching(block).isFailure }
        assertEquals(3, failures)
    }
}
