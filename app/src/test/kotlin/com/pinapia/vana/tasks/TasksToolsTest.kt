package com.pinapia.vana.tasks

import com.pinapia.vana.agentruntime.CapabilityExecutionResult
import com.pinapia.vana.agentruntime.CapabilityInvocation
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TasksToolsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = Instant.parse("2026-09-29T06:03:00Z") // 上海 14:03,周二

    private class Recorder : ReminderScheduling {
        val scheduled = mutableListOf<Task>()
        val cancelled = mutableListOf<String>()
        override fun schedule(task: Task) { scheduled += task }
        override fun cancel(taskId: String) { cancelled += taskId }
    }

    private val recorder = Recorder()
    private val store by lazy { TaskStore(folder.root) }
    private val env by lazy { TasksEnvironment(store = store, scheduling = recorder, now = { now }, zone = zone) }

    private fun call(name: String, input: String): CapabilityExecutionResult = runBlocking {
        TasksTools.registry(env).execute(CapabilityInvocation(toolCallId = "t", name = name, input = input))
    }

    private fun ok(name: String, input: String): String {
        val result = call(name, input)
        assertFalse(result.output.text, result.isError)
        return result.output.text
    }

    private fun fails(name: String, input: String): String {
        val result = call(name, input)
        assertTrue("应当失败：${result.output.text}", result.isError)
        return result.output.text
    }

    // ---- 现在几点 ----

    @Test
    fun getCurrentTimeGivesDateWeekdayTimeAndZone() {
        assertEquals("现在是 2026-09-29 14:03 星期二（Asia/Shanghai）。", ok("get_current_time", "{}"))
    }

    // ---- 提醒 ----

    @Test
    fun aReminderWithAnExplicitTimeIsStoredScheduledAndReadBackToTheUser() {
        val text = ok("create_reminder", """{"text":"给妈妈打电话","at":"2026-09-29T20:00"}""")

        assertTrue(text, text.contains("给妈妈打电话"))
        assertTrue(text, text.contains("今天 20:00"))
        assertTrue("要坦白提醒可能晚几分钟", text.contains("晚几分钟"))
        val task = store.active().single()
        assertEquals(TaskKind.REMINDER, task.kind)
        assertEquals(Instant.parse("2026-09-29T12:00:00Z"), task.dueAt)
        assertEquals(listOf(task.id), recorder.scheduled.map { it.id })
    }

    @Test
    fun inMinutesIsRelativeToNow() {
        ok("create_reminder", """{"text":"喝水","in_minutes":45}""")
        assertEquals(Instant.parse("2026-09-29T06:48:00Z"), store.active().single().dueAt)
    }

    @Test
    fun repeatingRemindersAreLabelledInTheAnswer() {
        val text = ok("create_reminder", """{"text":"吃药前先量一下","at":"2026-09-30T08:00","repeat":"daily"}""")
        assertTrue(text, text.contains("每天"))
        assertEquals(Repeat.DAILY, store.active().single().repeat)
    }

    @Test
    fun aTimeInThePastOrTooCloseIsRefusedWithAHintToCheckTheClock() {
        val past = fails("create_reminder", """{"text":"x","at":"2026-09-29T09:00"}""")
        assertTrue(past, past.contains("get_current_time"))
        fails("create_reminder", """{"text":"x","in_minutes":0}""".replace("\"in_minutes\":0", "\"at\":\"2026-09-29T14:03\""))
        assertTrue(store.all().isEmpty())
        assertTrue(recorder.scheduled.isEmpty())
    }

    @Test
    fun aMissingOrUnparsableTimeIsRefused() {
        assertTrue(fails("create_reminder", """{"text":"x"}""").contains("at"))
        assertTrue(fails("create_reminder", """{"text":"x","at":"明天晚上八点"}""").contains("看不懂"))
    }

    @Test
    fun aTimeMoreThanAYearAwayIsRefused() {
        assertTrue(fails("create_reminder", """{"text":"x","at":"2028-01-01T09:00"}""").contains("一年"))
    }

    @Test
    fun anEmptyReminderTextIsRefused() {
        fails("create_reminder", """{"text":"  ","at":"2026-09-29T20:00"}""")
    }

    @Test
    fun theNumberOfActiveRemindersIsCapped() {
        repeat(ReminderRules.MAX_ACTIVE_REMINDERS) {
            store.add(Task(kind = TaskKind.REMINDER, title = "r$it", status = TaskStatus.QUEUED, dueAt = now))
        }
        assertTrue(fails("create_reminder", """{"text":"再一条","at":"2026-09-29T20:00"}""").contains("${ReminderRules.MAX_ACTIVE_REMINDERS}"))
    }

    // ---- 列表 ----

    @Test
    fun listShowsRemindersAndGoalsWithHandlesAndNothingWhenEmpty() {
        assertTrue(ok("list_tasks", "{}").contains("没有进行中"))
        ok("create_reminder", """{"text":"交电费","at":"2026-09-29T20:00"}""")
        ok("create_goal", """{"title":"备半马","plan":["每周三次","买跑鞋"]}""")

        val text = ok("list_tasks", "{}")

        assertTrue(text, text.contains("提醒："))
        assertTrue(text, text.contains("今天 20:00 · 交电费"))
        assertTrue(text, text.contains("目标："))
        assertTrue(text, text.contains("备半马 · 步骤 0/2"))
        assertFalse("只看某一种", ok("list_tasks", """{"kind":"goal"}""").contains("交电费"))
    }

    // ---- 改 ----

    @Test
    fun completingOrCancellingAReminderUnschedulesIt() {
        ok("create_reminder", """{"text":"甲","at":"2026-09-29T20:00"}""")
        ok("create_reminder", """{"text":"乙","at":"2026-09-29T21:00"}""")
        val (a, b) = store.active()

        ok("update_task", """{"id":"${a.handle}","action":"complete"}""")
        ok("update_task", """{"id":"${b.handle}","action":"cancel"}""")

        assertEquals(TaskStatus.DONE, store.get(a.id)!!.status)
        assertEquals(TaskStatus.CANCELLED, store.get(b.id)!!.status)
        assertEquals(listOf(a.id, b.id), recorder.cancelled)
        assertTrue(store.active().isEmpty())
    }

    @Test
    fun reschedulingMovesTheReminderAndSchedulesItAgain() {
        ok("create_reminder", """{"text":"甲","at":"2026-09-29T20:00"}""")
        val task = store.active().single()

        ok("update_task", """{"id":"${task.handle}","action":"reschedule","at":"2026-09-30T09:00"}""")

        assertEquals(Instant.parse("2026-09-30T01:00:00Z"), store.get(task.id)!!.dueAt)
        assertEquals(2, recorder.scheduled.size)
    }

    @Test
    fun onlyRemindersCanBeRescheduledAndUnknownOrFinishedItemsAreRefused() {
        ok("create_goal", """{"title":"备半马"}""")
        val goal = store.active().single()
        assertTrue(fails("update_task", """{"id":"${goal.handle}","action":"reschedule","at":"2026-09-30T09:00"}""").contains("提醒"))
        assertTrue(fails("update_task", """{"id":"zzzzzzzz","action":"cancel"}""").contains("list_tasks"))

        ok("update_task", """{"id":"${goal.handle}","action":"complete"}""")
        assertTrue(fails("update_task", """{"id":"${goal.handle}","action":"cancel"}""").contains("已完成"))
    }

    // ---- 目标 ----

    @Test
    fun aGoalIsCreatedWithItsPlanAndAtMostFiveAreActive() {
        val text = ok("create_goal", """{"title":"备半马","why":"想跑完一次比赛","plan":["每周三次","买跑鞋"]}""")
        assertTrue(text, text.contains("步骤 0/2"))
        val goal = store.active().single()
        assertEquals("想跑完一次比赛", goal.why)
        assertEquals(TaskStatus.RUNNING, goal.status)

        (2..TasksTools.MAX_ACTIVE_GOALS).forEach { ok("create_goal", """{"title":"目标$it"}""") }
        assertTrue(fails("create_goal", """{"title":"第六个"}""").contains("${TasksTools.MAX_ACTIVE_GOALS}"))
    }

    @Test
    fun aDuplicateActiveGoalPointsBackToUpdateGoal() {
        ok("create_goal", """{"title":"备半马"}""")
        assertTrue(fails("create_goal", """{"title":"备半马"}""").contains("update_goal"))
    }

    @Test
    fun updateGoalAddsAndTicksPlanItemsAndRecordsANote() {
        ok("create_goal", """{"title":"备半马","plan":["每周三次","买跑鞋"]}""")
        val goal = store.active().single()

        val text = ok(
            "update_goal",
            """{"id":"${goal.handle}","add_plan":["报名比赛"],"complete_plan":["跑鞋"],"note":"这周跑了三次"}""",
        )

        assertTrue(text, text.contains("步骤 1/3"))
        val updated = store.get(goal.id)!!
        assertEquals(listOf(false, true, false), updated.plan.map { it.done })
        assertEquals("这周跑了三次", updated.notes.single().text)
    }

    @Test
    fun ticksThatMatchNothingAreReportedRatherThanSilentlyIgnored() {
        ok("create_goal", """{"title":"备半马","plan":["每周三次"]}""")
        val goal = store.active().single()
        val text = ok("update_goal", """{"id":"${goal.handle}","complete_plan":["不存在的步骤"]}""")
        assertTrue(text, text.contains("没找到这几个步骤"))
    }

    @Test
    fun updateGoalRefusesAnIdThatIsNotAGoal() {
        ok("create_reminder", """{"text":"甲","at":"2026-09-29T20:00"}""")
        val reminder = store.active().single()
        assertTrue(fails("update_goal", """{"id":"${reminder.handle}","note":"x"}""").contains("没有找到"))
    }

    @Test
    fun anUnknownToolIsAnError() {
        assertTrue(fails("nonsense", "{}").contains("不支持"))
    }
}
