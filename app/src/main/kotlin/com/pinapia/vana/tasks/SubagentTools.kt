package com.pinapia.vana.tasks

import com.pinapia.vana.agentruntime.AgentToolOutput
import com.pinapia.vana.agentruntime.CapabilityDefinition
import com.pinapia.vana.agentruntime.CapabilityExecutionResult
import com.pinapia.vana.agentruntime.CapabilityRegistry
import com.pinapia.vana.agentruntime.RuntimeJSONValue
import java.time.ZoneId
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/** 后台助手一路上攒下来的「想让用户做的事」。它自己不能写任何东西,只能提议。 */
class ProposalCollector {
    private val items = mutableListOf<TaskProposal>()

    @Synchronized
    fun add(proposal: TaskProposal): Boolean {
        if (items.size >= MAX) return false
        items += proposal
        return true
    }

    @Synchronized
    fun all(): List<TaskProposal> = items.toList()

    companion object {
        const val MAX = 5
    }
}

/**
 * 两个工具,分属两头:
 * - [START_TASK]:**前台**的 Vana 用它派活。只放一张确认卡,用户点了「开始」才会跑——
 *   所以它声明成写盘 + 要用户参与,隐私会话和后台里都不挂(后台助手不能再派后台助手)。
 * - [PROPOSE_ACTION]:**后台助手**用它提议「设个提醒 / 记成目标 / 记住这件事」。什么都不写,
 *   只是攒在一个列表里,跑完之后放在结果里由用户逐条决定,所以是只读的。
 */
object SubagentTools {
    const val START_TASK = "start_task"
    const val PROPOSE_ACTION = "propose_action"

    private fun obj(vararg entries: Pair<String, RuntimeJSONValue>) = RuntimeJSONValue.ObjectValue(mapOf(*entries))
    private fun str(value: String) = RuntimeJSONValue.StringValue(value)
    private fun stringProp(description: String) = obj("type" to str("string"), "description" to str(description))

    private fun failure(message: String) = CapabilityExecutionResult(
        output = AgentToolOutput(kind = AgentToolOutput.Kind.TEXT, text = message),
        isError = true,
    )

    fun startTaskRegistry(env: TasksEnvironment): CapabilityRegistry {
        val definition = CapabilityDefinition(
            name = START_TASK,
            description = "把一件**独立、要花几分钟**的事（查很多资料、比较几个方案、整理一个主题）派给后台助手去做。" +
                "它看不到这段对话，所以 brief 必须自己讲得清清楚楚。会先给用户一张确认卡，他点了「开始」才会跑；" +
                "结果出来后会出现在对话里。一句话能答的问题不要派。",
            inputSchema = obj(
                "type" to str("object"),
                "properties" to obj(
                    "title" to stringProp("任务名，短一点，比如「比较三款空气净化器」"),
                    "brief" to stringProp(
                        "交给后台助手的完整说明：要做什么、要什么样的结果、用户的相关偏好和限制。" +
                            "不超过 ${SubagentLimits.MAX_BRIEF_CHARS} 字，不要写进用户不必要的个人信息。",
                    ),
                ),
                "required" to RuntimeJSONValue.ArrayValue(listOf(str("title"), str("brief"))),
                "additionalProperties" to RuntimeJSONValue.BoolValue(false),
            ),
        )
        return CapabilityRegistry(definitions = listOf(definition)) { invocation ->
            val input = runCatching { RuntimeJSONValue.decode(from = invocation.input) }.getOrNull()
            startTask(env, input)
        }
    }

