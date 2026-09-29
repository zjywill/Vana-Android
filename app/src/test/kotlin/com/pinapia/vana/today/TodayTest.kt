package com.pinapia.vana.today

import com.pinapia.vana.medications.MedicationItem
import com.pinapia.vana.medications.MedicationSnapshot
import com.pinapia.vana.memory.MemoryItem
import com.pinapia.vana.memory.MemorySnapshot
import com.pinapia.vana.plugins.PluginIds
import com.pinapia.vana.tasks.PlanItem
import com.pinapia.vana.tasks.Task
import com.pinapia.vana.tasks.TaskKind
import com.pinapia.vana.tasks.TaskStatus
import java.time.ZoneId
import java.util.Locale
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TodayTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = Instant.parse("2026-09-29T02:00:00Z") // 上海 10:00

    private fun reminder(title: String, due: Instant, status: TaskStatus = TaskStatus.QUEUED) =
        Task(kind = TaskKind.REMINDER, title = title, status = status, dueAt = due)

    private fun cards(
        tasks: List<Task> = emptyList(),
        memory: MemorySnapshot = MemorySnapshot.empty,
        meds: MedicationSnapshot = MedicationSnapshot.empty,
        isEnabled: (String) -> Boolean = { true },
    ): List<TodayCard> = zh { TodayCompute.cards(now, zone, tasks, memory, meds, isEnabled) }

    private fun <T> zh(block: () -> T): T {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
            return block()
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun nothingScheduledMeansNoCardsAtAll() {
        assertTrue(cards().isEmpty())
    }

    @Test
    fun aReminderLaterTodayShowsButTomorrowsDoesNot() {
        val result = cards(
            listOf(
                reminder("带伞", now + 5.hours),
                reminder("明天的事", now + 20.hours),
            ),
        )
        assertEquals(listOf("带伞"), result.map { it.title })
        assertEquals(TodayPriority.DUE_TODAY_REMINDER, result.single().priority)
    }

    @Test
    fun aReminderThatIsPastDueOutranksOneStillAhead() {
        val result = cards(listOf(reminder("待会儿", now + 2.hours), reminder("早该做了", now - 1.hours)))
        assertEquals(listOf("早该做了", "待会儿"), result.map { it.title })
        assertTrue(result.first().body!!.contains("已过点"))
    }

    /** 卡上那颗图标按类别上色:过点的和到点的不能是同一种颜色(和 iOS 同一套)。 */
    @Test
    fun eachCardSaysWhatKindItIs() {
        val job = Task(kind = TaskKind.JOB, title = "查租房", status = TaskStatus.PROPOSED, brief = "x")
        val goal = Task(kind = TaskKind.GOAL, title = "备半马", status = TaskStatus.RUNNING)
        val kinds = cards(listOf(goal, job, reminder("过点了", now - 1.hours), reminder("待会儿", now + 1.hours)))
            .associate { it.title to it.kind }
        assertEquals(TodayKind.OVERDUE, kinds["过点了"])
        assertEquals(TodayKind.REMINDER, kinds["待会儿"])
        assertEquals(TodayKind.NEEDS_YOU, kinds["查租房"])
        assertEquals(TodayKind.GOAL, kinds["备半马"])
    }

    @Test
    fun finishedRemindersAreNotShown() {
        assertTrue(cards(listOf(reminder("做完了", now + 1.hours, status = TaskStatus.DONE))).isEmpty())
    }

    @Test
    fun aJobWaitingForTheUserComesBeforeEverythingButAnOverdueReminder() {
        val job = Task(kind = TaskKind.JOB, title = "查租房", status = TaskStatus.PROPOSED, brief = "x")
        val goal = Task(kind = TaskKind.GOAL, title = "备半马", status = TaskStatus.RUNNING)
        val result = cards(listOf(goal, job, reminder("过点了", now - 1.hours)))
        assertEquals(listOf("过点了", "查租房", "备半马"), result.map { it.title })
    }

    @Test
    fun aGoalShowsItsProgress() {
        val goal = Task(
            kind = TaskKind.GOAL,
            title = "备半马",
            status = TaskStatus.RUNNING,
            plan = listOf(PlanItem(text = "a", done = true), PlanItem(text = "b")),
        )
        assertEquals("步骤 1/2", cards(listOf(goal)).single().body)
    }

    @Test
    fun aDueFollowUpBecomesACardThatAsksAboutIt() {
        val followUp = MemoryItem(
            text = "试了新枕头",
            kind = MemoryItem.Kind.FOLLOW_UP,
            dueAt = now - 1.days,
        )
        val card = cards(memory = MemorySnapshot(listOf(followUp))).single()
        assertTrue(card.title.contains("试了新枕头"))
        assertTrue((card.action as TodayAction.Ask).prompt.contains("试了新枕头"))
    }

    @Test
    fun withMemoryOffNoFollowUpCardAppears() {
        val followUp = MemoryItem(text = "试了新枕头", kind = MemoryItem.Kind.FOLLOW_UP, dueAt = now - 1.days)
        val result = cards(memory = MemorySnapshot(listOf(followUp)), isEnabled = { it != PluginIds.MEMORY })
        assertTrue(result.isEmpty())
    }

    @Test
    fun aMedicationFollowUpComesFromTheHealthPluginAndVanishesWhenItIsOff() {
        val med = MedicationItem(name = "维生素 D", followUpAt = now - 1.days)
        val meds = MedicationSnapshot(listOf(med))
        val on = cards(meds = meds)
        assertEquals("health", on.single().pluginId)
        assertEquals(TodayAction.OpenSurface("medications"), on.single().action)

        assertTrue(cards(meds = meds, isEnabled = { it != PluginIds.HEALTH }).isEmpty())
        assertTrue(cards(meds = meds, isEnabled = { it != PluginIds.HEALTH_MEDICATIONS }).isEmpty())
    }

    @Test
    fun theAttentionBadgeCountsOnlyWhatNeedsALookNow() {
        val goal = Task(kind = TaskKind.GOAL, title = "备半马", status = TaskStatus.RUNNING)
        val result = cards(listOf(goal, reminder("现在", now - 1.hours), reminder("下午", now + 3.hours)))
        assertEquals(2, TodayCompute.attention(result))
        assertFalse(TodayCompute.attention(cards(listOf(goal))) > 0)
    }

    @Test
    fun theStripNeverShowsMoreThanTheLimit() {
        val many = (1..12).map { reminder("提醒$it", now + it.hours.coerceAtMost(13.hours)) }
        assertTrue(cards(many).size <= 8)
    }
}
