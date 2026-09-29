package com.pinapia.vana.recall

import com.pinapia.vana.memory.MemoryItem
import com.pinapia.vana.memory.MemoryStore
import com.pinapia.vana.thread.ThreadStore
import com.pinapia.vana.thread.ThreadWriter
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FollowUpRunnerTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val now = Clock.System.now()
    private val memory by lazy { MemoryStore(folder.newFolder("memory")) }
    private val writer by lazy { ThreadWriter(ThreadStore(folder.newFolder("thread"))) }

    /** 一条已经到期的待跟进。 */
    private fun dueFollowUp(): MemoryItem =
        memory.remember("看看维 D 有没有效果", MemoryItem.Kind.FOLLOW_UP, days = 1, now = now - 2.days)!!

    private fun pending(memoryEnabled: Boolean = true) = runBlocking {
        FollowUpRunner.pending(now = now, memoryStore = memory, writer = writer, memoryEnabled = memoryEnabled)
    }

    private fun recordRun(item: MemoryItem, ago: kotlin.time.Duration, conclusion: String? = "看过了") = runBlocking {
        writer.write { store ->
            store.updateMeta {
                it.copy(derived = it.derived + ("followup:${item.id}" to ThreadStore.DerivedRecord(now - ago, conclusion)))
            }
        }
    }

    @Test
    fun aDueFollowUpThatHasNeverBeenRunIsPending() {
        val item = dueFollowUp()
        assertEquals(item.id, pending()?.id)
    }

    @Test
    fun itIsNotRunAgainWithinADay() {
        val item = dueFollowUp()
        recordRun(item, ago = 2.hours)
        assertNull(pending())
    }

    @Test
    fun itIsPendingAgainAfterADay() {
        val item = dueFollowUp()
        recordRun(item, ago = 25.hours)
        assertEquals(item.id, pending()?.id)
    }

    @Test
    fun nothingRunsWithMemoryOff() {
        dueFollowUp()
        assertNull(pending(memoryEnabled = false))
    }

    @Test
    fun aFollowUpThatIsNotDueYetIsIgnored() {
        memory.remember("下个月再看", MemoryItem.Kind.FOLLOW_UP, days = 30, now = now)
        assertNull(pending())
    }

    @Test
    fun theConclusionComesFromTheLedgerForTheNotification() {
        val item = dueFollowUp()
        recordRun(item, ago = 1.hours, conclusion = "维 D 已经到正常范围了。")
        val conclusion = runBlocking { FollowUpRunner.conclusion(item, writer) }
        assertEquals("维 D 已经到正常范围了。", conclusion)
        assertNull(runBlocking { FollowUpRunner.conclusion(dueFollowUp().copy(id = "other"), writer) })
    }

    @Test
    fun theQuestionQuotesThePromiseWithoutItsTrailingPunctuation() {
        val question = FollowUpRunner.question(
            MemoryItem(text = "看看维 D 有没有效果。", kind = MemoryItem.Kind.FOLLOW_UP),
        )
        assertEquals(true, question.contains("看看维 D 有没有效果。现在怎么样了？"))
    }
}