    private fun startTask(env: TasksEnvironment, input: RuntimeJSONValue?): CapabilityExecutionResult {
        val jobs = env.jobs ?: return failure("这台设备上现在不能派后台任务。")
        val title = input?.get("title")?.stringValue?.trim().orEmpty()
        val brief = input?.get("brief")?.stringValue?.trim().orEmpty()
        if (title.isEmpty() || brief.isEmpty()) return failure("start_task 需要 title 和 brief。")
        if (title.length > TasksTools.MAX_TITLE_CHARS) return failure("任务名太长了，短一点。")
        if (brief.length > SubagentLimits.MAX_BRIEF_CHARS) {
            return failure("brief 太长了（最多 ${SubagentLimits.MAX_BRIEF_CHARS} 字），压缩成后台助手真正需要的部分。")
        }
        SubagentLimits.problem(env.store, env.now(), env.zone)?.let { return failure(it) }

        val task = env.store.add(Task(kind = TaskKind.JOB, title = title, status = TaskStatus.PROPOSED, brief = brief))
        val auto = jobs.autoStart
        if (auto) jobs.start(task.id)

        val text = if (auto) {
            "已经交给后台助手（编号 ${task.handle}）。它需要几分钟，做完结果会出现在对话里。你现在不用等，也不要说已经做完了。"
        } else {
            "已经在对话里放了一张确认卡（编号 ${task.handle}）：用户点「开始」才会跑。" +
                "告诉他这件事会放到后台做、大概几分钟，然后接着聊别的；不要说已经开始了。"
        }
        return CapabilityExecutionResult(
            output = AgentToolOutput(
                kind = AgentToolOutput.Kind.TEXT,
                text = text,
                // 只给界面:卡片凭它找到那条任务。
                metadata = RuntimeJSONValue.ObjectValue(mapOf(TASK_ID_KEY to RuntimeJSONValue.StringValue(task.id))),
            ),
        )
    }

    fun proposeRegistry(collector: ProposalCollector, zone: ZoneId, now: () -> Instant = { Clock.System.now() }): CapabilityRegistry {
        val definition = CapabilityDefinition(
            name = PROPOSE_ACTION,
            description = "提议一件你想让用户做的事，放进结果里由他逐条决定，你自己什么都不会被写下。" +
                "kind 是 reminder（设提醒，要给 at）、goal（记成长期目标）或 memory（记住一件长期成立的事）。最多 ${ProposalCollector.MAX} 条，只提真正有用的。",
            inputSchema = obj(
                "type" to str("object"),
                "properties" to obj(
                    "kind" to obj(
                        "type" to str("string"),
                        "description" to str("提议的类型"),
                        "enum" to RuntimeJSONValue.ArrayValue(listOf(str("reminder"), str("goal"), str("memory"))),
                    ),
                    "text" to stringProp("提议的内容，一句话"),
                    "at" to stringProp("kind=reminder 时必填：用户当地时间，形如 2026-10-01T20:00"),
                    "why" to stringProp("为什么这样提议，一句话，可选"),
                ),
                "required" to RuntimeJSONValue.ArrayValue(listOf(str("kind"), str("text"))),
                "additionalProperties" to RuntimeJSONValue.BoolValue(false),
            ),
        )
        return CapabilityRegistry(definitions = listOf(definition)) { invocation ->
            val input = runCatching { RuntimeJSONValue.decode(from = invocation.input) }.getOrNull()
            val kind = input?.get("kind")?.stringValue
            val text = input?.get("text")?.stringValue?.trim().orEmpty()
            if (kind !in setOf("reminder", "goal", "memory") || text.isEmpty()) {
                return@CapabilityRegistry failure("需要 kind（reminder / goal / memory）和 text。")
            }
            var at: Instant? = null
            if (kind == "reminder") {
                val raw = input?.get("at")?.stringValue?.trim().orEmpty()
                at = ReminderRules.parseLocal(raw, zone)
                    ?: return@CapabilityRegistry failure("提议提醒时要给 at，用用户当地时间，形如 2026-10-01T20:00。")
                if (at <= now()) return@CapabilityRegistry failure("这个时间已经过了，换一个之后的时间。")
            }
            val added = collector.add(
                TaskProposal(kind = kind!!, text = text.take(200), at = at, why = input?.get("why")?.stringValue?.trim()?.take(200)),
            )
            if (!added) return@CapabilityRegistry failure("提议已经够多了（最多 ${ProposalCollector.MAX} 条），不要再加。")
            CapabilityExecutionResult(
                output = AgentToolOutput(kind = AgentToolOutput.Kind.TEXT, text = "已记下这条提议，用户会在结果里决定要不要照做。"),
            )
        }
    }

    const val TASK_ID_KEY = "taskId"
}

/** 聊天里这条工具调用带的任务 id(卡片据此找到那条任务)。 */
val com.pinapia.vana.session.ToolCallRecord.taskId: String?
    get() = if (name == SubagentTools.START_TASK) metadata?.get(SubagentTools.TASK_ID_KEY)?.stringValue else null
