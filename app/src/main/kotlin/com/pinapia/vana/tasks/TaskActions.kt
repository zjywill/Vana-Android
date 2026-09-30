package com.pinapia.vana.tasks

import com.pinapia.vana.ui.L10n
import kotlinx.datetime.Instant

/**
 * 用户在「任务」页上手动做的事。和模型那边的工具([TasksTools])守同一批上限——两条路进来的东西
 * 在盘上长得一样,也一样会被闹钟排上。返回 null 表示成功,否则是要给用户看的原因。
 */
object TaskActions {
    fun addReminder(env: TasksEnvironment, title: String, due: Instant, repeat: Repeat): String? {
        val text = title.trim()
        if (text.isEmpty()) return L10n.text("写一下要提醒什么", "Say what to be reminded about")
        val now = env.now()
        if (due.toEpochMilliseconds() < now.toEpochMilliseconds() + 30_000L) {
            return L10n.text("这个时间已经过了，选一个之后的时间", "That time has passed — pick a later time")
        }
        if (due.toEpochMilliseconds() > now.toEpochMilliseconds() + ReminderRules.MAX_HORIZON_DAYS * 86_400_000L) {
            return L10n.text("最远只能约到一年以内", "Reminders can be set up to a year ahead")
        }
        if (env.store.active().count { it.kind == TaskKind.REMINDER } >= ReminderRules.MAX_ACTIVE_REMINDERS) {
            return L10n.text(
                "进行中的提醒已经有 ${ReminderRules.MAX_ACTIVE_REMINDERS} 条了，先清理一些",
                "You already have ${ReminderRules.MAX_ACTIVE_REMINDERS} active reminders — clear some first",
            )
        }
        val task = env.store.add(
            Task(kind = TaskKind.REMINDER, title = text.take(TasksTools.MAX_TITLE_CHARS * 2), status = TaskStatus.QUEUED, dueAt = due, repeat = repeat),
        )
        env.scheduling.schedule(task)
        return null
    }

    fun addGoal(env: TasksEnvironment, title: String, why: String): String? {
        val name = title.trim()
        if (name.isEmpty()) return L10n.text("写一下目标是什么", "Say what the goal is")
        if (name.length > TasksTools.MAX_TITLE_CHARS) return L10n.text("名称太长了，短一点", "That name is too long")
        val active = env.store.active().filter { it.kind == TaskKind.GOAL }
        if (active.size >= TasksTools.MAX_ACTIVE_GOALS) {
            return L10n.text(
                "进行中的目标已经有 ${TasksTools.MAX_ACTIVE_GOALS} 个了，先完成或放弃一个",
                "You already have ${TasksTools.MAX_ACTIVE_GOALS} active goals — finish or drop one first",
            )
        }
        if (active.any { it.title == name }) return L10n.text("已经有同名的目标了", "You already have a goal with that name")
        env.store.add(Task(kind = TaskKind.GOAL, title = name, status = TaskStatus.RUNNING, why = why.trim()))
        return null
    }

    fun complete(env: TasksEnvironment, id: String) {
        env.scheduling.cancel(id)
        env.store.update(id) { it.copy(status = TaskStatus.DONE) }
    }

    /** 放弃(目标、提醒通用):闹钟一并撤掉。 */
    fun cancel(env: TasksEnvironment, id: String) {
        env.scheduling.cancel(id)
        env.store.update(id) { it.copy(status = TaskStatus.CANCELLED) }
    }

    fun delete(env: TasksEnvironment, id: String) {
        env.scheduling.cancel(id)
        env.store.delete(id)
    }

    fun togglePlanItem(env: TasksEnvironment, id: String, itemId: String) {
        env.store.update(id) { task ->
            task.copy(plan = task.plan.map { if (it.id == itemId) it.copy(done = !it.done) else it })
        }
    }

    fun addPlanItem(env: TasksEnvironment, id: String, text: String) {
        val step = text.trim()
        if (step.isEmpty()) return
        env.store.update(id) { task ->
            if (task.plan.size >= MAX_PLAN_ITEMS) task else task.copy(plan = task.plan + PlanItem(text = step.take(120)))
        }
    }

    fun addNote(env: TasksEnvironment, id: String, text: String) {
        val note = text.trim()
        if (note.isEmpty()) return
        val now = env.now()
        env.store.update(id, now) { task -> task.copy(notes = task.notes + GoalNote(now, note.take(400))) }
    }

    fun reopen(env: TasksEnvironment, id: String) {
        env.store.update(id) { it.copy(status = TaskStatus.RUNNING) }
    }

    private const val MAX_PLAN_ITEMS = 30
}
