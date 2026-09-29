package com.pinapia.vana.tasks

import com.pinapia.vana.thread.ThreadWriter
import kotlin.time.Duration.Companion.days
import kotlinx.datetime.Instant

/**
 * 目标的每周回顾:开了「每周回顾」的目标,每隔七天派一件后台任务去看看进展。
 *
 * 它和用户手动派的任务是同一种东西(同样的确认在哪儿?——在**他打开这个开关的那一刻**:开关的说明里写明了
 * 会在后台请模型看一眼目标的进展、内容会发给模型服务),所以到点直接排队,不再弹卡。
 * 目标的内容是写进 brief 里的——后台助手不带主窗口,它只知道 brief 里有什么。
 */
object GoalDigest {
    val INTERVAL = 7.days

    fun due(goals: List<Task>, now: Instant): List<Task> = goals.filter { goal ->
        goal.kind == TaskKind.GOAL && goal.status == TaskStatus.RUNNING && goal.digestEnabled &&
            (goal.lastDigestAt?.let { now - it >= INTERVAL } ?: (now - goal.createdAt >= INTERVAL))
    }

    fun brief(goal: Task, now: Instant): String = buildString {
        appendLine("回顾一个用户正在推进的目标，写三四句话：这段时间做了什么、卡在哪、下周建议他做的一个小步。不要空洞的鼓励，不要重复他已经知道的。")
        appendLine()
        appendLine("目标：${goal.title}")
        if (goal.why.isNotBlank()) appendLine("原因：${goal.why}")
        if (goal.plan.isNotEmpty()) {
            appendLine("步骤：")
            goal.plan.forEach { appendLine("- [${if (it.done) "x" else " "}] ${it.text}") }
        }
        val recent = goal.notes.takeLast(6)
        if (recent.isNotEmpty()) {
            appendLine("最近的进展记录：")
            recent.forEach { appendLine("- ${it.text}") }
        }
        appendLine("开始于：${goal.createdAt.toString().take(10)}，现在是 ${now.toString().take(10)}。")
        append("可以用记忆和过往对话补充背景。如果建议他设一个提醒或加一个步骤，用 ${SubagentTools.PROPOSE_ACTION} 提议。")
    }.take(SubagentLimits.MAX_BRIEF_CHARS)

    /** 到点的目标各排一件回顾任务。受排队和每日次数的限制,超了这次就先不排(下次打开再看)。 */
    @Suppress("UNUSED_PARAMETER")
    suspend fun enqueueDue(store: TaskStore, now: Instant, writer: ThreadWriter) {
        for (goal in due(store.byKind(TaskKind.GOAL), now)) {
            val zone = java.time.ZoneId.systemDefault()
            if (SubagentLimits.problem(store, now, zone) != null) return
            store.add(
                Task(
                    kind = TaskKind.JOB,
                    title = "本周回顾：${goal.title}".take(TasksTools.MAX_TITLE_CHARS),
                    status = TaskStatus.QUEUED,
                    brief = brief(goal, now),
                ),
            )
            store.update(goal.id, now) { it.copy(lastDigestAt = now) }
        }
    }
}
