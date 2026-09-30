package com.pinapia.vana.tasks

import com.pinapia.vana.agentruntime.AgentToolOutput
import com.pinapia.vana.agentruntime.CapabilityDefinition
import com.pinapia.vana.agentruntime.CapabilityExecutionResult
import com.pinapia.vana.agentruntime.CapabilityInvocation
import com.pinapia.vana.agentruntime.CapabilityRegistry
import com.pinapia.vana.agentruntime.RuntimeJSONValue
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/** 提醒落到系统闹钟上。`create_reminder` 之类的工具经它排程,而不直接认识 AlarmManager。 */
interface ReminderScheduling {
    fun schedule(task: Task)
    fun cancel(taskId: String)

    companion object {
        val none = object : ReminderScheduling {
            override fun schedule(task: Task) = Unit
            override fun cancel(taskId: String) = Unit
        }
    }
}

class TasksEnvironment(
    val store: TaskStore,
    val scheduling: ReminderScheduling = ReminderScheduling.none,
    val now: () -> Instant = { Clock.System.now() },
    val zone: ZoneId = ZoneId.systemDefault(),
)

/**
 * 提醒、目标和「现在几点」。
 *
 * 精确时间**不进 system 段**(每分钟都变,会把请求前缀的缓存整个打掉),要知道现在几点就调
 * [GET_TIME]。提醒到点只发通知、**不调模型**,所以这里的工具只管创建和管理,没有「到点执行」。
 * 写操作([CREATE_REMINDER]、[UPDATE_TASK]、[CREATE_GOAL]、[UPDATE_GOAL])在装配时声明为写盘工具,
 * 「不留痕」浮层和后台派生里都不会挂出去。
 */
object TasksTools {
    const val GET_TIME = "get_current_time"
    const val CREATE_REMINDER = "create_reminder"
    const val LIST = "list_tasks"
    const val UPDATE_TASK = "update_task"
    const val CREATE_GOAL = "create_goal"
    const val UPDATE_GOAL = "update_goal"

    val READ_TOOLS = setOf(GET_TIME, LIST)

    /** 同时进行中的目标最多这么多:目标多了就没有目标了,system 段里那一小块也放不下。 */
    const val MAX_ACTIVE_GOALS = 5
    const val MAX_TITLE_CHARS = 60

    fun registry(env: TasksEnvironment): CapabilityRegistry =
        CapabilityRegistry(
            definitions = listOf(
                getTimeDefinition(),
                createReminderDefinition(),
                listDefinition(),
                updateTaskDefinition(),
                createGoalDefinition(),
                updateGoalDefinition(),
            ),
        ) { invocation ->
            val input = runCatching { RuntimeJSONValue.decode(from = invocation.input) }.getOrNull()
            when (invocation.name) {
                GET_TIME -> getTime(env)
                CREATE_REMINDER -> createReminder(env, input)
                LIST -> list(env, input)
                UPDATE_TASK -> updateTask(env, input)
                CREATE_GOAL -> createGoal(env, input)
                UPDATE_GOAL -> updateGoal(env, input)
                else -> failure("不支持名为 ${invocation.name} 的工具。")
            }
        }

    // ---------------- 定义 ----------------

    private fun obj(vararg entries: Pair<String, RuntimeJSONValue>) = RuntimeJSONValue.ObjectValue(mapOf(*entries))
    private fun str(value: String) = RuntimeJSONValue.StringValue(value)
    private fun stringProp(description: String) = obj("type" to str("string"), "description" to str(description))
    private fun intProp(description: String, min: Int? = null, max: Int? = null): RuntimeJSONValue.ObjectValue {
        val entries = linkedMapOf<String, RuntimeJSONValue>(
            "type" to str("integer"),
            "description" to str(description),
        )
        min?.let { entries["minimum"] = RuntimeJSONValue.IntValue(it) }
        max?.let { entries["maximum"] = RuntimeJSONValue.IntValue(it) }
        return RuntimeJSONValue.ObjectValue(entries)
    }
    private fun stringList(description: String) = obj(
        "type" to str("array"),
        "description" to str(description),
        "items" to obj("type" to str("string")),
    )
    private fun enumProp(description: String, vararg values: String) = obj(
        "type" to str("string"),
        "description" to str(description),
        "enum" to RuntimeJSONValue.ArrayValue(values.map { str(it) }),
    )
    private fun schema(properties: Map<String, RuntimeJSONValue>, required: List<String> = emptyList()) = obj(
        "type" to str("object"),
        "properties" to RuntimeJSONValue.ObjectValue(properties),
        "required" to RuntimeJSONValue.ArrayValue(required.map { str(it) }),
        "additionalProperties" to RuntimeJSONValue.BoolValue(false),
    )

