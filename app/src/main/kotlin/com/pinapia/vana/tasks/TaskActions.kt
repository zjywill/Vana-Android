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

    /** 放弃(目标、提醒、任务通用):闹钟一并撤掉。 */
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

    /** 打开「每周回顾」的这一刻起算:第一次回顾在七天之后,不是马上。 */
    fun setDigest(env: TasksEnvironment, id: String, enabled: Boolean) {
        val now = env.now()
        env.store.update(id, now) { it.copy(digestEnabled = enabled, lastDigestAt = if (enabled) now else it.lastDigestAt) }
    }

    fun reopen(env: TasksEnvironment, id: String) {
        env.store.update(id) { it.copy(status = TaskStatus.RUNNING) }
    }

    /**
     * 用户对后台助手的一条提议做了决定。「照做」才真的写:提醒走和手动添加同一条路(同样的上限和排程),
     * 记忆是用户亲手点了才存的,所以按「自己写的」算(不会被容量挤掉)。
     * 返回没能照做的原因,null 表示成功或只是略过。
     */
    fun decide(
        env: TasksEnvironment,
        memory: com.pinapia.vana.memory.MemoryStore?,
        taskId: String,
        proposalId: String,
        accept: Boolean,
    ): String? {
        val task = env.store.get(taskId) ?: return null
        val proposal = task.result?.proposals?.firstOrNull { it.id == proposalId } ?: return null
        if (proposal.status != ProposalStatus.PENDING) return null

        var problem: String? = null
        if (accept) {
            problem = when (proposal.kind) {
                "reminder" -> {
                    val at = proposal.at
                    if (at == null) L10n.text("这条提醒没有时间", "This reminder has no time") else addReminder(env, proposal.text, at, Repeat.NONE)
                }
                "goal" -> addGoal(env, proposal.text, proposal.why.orEmpty())
                "memory" -> if (memory == null) {
                    L10n.text("记忆现在是关着的", "Memory is turned off")
                } else {
                    memory.remember(proposal.text, com.pinapia.vana.memory.MemoryItem.Kind.PROFILE, origin = com.pinapia.vana.memory.MemoryItem.Origin.MANUAL)
                    null
                }
                else -> L10n.text("不认识这种提议", "Unknown suggestion")
            }
        }
        val next = if (accept && problem == null) ProposalStatus.ACCEPTED else ProposalStatus.DISMISSED
        env.store.update(taskId) { current ->
            current.copy(
                result = current.result?.copy(
                    proposals = current.result.proposals.map { if (it.id == proposalId) it.copy(status = next) else it },
                ),
            )
        }
        return problem
    }

    private const val MAX_PLAN_ITEMS = 30
}
