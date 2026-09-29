package com.pinapia.vana.today

import com.pinapia.vana.tasks.ReminderRules
import com.pinapia.vana.tasks.Task
import com.pinapia.vana.tasks.TaskKind
import com.pinapia.vana.tasks.TaskStatus
import com.pinapia.vana.ui.L10n
import java.util.Locale

private fun english(): Boolean = Locale.getDefault().language.equals("en", ignoreCase = true)

/**
 * 核心贡献的「今天」卡片:到点/错过的提醒、等你确认的后台任务、在推进的目标、说好回头看的事。
 * 全部由本机数据拼出来,不调模型。
 */
object CoreToday {
    private const val MAX_GOAL_CARDS = 2

    fun cards(context: TodayContext): List<TodayCard> = buildList {
        val en = english()
        val endOfDay = ReminderRules.endOfDay(context.now, context.zone)

        for (task in context.tasks.filter { it.kind == TaskKind.REMINDER && it.isActive }) {
            val due = task.dueAt ?: continue
            if (due > endOfDay) continue
            val overdue = due < context.now
            add(
                TodayCard(
                    id = "reminder-${task.id}",
                    pluginId = "core",
                    priority = if (overdue) TodayPriority.OVERDUE_REMINDER else TodayPriority.DUE_TODAY_REMINDER,
                    title = task.title,
                    body = (if (overdue) L10n.text("已过点 · ", "Past due · ") else "") +
                        ReminderRules.describe(due, context.now, context.zone, en),
                    action = TodayAction.OpenTask(task.id),
                ),
            )
        }

        for (task in context.tasks.filter { it.kind == TaskKind.JOB && it.isActive }) {
            val (priority, body) = when (task.status) {
                TaskStatus.NEEDS_YOU, TaskStatus.PROPOSED ->
                    TodayPriority.NEEDS_YOU to L10n.text("等你确认", "Waiting for you")
                TaskStatus.RUNNING ->
                    TodayPriority.RUNNING to L10n.text("进行中", "In progress")
                else -> TodayPriority.RUNNING to L10n.text("排队中", "Queued")
            }
            add(
                TodayCard(
                    id = "job-${task.id}",
                    pluginId = "core",
                    priority = priority,
                    title = task.title,
                    body = body,
                    action = TodayAction.OpenTask(task.id),
                ),
            )
        }

        for (task in context.tasks.filter { it.kind == TaskKind.GOAL && it.isActive }.take(MAX_GOAL_CARDS)) {
            add(
                TodayCard(
                    id = "goal-${task.id}",
                    pluginId = "core",
                    priority = TodayPriority.GOAL,
                    title = task.title,
                    body = goalProgress(task),
                    action = TodayAction.OpenTask(task.id),
                ),
            )
        }

        for (item in context.dueFollowUps.take(2)) {
            add(
                TodayCard(
                    id = "followup-${item.id}",
                    pluginId = "core",
                    priority = TodayPriority.FOLLOW_UP_DUE,
                    title = L10n.text("说好回头看：", "Circling back: ") + item.text,
                    action = TodayAction.Ask(
                        L10n.text(
                            "上次说的「${item.text}」，现在怎么样了？",
                            "About \"${item.text}\" — how is it going now?",
                        ),
                    ),
                ),
            )
        }
    }

    private fun goalProgress(task: Task): String =
        if (task.plan.isEmpty()) {
            L10n.text("还没有步骤", "No steps yet")
        } else {
            L10n.text(
                "步骤 ${task.plan.count { it.done }}/${task.plan.size}",
                "Steps ${task.plan.count { it.done }}/${task.plan.size}",
            )
        }

    /** 界面上给状态一个标签。 */
    fun statusLabel(status: TaskStatus): String = when (status) {
        TaskStatus.PROPOSED -> L10n.text("等你确认", "Waiting for you")
        TaskStatus.QUEUED -> L10n.text("排队中", "Queued")
        TaskStatus.RUNNING -> L10n.text("进行中", "In progress")
        TaskStatus.NEEDS_YOU -> L10n.text("需要你", "Needs you")
        TaskStatus.DONE -> L10n.text("已完成", "Done")
        TaskStatus.FAILED -> L10n.text("失败了", "Failed")
        TaskStatus.CANCELLED -> L10n.text("已取消", "Cancelled")
    }
}

/** 健康插件的卡片:到了该回头看的用药(`followUpAt` 已到的)。 */
object HealthToday {
    fun cards(context: TodayContext): List<TodayCard> = context.medications.due(context.now).take(2).map { item ->
        TodayCard(
            id = "medication-${item.id}",
            pluginId = "health",
            priority = TodayPriority.FOLLOW_UP_DUE,
            title = L10n.text("回头看看：${item.name}", "Check back on ${item.name}"),
            body = L10n.text("说好这几天回头评价一下效果", "You planned to review how it's working"),
            action = TodayAction.OpenSurface("medications"),
        )
    }
}