    private fun getTimeDefinition() = CapabilityDefinition(
        name = GET_TIME,
        description = "读取现在的日期、星期、时间和用户所在的时区。要把「明晚 8 点」「三天后」这类说法换算成具体时间之前先调用它。",
        inputSchema = schema(emptyMap()),
    )

    private fun createReminderDefinition() = CapabilityDefinition(
        name = CREATE_REMINDER,
        description = "设一条提醒：到点手机会发一条通知。给 at（用户当地时间，形如 2026-10-01T20:00）或 in_minutes（多少分钟以后）二选一。" +
            "重复提醒用 repeat。提醒到点只发通知，不会再调用你。",
        inputSchema = schema(
            mapOf(
                "text" to stringProp("提醒的内容，一句话，比如「给妈妈打电话」"),
                "at" to stringProp("用户当地时间，形如 2026-10-01T20:00"),
                "in_minutes" to intProp("多少分钟以后", min = 1, max = 60 * 24 * 30),
                "repeat" to enumProp("重复方式，默认不重复", "none", "daily", "weekly"),
            ),
            required = listOf("text"),
        ),
    )

    private fun listDefinition() = CapabilityDefinition(
        name = LIST,
        description = "列出进行中的提醒和目标，带短编号。要改或取消某一条之前先用它拿编号。",
        inputSchema = schema(
            mapOf("kind" to enumProp("只看某一种，默认全部", "all", "reminder", "goal")),
        ),
    )

    private fun updateTaskDefinition() = CapabilityDefinition(
        name = UPDATE_TASK,
        description = "对一条提醒或目标做：complete 完成、cancel 取消、reschedule 改期（只有提醒能改期，给 at 或 in_minutes）。按 list_tasks 给的短编号指到那一条。",
        inputSchema = schema(
            mapOf(
                "id" to stringProp("短编号，来自 list_tasks"),
                "action" to enumProp("要做什么", "complete", "cancel", "reschedule"),
                "at" to stringProp("改期用：用户当地时间，形如 2026-10-01T20:00"),
                "in_minutes" to intProp("改期用：多少分钟以后", min = 1, max = 60 * 24 * 30),
            ),
            required = listOf("id", "action"),
        ),
    )

    private fun createGoalDefinition() = CapabilityDefinition(
        name = CREATE_GOAL,
        description = "把用户想长期坚持的一件事记成目标（备半马、学吉他、把作息调回来）。只在用户表示要长期做这件事时才建；同时进行中的目标最多 $MAX_ACTIVE_GOALS 个。",
        inputSchema = schema(
            mapOf(
                "title" to stringProp("目标名称，短一点"),
                "why" to stringProp("他为什么要做这件事，一句话，可选"),
                "plan" to stringList("初步的几个步骤，可选"),
            ),
            required = listOf("title"),
        ),
    )

    private fun updateGoalDefinition() = CapabilityDefinition(
        name = UPDATE_GOAL,
        description = "更新一个目标：改名、改原因、加步骤、勾掉做完的步骤、记一条进展。按 list_tasks 或系统提示里给的短编号指到目标。",
        inputSchema = schema(
            mapOf(
                "id" to stringProp("目标的短编号"),
                "title" to stringProp("新名称，可选"),
                "why" to stringProp("新的原因，可选"),
                "add_plan" to stringList("要加的步骤，可选"),
                "complete_plan" to stringList("做完了的步骤（写步骤原文的一部分即可），可选"),
                "note" to stringProp("一条进展记录，可选"),
            ),
            required = listOf("id"),
        ),
    )

    // ---------------- 执行 ----------------

    private fun failure(message: String) = CapabilityExecutionResult(
        output = AgentToolOutput(kind = AgentToolOutput.Kind.TEXT, text = message),
        isError = true,
    )

    private fun success(message: String) = CapabilityExecutionResult(
        output = AgentToolOutput(kind = AgentToolOutput.Kind.TEXT, text = message),
    )

    private val weekdays = arrayOf("一", "二", "三", "四", "五", "六", "日")

