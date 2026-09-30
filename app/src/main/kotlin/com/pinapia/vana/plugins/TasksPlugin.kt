package com.pinapia.vana.plugins

import com.pinapia.vana.agentruntime.AgentPlugin
import com.pinapia.vana.agentruntime.PluginContext
import com.pinapia.vana.agentruntime.PluginTool
import com.pinapia.vana.agentruntime.PromptBlock
import com.pinapia.vana.agentruntime.ToolEffect
import com.pinapia.vana.tasks.TaskKind
import com.pinapia.vana.tasks.TasksEnvironment
import com.pinapia.vana.tasks.TasksTools

/**
 * 提醒、目标、现在几点。核心插件,任何 Vana 都带着。
 *
 * - 工具只有前台挂:后台派生用不到,也不该在用户不在场时替他设提醒。
 * - 以前还有 `start_task`(派后台任务),2026-09-30 连同子 agent 撤掉了:独立的活由用户自己开侧聊。
 * - 进行中的目标(≤5 条,一行一个)**常驻 system 段**——以前「目标」是一条专属的会话线、名字才进 system 段;
 *   现在只有一条对话,模型随时知道他在推进什么。它排在易变那一片,目标一变只打掉尾巴。
 */
class TasksPlugin(private val env: TasksEnvironment) : AgentPlugin {
    override val id = "tasks"

    override fun tools(context: PluginContext): List<PluginTool> =
        PluginTool.from(TasksTools.registry(env)) { name ->
            if (name in TasksTools.READ_TOOLS) setOf(ToolEffect.READ) else setOf(ToolEffect.WRITE_LOCAL)
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
