package com.pinapia.vana.plugins

import com.pinapia.vana.agentruntime.AgentPlugin
import com.pinapia.vana.agentruntime.PluginContext
import com.pinapia.vana.agentruntime.PluginTool
import com.pinapia.vana.agentruntime.PromptBlock
import com.pinapia.vana.agentruntime.ToolEffect
import com.pinapia.vana.search.WebSearchTools
import com.pinapia.vana.tasks.SubagentTools
import com.pinapia.vana.tasks.TaskKind
import com.pinapia.vana.tasks.TasksEnvironment
import com.pinapia.vana.tasks.TasksTools

/**
 * 提醒、目标、现在几点。核心插件,任何 Vana 都带着。
 *
 * - 工具只有前台挂:后台派生用不到,也不该在用户不在场时替他设提醒。
 * - 进行中的目标(≤5 条,一行一个)**常驻 system 段**——以前「目标」是一条专属的会话线、名字才进 system 段;
 *   现在只有一条对话,模型随时知道他在推进什么。它排在易变那一片,目标一变只打掉尾巴。
 */
class TasksPlugin(private val env: TasksEnvironment) : AgentPlugin {
    override val id = "tasks"

    override fun tools(context: PluginContext): List<PluginTool> = buildList {
        addAll(
            PluginTool.from(TasksTools.registry(env)) { name ->
                if (name in TasksTools.READ_TOOLS) setOf(ToolEffect.READ) else setOf(ToolEffect.WRITE_LOCAL)
            },
        )
        // 派后台任务:放一张确认卡、要用户点了才跑,所以是写盘 + 要用户参与——
        // 隐私会话(写盘)和后台路(没人在场)都不挂,后台助手因此不能再派后台助手。
        if (env.jobs != null) {
            addAll(
                PluginTool.from(SubagentTools.startTaskRegistry(env)) {
                    setOf(ToolEffect.WRITE_LOCAL, ToolEffect.NEEDS_USER)
                },
            )
        }
    }

    override fun promptBlocks(context: PluginContext, mountedTools: Set<String>): List<PromptBlock> = buildList {
        if (TasksTools.CREATE_REMINDER in mountedTools) {
            add(
                PromptBlock(
                    PromptOrder.GUIDE_TASKS,
                    "用户要你在某个时间提醒他做某件事时，先用 ${TasksTools.GET_TIME} 知道现在几点，把「明晚 8 点」这类说法换算成具体时间，" +
                        "再用 ${TasksTools.CREATE_REMINDER} 设好，并照实告诉他设在了什么时候。提醒到点只会发一条通知，不会再调用你；" +
                        "它可能比设定的晚几分钟，不要承诺分秒不差。" +
                        "用户说想长期坚持某件事（备半马、学吉他、把作息调回来）时，可以问他要不要记成一个目标（${TasksTools.CREATE_GOAL}）；" +
                        "目标有进展或改了计划时用 ${TasksTools.UPDATE_GOAL} 记下。已经不做的提醒或目标，用 ${TasksTools.UPDATE_TASK} 完成或取消。",
                ),
            )
        }
        if (SubagentTools.START_TASK in mountedTools) {
            val web = if (WebSearchTools.SEARCH_TOOL_NAME in mountedTools) "它能上网搜索；" else "它现在不能上网（没配搜索），只能用记忆和过往的对话；"
            add(
                PromptBlock(
                    PromptOrder.GUIDE_JOBS,
                    "遇到**独立的、要花几分钟**的事（比较几个方案、整理一个主题的资料、查一批信息），可以用 ${SubagentTools.START_TASK} 派给后台助手，" +
                        "${web}它看不到这段对话，所以 brief 要写得自足。它会先给用户一张确认卡，他点了才跑，做完结果会出现在对话里。" +
                        "一句话能答的、需要来回商量的、涉及他此刻感受的事，直接在对话里做，不要派。",
                ),
            )
        }
        val goals = env.store.active().filter { it.kind == TaskKind.GOAL }.take(TasksTools.MAX_ACTIVE_GOALS)
        if (goals.isNotEmpty()) {
            val lines = goals.joinToString("\n") { goal ->
                buildString {
                    append("- ${goal.handle} ${goal.title}")
                    if (goal.why.isNotBlank()) append("（${goal.why}）")
                    append(" · ${TasksTools.planProgress(goal)}")
                    goal.notes.lastOrNull()?.let { append(" · 最近进展：${it.text.take(40)}") }
                }
            }
            add(PromptBlock(PromptOrder.GOAL, "他正在推进的目标（不是每次都要提起，相关时再结合）：\n$lines"))
        }
    }
}