    private fun getTime(env: TasksEnvironment): CapabilityExecutionResult {
        val now = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(env.now().toEpochMilliseconds()), env.zone)
        val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT).format(now)
        return success("现在是 $stamp 星期${weekdays[now.dayOfWeek.value - 1]}（${env.zone.id}）。")
    }

    /** 从 at / in_minutes 里取出目标时间;取不出来就给出该说的错话。 */
    private fun resolveTime(env: TasksEnvironment, input: RuntimeJSONValue?): Pair<Instant?, String?> {
        val at = input?.get("at")?.stringValue?.trim().orEmpty()
        val minutes = input?.get("in_minutes")?.intValue
        val now = env.now()
        val due = when {
            at.isNotEmpty() -> ReminderRules.parseLocal(at, env.zone)
                ?: return null to "看不懂这个时间「$at」。用用户当地时间，形如 2026-10-01T20:00。"
            minutes != null -> Instant.fromEpochMilliseconds(now.toEpochMilliseconds() + minutes * 60_000L)
            else -> return null to "需要 at（具体时间）或 in_minutes（多少分钟以后）二选一。"
        }
        if (due.toEpochMilliseconds() < now.toEpochMilliseconds() + 30_000L) {
            return null to "这个时间已经过了或太近了（${ReminderRules.describe(due, now, env.zone)}）。先用 get_current_time 看现在几点，再给一个之后的时间。"
        }
        if (due.toEpochMilliseconds() > now.toEpochMilliseconds() + ReminderRules.MAX_HORIZON_DAYS * 86_400_000L) {
            return null to "最远只能约到一年以内。"
        }
        return due to null
    }

    private fun createReminder(env: TasksEnvironment, input: RuntimeJSONValue?): CapabilityExecutionResult {
        val text = input?.get("text")?.stringValue?.trim().orEmpty()
        if (text.isEmpty()) return failure("create_reminder 需要提醒的内容 text。")
        if (text.length > MAX_TITLE_CHARS * 2) return failure("提醒的内容太长了，压成一句话。")
        val (due, error) = resolveTime(env, input)
        if (due == null) return failure(error ?: "时间不对。")
        val repeat = when (input?.get("repeat")?.stringValue) {
            "daily" -> Repeat.DAILY
            "weekly" -> Repeat.WEEKLY
            else -> Repeat.NONE
        }
        if (env.store.active().count { it.kind == TaskKind.REMINDER } >= ReminderRules.MAX_ACTIVE_REMINDERS) {
            return failure("进行中的提醒已经有 ${ReminderRules.MAX_ACTIVE_REMINDERS} 条了。先让用户清理一下。")
        }
        val task = env.store.add(
            Task(kind = TaskKind.REMINDER, title = text, status = TaskStatus.QUEUED, dueAt = due, repeat = repeat),
        )
        env.scheduling.schedule(task)
        val when_ = ReminderRules.describe(due, env.now(), env.zone)
        val every = ReminderRules.describeRepeat(repeat, due, env.zone).let { if (it.isEmpty()) "" else "，$it 重复" }
        return success("已设好提醒：$text，$when_$every。（提醒可能比设定的时间晚几分钟。）")
    }

    private fun kindLabel(kind: TaskKind) = when (kind) {
        TaskKind.REMINDER -> "提醒"
        TaskKind.GOAL -> "目标"
    }

    private fun describeLine(task: Task, env: TasksEnvironment): String = when (task.kind) {
        TaskKind.REMINDER -> {
            val due = task.dueAt?.let { ReminderRules.describe(it, env.now(), env.zone) }.orEmpty()
            val every = ReminderRules.describeRepeat(task.repeat, task.dueAt, env.zone).let { if (it.isEmpty()) "" else "（$it）" }
            "- ${task.handle} · $due$every · ${task.title}"
        }
        TaskKind.GOAL -> "- ${task.handle} · ${task.title} · ${planProgress(task)}"
    }

    fun planProgress(task: Task): String =
        if (task.plan.isEmpty()) "还没有步骤" else "步骤 ${task.plan.count { it.done }}/${task.plan.size}"

    fun statusLabel(status: TaskStatus) = when (status) {
        TaskStatus.QUEUED -> "还没到点"
        TaskStatus.RUNNING -> "进行中"
        TaskStatus.DONE -> "已完成"
        TaskStatus.CANCELLED -> "已取消"
    }

    private fun list(env: TasksEnvironment, input: RuntimeJSONValue?): CapabilityExecutionResult {
        val kind = when (input?.get("kind")?.stringValue) {
            "reminder" -> TaskKind.REMINDER
            "goal" -> TaskKind.GOAL
            else -> null
        }
        val active = env.store.active().filter { kind == null || it.kind == kind }
        if (active.isEmpty()) return success("现在没有进行中的提醒或目标。")
        val lines = mutableListOf<String>()
        for (group in listOf(TaskKind.REMINDER, TaskKind.GOAL)) {
            val items = active.filter { it.kind == group }
            if (items.isEmpty()) continue
            lines += "${kindLabel(group)}："
            items.sortedBy { it.dueAt?.toEpochMilliseconds() ?: it.createdAt.toEpochMilliseconds() }
                .forEach { lines += describeLine(it, env) }
        }
        return success(lines.joinToString("\n"))
    }

    private fun updateTask(env: TasksEnvironment, input: RuntimeJSONValue?): CapabilityExecutionResult {
        val handle = input?.get("id")?.stringValue?.trim().orEmpty()
        val task = env.store.find(handle)
            ?: return failure("没有找到编号为 $handle 的项。先用 list_tasks 拿编号。")
        if (!task.isActive) return failure("「${task.title}」已经${statusLabel(task.status)}了。")
        return when (input?.get("action")?.stringValue) {
            "complete" -> {
                env.scheduling.cancel(task.id)
                env.store.update(task.id) { it.copy(status = TaskStatus.DONE) }
                success("已完成：${task.title}")
            }
            "cancel" -> {
                env.scheduling.cancel(task.id)
                env.store.update(task.id) { it.copy(status = TaskStatus.CANCELLED) }
                success("已取消：${task.title}")
            }
            "reschedule" -> {
                if (task.kind != TaskKind.REMINDER) return failure("只有提醒能改期。")
                val (due, error) = resolveTime(env, input)
                if (due == null) return failure(error ?: "时间不对。")
                val updated = env.store.update(task.id) { it.copy(dueAt = due, status = TaskStatus.QUEUED) }!!
                env.scheduling.schedule(updated)
                success("已改到 ${ReminderRules.describe(due, env.now(), env.zone)}：${task.title}")
            }
            else -> failure("action 要是 complete、cancel 或 reschedule。")
        }
    }

    private fun createGoal(env: TasksEnvironment, input: RuntimeJSONValue?): CapabilityExecutionResult {
        val title = input?.get("title")?.stringValue?.trim().orEmpty()
        if (title.isEmpty()) return failure("create_goal 需要目标名称 title。")
        if (title.length > MAX_TITLE_CHARS) return failure("目标名称太长了，短一点。")
        val active = env.store.active().filter { it.kind == TaskKind.GOAL }
        if (active.size >= MAX_ACTIVE_GOALS) {
            return failure("进行中的目标已经有 $MAX_ACTIVE_GOALS 个了。先完成或取消一个，再建新的。")
        }
        if (active.any { it.title == title }) return failure("已经有一个叫「$title」的目标了，用 update_goal 更新它。")
        val plan = input?.get("plan")?.arrayValue?.mapNotNull { it.stringValue?.trim()?.takeIf(String::isNotEmpty) }.orEmpty()
        val task = env.store.add(
            Task(
                kind = TaskKind.GOAL,
                title = title,
                status = TaskStatus.RUNNING,
                why = input?.get("why")?.stringValue?.trim().orEmpty(),
                plan = plan.take(12).map { PlanItem(text = it) },
            ),
        )
        return success("已记成目标：$title（编号 ${task.handle}，${planProgress(task)}）。")
    }

    private fun updateGoal(env: TasksEnvironment, input: RuntimeJSONValue?): CapabilityExecutionResult {
        val handle = input?.get("id")?.stringValue?.trim().orEmpty()
        val goal = env.store.find(handle)?.takeIf { it.kind == TaskKind.GOAL }
            ?: return failure("没有找到编号为 $handle 的目标。先用 list_tasks 拿编号。")
        if (!goal.isActive) return failure("「${goal.title}」已经${statusLabel(goal.status)}了。")
        val now = env.now()
        val newTitle = input?.get("title")?.stringValue?.trim()?.takeIf { it.isNotEmpty() }
        if (newTitle != null && newTitle.length > MAX_TITLE_CHARS) return failure("目标名称太长了，短一点。")
        val why = input?.get("why")?.stringValue?.trim()
        val add = input?.get("add_plan")?.arrayValue?.mapNotNull { it.stringValue?.trim()?.takeIf(String::isNotEmpty) }.orEmpty()
        val complete = input?.get("complete_plan")?.arrayValue?.mapNotNull { it.stringValue?.trim()?.takeIf(String::isNotEmpty) }.orEmpty()
        val note = input?.get("note")?.stringValue?.trim()?.takeIf { it.isNotEmpty() }

        val unmatched = mutableListOf<String>()
        val updated = env.store.update(goal.id, now) { current ->
            var plan = current.plan + add.map { PlanItem(text = it) }
            for (key in complete) {
                val index = plan.indexOfFirst { !it.done && (it.text.contains(key) || it.id.startsWith(key)) }
                if (index < 0) unmatched += key else plan = plan.mapIndexed { i, item -> if (i == index) item.copy(done = true) else item }
            }
            current.copy(
                title = newTitle ?: current.title,
                why = why ?: current.why,
                plan = plan.take(30),
                notes = if (note != null) current.notes + GoalNote(now, note) else current.notes,
            )
        }!!
        val tail = if (unmatched.isEmpty()) "" else "（没找到这几个步骤：${unmatched.joinToString("、")}）"
        return success("已更新目标「${updated.title}」：${planProgress(updated)}$tail")
    }
}
