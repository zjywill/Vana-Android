package com.pinapia.vana.tasks

import com.pinapia.vana.agent.AgentEngine
import com.pinapia.vana.agentruntime.AgentChatMessageDTO
import com.pinapia.vana.agentruntime.AgentPendingInputProvider
import com.pinapia.vana.agentruntime.AgentToolOutput
import com.pinapia.vana.agentruntime.AgentTurnEvent
import com.pinapia.vana.agentruntime.RuntimeJSONValue
import com.pinapia.vana.agentruntime.ToolCallRecordDTO
import com.pinapia.vana.session.ChatMessage
import com.pinapia.vana.session.ToolCallRecord
import java.io.File
import java.time.ZoneId
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SubagentResultTest {
    @Test
    fun theFirstParagraphIsTheSummaryAndTheRestIsTheBody() {
        val result = SubagentResult.parse("三款里 B 最合适。\n\n详细：A 太吵，C 太贵。")!!
        assertEquals("三款里 B 最合适。", result.summary)
        assertEquals("详细：A 太吵，C 太贵。", result.body)
        assertTrue(result.sources.isEmpty())
    }

    @Test
    fun aTrailingSourcesParagraphIsSplitIntoOneEntryPerLine() {
        val result = SubagentResult.parse("结论。\n\n正文一段。\n\n来源：\n- https://a.example\n- https://b.example")!!
        assertEquals("正文一段。", result.body)
        assertEquals(listOf("https://a.example", "https://b.example"), result.sources)
    }

    @Test
    fun aSourcesHeaderWithTheFirstEntryOnTheSameLineIsUnderstood() {
        val result = SubagentResult.parse("结论。\n\n来源：https://a.example\nhttps://b.example")!!
        assertEquals(listOf("https://a.example", "https://b.example"), result.sources)
    }

    @Test
    fun aVeryLongFirstParagraphIsClippedButNothingIsLost() {
        val first = "很长的一句话。".repeat(60)
        val result = SubagentResult.parse(first)!!
        assertTrue(result.summary.length <= 160)
        assertEquals("被截掉的部分要并回详细内容", first, result.body)
    }

    @Test
    fun blankTextIsNoResultAndProposalsRideAlong() {
        assertNull(SubagentResult.parse("   \n "))
        val proposal = TaskProposal(kind = "goal", text = "学吉他")
        assertEquals(listOf(proposal), SubagentResult.parse("好了。", listOf(proposal))!!.proposals)
    }
}

class SubagentLimitsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = Instant.parse("2026-09-29T04:00:00Z")

    private fun job(status: TaskStatus, startedAt: Instant? = null) =
        Task(kind = TaskKind.JOB, title = "x", status = status, brief = "b", startedAt = startedAt)

    @Test
    fun anEmptyStoreHasRoom() {
        assertNull(SubagentLimits.problem(TaskStore(folder.root), now, zone))
    }

    @Test
    fun queuedAndRunningJobsCountAgainstTheQueueLimit() {
        val store = TaskStore(folder.root)
        store.add(job(TaskStatus.QUEUED)); store.add(job(TaskStatus.RUNNING)); store.add(job(TaskStatus.PROPOSED))
        assertNull("只是等确认的不占位", SubagentLimits.problem(store, now, zone))
        store.add(job(TaskStatus.QUEUED))
        assertNotNull(SubagentLimits.problem(store, now, zone))
    }

    @Test
    fun theDailyRunLimitCountsTodaysStartsButNotYesterdays() {
        val store = TaskStore(folder.root)
        repeat(SubagentLimits.MAX_RUNS_PER_DAY - 1) { store.add(job(TaskStatus.DONE, startedAt = now - 1.hours)) }
        repeat(5) { store.add(job(TaskStatus.DONE, startedAt = now - 2.days)) }
        assertNull(SubagentLimits.problem(store, now, zone))
        store.add(job(TaskStatus.DONE, startedAt = now - 2.hours))
        assertTrue(SubagentLimits.problem(store, now, zone)!!.contains("今天"))
    }
}

private class FakeEngine(
    private val body: suspend (kotlinx.coroutines.flow.FlowCollector<AgentTurnEvent>) -> Unit,
) : AgentEngine {
    override val name = "fake"
    override fun reply(history: List<ChatMessage>, pendingInput: AgentPendingInputProvider?): Flow<AgentTurnEvent> =
        flow { body(this) }
}

class SubagentRunnerTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val now = Clock.System.now()

    private fun store() = TaskStore(folder.newFolder())

    private fun queued(store: TaskStore) = store.add(
        Task(kind = TaskKind.JOB, title = "比较空气净化器", status = TaskStatus.QUEUED, brief = "比较三款，给结论。"),
    )

    private fun runner(store: TaskStore, body: suspend (kotlinx.coroutines.flow.FlowCollector<AgentTurnEvent>) -> Unit) =
        SubagentRunner(store, engineFor = { FakeEngine(body) }, now = { now })

    @Test
    fun aSuccessfulRunRecordsStepsResultAndUsage() = runBlocking {
        val previous = java.util.Locale.getDefault()
        java.util.Locale.setDefault(java.util.Locale.SIMPLIFIED_CHINESE)
        try { successfulRun() } finally { java.util.Locale.setDefault(previous) }
    }

    private suspend fun successfulRun() {
        val store = store()
        val task = queued(store)
        val outcome = runner(store) { out ->
            out.emit(AgentTurnEvent.ToolCallStarted(ToolCallRecordDTO(id = "1", name = "web_search", input = "{\"query\":\"净化器\"}")))
            out.emit(AgentTurnEvent.ToolCallFinished("1", AgentToolOutput(AgentToolOutput.Kind.TEXT, "结果一二三"), isError = false))
            out.emit(AgentTurnEvent.TextDelta("B 最合适。\n\n详细内容。\n\n来源：\n- https://a.example"))
        }.run(task.id)

        val done = (outcome as SubagentRunner.Outcome.Done).task
        assertEquals(TaskStatus.DONE, done.status)
        assertEquals("B 最合适。", done.result!!.summary)
        assertEquals(listOf("https://a.example"), done.result!!.sources)
        assertEquals(1, done.steps.size)
        assertEquals("搜索了网页", done.steps.single().label)
        assertEquals(1, done.attempts)
        assertNotNull(done.startedAt)
        assertTrue(done.tokensUsed > 0)
        assertEquals(TaskStatus.DONE, store.get(task.id)!!.status)
    }

    @Test
    fun onlyAQueuedJobRuns() = runBlocking {
        val store = store()
        val proposed = store.add(Task(kind = TaskKind.JOB, title = "x", status = TaskStatus.PROPOSED, brief = "b"))
        assertEquals(SubagentRunner.Outcome.Skipped, runner(store) { error("不该被调用") }.run(proposed.id))
        assertEquals(SubagentRunner.Outcome.Skipped, runner(store) { error("不该被调用") }.run("不存在"))
    }

    @Test
    fun aModelErrorFailsTheJobAndKeepsTheReason() = runBlocking {
        val store = store()
        val task = queued(store)
        val outcome = runner(store) { throw IllegalStateException("上游炸了") }.run(task.id)
        val failed = (outcome as SubagentRunner.Outcome.Failed).task
        assertEquals(TaskStatus.FAILED, failed.status)
        assertTrue(failed.error!!.contains("上游炸了"))
    }

    @Test
    fun anEmptyReplyIsAFailureNotAnEmptyResult() = runBlocking {
        val store = store()
        val task = queued(store)
        val outcome = runner(store) { }.run(task.id)
        assertEquals(TaskStatus.FAILED, (outcome as SubagentRunner.Outcome.Failed).task.status)
    }

    @Test
    fun overTheEstimatedTokenBudgetItStopsAndFails() = runBlocking {
        val store = store()
        val task = queued(store)
        val outcome = runner(store) { out ->
            repeat(10) { out.emit(AgentTurnEvent.TextDelta("字".repeat(30_000))) }
        }.run(task.id)
        val failed = (outcome as SubagentRunner.Outcome.Failed).task
        assertTrue(failed.error!!.contains("预算"))
    }

    @Test
    fun overTheWallClockItFails() = runTest {
        val store = store()
        val task = queued(store)
        val deferred = async { runner(store) { delay(SubagentLimits.MAX_WALL_CLOCK + 1.minutes) }.run(task.id) }
        advanceTimeBy((SubagentLimits.MAX_WALL_CLOCK + 2.minutes).inWholeMilliseconds)
        runCurrent()
        val failed = (deferred.await() as SubagentRunner.Outcome.Failed).task
        assertTrue(failed.error!!.contains("分钟"))
    }

    @Test
    fun aStopDuringTheRunLeavesTheStatusToWhoeverStoppedIt() = runBlocking {
        val store = store()
        val task = queued(store)
        val job = kotlinx.coroutines.GlobalScope.async { runner(store) { delay(60_000) }.run(task.id) }
        while (store.get(task.id)!!.status != TaskStatus.RUNNING) delay(5)
        job.cancel()
        try {
            job.await()
        } catch (_: CancellationException) {
        }
        assertEquals("取消不由 runner 改状态", TaskStatus.RUNNING, store.get(task.id)!!.status)
    }

    @Test
    fun proposalsCollectedDuringTheRunLandInTheResult() = runBlocking {
        val store = store()
        val task = queued(store)
        val proposal = TaskProposal(kind = "goal", text = "买净化器")
        val runner = SubagentRunner(store, engineFor = { collector ->
            FakeEngine { out ->
                collector.add(proposal)
                out.emit(AgentTurnEvent.TextDelta("好了。"))
            }
        }, now = { now })
        val done = (runner.run(task.id) as SubagentRunner.Outcome.Done).task
        assertEquals(listOf(proposal), done.result!!.proposals)
    }

    @Test
    fun retryingClearsTheOldOutcomeAndCountsAnotherAttempt() = runBlocking {
        val store = store()
        val task = queued(store)
        runner(store) { throw IllegalStateException("第一次失败") }.run(task.id)
        store.update(task.id) { it.copy(status = TaskStatus.QUEUED) }
        val done = (runner(store) { it.emit(AgentTurnEvent.TextDelta("这次好了。")) }.run(task.id) as SubagentRunner.Outcome.Done).task
        assertEquals(2, done.attempts)
        assertNull(done.error)
    }
}

class SubagentToolsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private class FakeJobs(override val autoStart: Boolean = false) : JobControls {
        val started = mutableListOf<String>()
        override fun start(taskId: String) { started += taskId }
        override fun stop(taskId: String) = Unit
        override fun decide(taskId: String, proposalId: String, accept: Boolean) = Unit
    }

    private fun env(jobs: JobControls? = FakeJobs()) =
        TasksEnvironment(store = TaskStore(folder.newFolder()), jobs = jobs)

    private fun call(registry: com.pinapia.vana.agentruntime.CapabilityRegistry, name: String, json: String) = runBlocking {
        registry.execute(com.pinapia.vana.agentruntime.CapabilityInvocation(toolCallId = "c", name = name, input = json))
    }

    @Test
    fun startTaskLeavesAProposedJobAndPointsTheCardAtIt() {
        val env = env()
        val result = call(SubagentTools.startTaskRegistry(env), "start_task", """{"title":"比较净化器","brief":"比较三款"}""")
        assertFalse(result.isError)
        val job = env.store.byKind(TaskKind.JOB).single()
        assertEquals(TaskStatus.PROPOSED, job.status)
        assertEquals(job.id, result.output.metadata!![SubagentTools.TASK_ID_KEY]!!.stringValue)
        assertTrue("默认要用户点了才跑", (env.jobs as FakeJobs).started.isEmpty())
        assertTrue(result.output.text.contains("确认卡"))
    }

    @Test
    fun withAutoStartOnTheJobStartsRightAway() {
        val env = env(FakeJobs(autoStart = true))
        call(SubagentTools.startTaskRegistry(env), "start_task", """{"title":"t","brief":"b"}""")
        assertEquals(listOf(env.store.byKind(TaskKind.JOB).single().id), (env.jobs as FakeJobs).started)
    }

    @Test
    fun startTaskRefusesWhatTheLimitsForbid() {
        val env = env()
        val registry = SubagentTools.startTaskRegistry(env)
        assertTrue(call(registry, "start_task", """{"title":"","brief":"b"}""").isError)
        assertTrue(call(registry, "start_task", """{"title":"t","brief":"${"长".repeat(SubagentLimits.MAX_BRIEF_CHARS + 1)}"}""").isError)
        repeat(SubagentLimits.MAX_QUEUED) {
            env.store.add(Task(kind = TaskKind.JOB, title = "x", status = TaskStatus.QUEUED, brief = "b"))
        }
        val result = call(registry, "start_task", """{"title":"t","brief":"b"}""")
        assertTrue(result.isError)
        assertEquals(SubagentLimits.MAX_QUEUED, env.store.byKind(TaskKind.JOB).size)
    }

    @Test
    fun theToolCallRecordExposesTheTaskIdOnlyForStartTask() {
        val meta = RuntimeJSONValue.ObjectValue(mapOf(SubagentTools.TASK_ID_KEY to RuntimeJSONValue.StringValue("abc")))
        assertEquals("abc", ToolCallRecord(id = "1", name = "start_task", input = "{}", metadata = meta).taskId)
        assertNull(ToolCallRecord(id = "1", name = "web_search", input = "{}", metadata = meta).taskId)
    }

    @Test
    fun proposeActionCollectsValidProposalsAndRejectsBadOnes() {
        val zone = ZoneId.of("Asia/Shanghai")
        val collector = ProposalCollector()
        val now = Instant.parse("2026-09-29T02:00:00Z")
        val registry = SubagentTools.proposeRegistry(collector, zone) { now }

        assertFalse(call(registry, "propose_action", """{"kind":"goal","text":"学吉他"}""").isError)
        assertFalse(call(registry, "propose_action", """{"kind":"reminder","text":"买滤芯","at":"2026-10-01T20:00"}""").isError)
        assertTrue("提醒没有时间", call(registry, "propose_action", """{"kind":"reminder","text":"买滤芯"}""").isError)
        assertTrue("时间已过", call(registry, "propose_action", """{"kind":"reminder","text":"x","at":"2026-09-01T20:00"}""").isError)
        assertTrue("不认识的类型", call(registry, "propose_action", """{"kind":"delete","text":"x"}""").isError)
        assertEquals(2, collector.all().size)

        repeat(ProposalCollector.MAX) { call(registry, "propose_action", """{"kind":"memory","text":"条$it"}""") }
        assertEquals(ProposalCollector.MAX, collector.all().size)
    }
}

class GoalDigestTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val now = Instant.parse("2026-09-29T02:00:00Z")

    private fun goal(digest: Boolean = true, created: Instant = now - 10.days, last: Instant? = null) = Task(
        kind = TaskKind.GOAL, title = "备半马", status = TaskStatus.RUNNING, why = "跑完一次",
        plan = listOf(PlanItem(text = "买鞋", done = true), PlanItem(text = "报名")),
        notes = listOf(GoalNote(now - 2.days, "跑了 5 公里")),
        digestEnabled = digest, createdAt = created, lastDigestAt = last,
    )

    @Test
    fun aGoalIsDueSevenDaysAfterItWasCreatedOrLastReviewed() {
        assertEquals(1, GoalDigest.due(listOf(goal()), now).size)
        assertEquals(0, GoalDigest.due(listOf(goal(created = now - 3.days)), now).size)
        assertEquals(0, GoalDigest.due(listOf(goal(last = now - 3.days)), now).size)
        assertEquals(1, GoalDigest.due(listOf(goal(last = now - 8.days)), now).size)
    }

    @Test
    fun aGoalWithoutTheSwitchIsNeverReviewed() {
        assertTrue(GoalDigest.due(listOf(goal(digest = false)), now).isEmpty())
    }

    @Test
    fun theBriefCarriesEverythingTheIsolatedAssistantNeeds() {
        val brief = GoalDigest.brief(goal(), now)
        listOf("备半马", "跑完一次", "[x] 买鞋", "[ ] 报名", "跑了 5 公里").forEach { assertTrue("brief 里该有「$it」", brief.contains(it)) }
        assertTrue(brief.length <= SubagentLimits.MAX_BRIEF_CHARS)
    }

    @Test
    fun enqueueingQueuesAJobAndMarksTheGoalSoItIsNotReviewedTwice() = runBlocking {
        val store = TaskStore(folder.newFolder())
        val g = store.add(goal())
        val writer = com.pinapia.vana.thread.ThreadWriter(com.pinapia.vana.thread.ThreadStore(folder.newFolder()))
        GoalDigest.enqueueDue(store, now, writer)
        GoalDigest.enqueueDue(store, now, writer)
        val jobs = store.byKind(TaskKind.JOB)
        assertEquals(1, jobs.size)
        assertEquals(TaskStatus.QUEUED, jobs.single().status)
        assertTrue(jobs.single().title.startsWith("本周回顾"))
        assertEquals(now, store.get(g.id)!!.lastDigestAt)
    }
}

class TaskActionsDecideTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val now = Instant.parse("2026-09-29T02:00:00Z")

    private class Recorder : ReminderScheduling {
        val scheduled = mutableListOf<Task>()
        override fun schedule(task: Task) { scheduled += task }
        override fun cancel(taskId: String) = Unit
    }

    private fun setup(vararg proposals: TaskProposal): Triple<TasksEnvironment, Task, Recorder> {
        val recorder = Recorder()
        val env = TasksEnvironment(store = TaskStore(folder.newFolder()), scheduling = recorder, now = { now }, zone = ZoneId.of("Asia/Shanghai"))
        val job = env.store.add(
            Task(kind = TaskKind.JOB, title = "查", status = TaskStatus.DONE, brief = "b", result = TaskResult(summary = "好", proposals = proposals.toList())),
        )
        return Triple(env, job, recorder)
    }

    private fun statusOf(env: TasksEnvironment, job: Task, proposal: TaskProposal) =
        env.store.get(job.id)!!.result!!.proposals.first { it.id == proposal.id }.status

    @Test
    fun acceptingAReminderProposalSchedulesARealReminder() {
        val p = TaskProposal(kind = "reminder", text = "买滤芯", at = now + 1.days)
        val (env, job, recorder) = setup(p)
        assertNull(TaskActions.decide(env, null, job.id, p.id, accept = true))
        assertEquals(1, env.store.byKind(TaskKind.REMINDER).size)
        assertEquals(1, recorder.scheduled.size)
        assertEquals(ProposalStatus.ACCEPTED, statusOf(env, job, p))
    }

    @Test
    fun aReminderProposalWhoseTimeHasPassedCannotBeAppliedAndIsDismissed() {
        val p = TaskProposal(kind = "reminder", text = "过期", at = now - 1.hours)
        val (env, job, _) = setup(p)
        assertNotNull(TaskActions.decide(env, null, job.id, p.id, accept = true))
        assertTrue(env.store.byKind(TaskKind.REMINDER).isEmpty())
        assertEquals(ProposalStatus.DISMISSED, statusOf(env, job, p))
    }

    @Test
    fun acceptingAMemoryProposalStoresItAsTheUsersOwn() {
        val p = TaskProposal(kind = "memory", text = "他偏好静音的家电")
        val (env, job, _) = setup(p)
        val memory = com.pinapia.vana.memory.MemoryStore(folder.newFolder())
        assertNull(TaskActions.decide(env, memory, job.id, p.id, accept = true))
        val saved = memory.load().single()
        assertEquals("他偏好静音的家电", saved.text)
        assertEquals(com.pinapia.vana.memory.MemoryItem.Origin.MANUAL, saved.origin)
    }

    @Test
    fun skippingWritesNothingAndDecidingTwiceIsANoOp() {
        val p = TaskProposal(kind = "goal", text = "学吉他")
        val (env, job, _) = setup(p)
        assertNull(TaskActions.decide(env, null, job.id, p.id, accept = false))
        assertTrue(env.store.byKind(TaskKind.GOAL).isEmpty())
        assertEquals(ProposalStatus.DISMISSED, statusOf(env, job, p))
        assertNull(TaskActions.decide(env, null, job.id, p.id, accept = true))
        assertTrue("已经决定过的不能再被「照做」", env.store.byKind(TaskKind.GOAL).isEmpty())
    }

    @Test
    fun aMemoryProposalCannotBeAppliedWhileMemoryIsOff() {
        val p = TaskProposal(kind = "memory", text = "x")
        val (env, job, _) = setup(p)
        assertNotNull(TaskActions.decide(env, null, job.id, p.id, accept = true))
        assertEquals(ProposalStatus.DISMISSED, statusOf(env, job, p))
    }
}
