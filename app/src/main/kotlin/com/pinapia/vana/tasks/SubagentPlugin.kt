package com.pinapia.vana.tasks

import com.pinapia.vana.agentruntime.AgentPlugin
import com.pinapia.vana.agentruntime.PluginContext
import com.pinapia.vana.agentruntime.PluginTool
import com.pinapia.vana.agentruntime.PromptBlock
import com.pinapia.vana.agentruntime.ToolEffect
import com.pinapia.vana.plugins.PromptOrder
import java.time.ZoneId

/**
 * 后台助手这一路独有的:角色说明加「提议」工具。
 * 提议不写任何东西(攒在 [collector] 里,跑完随结果交给用户),所以声明为只读,后台路能挂。
 */
class SubagentPlugin(
    private val collector: ProposalCollector,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : AgentPlugin {
    override val id = "subagent"

    override fun tools(context: PluginContext): List<PluginTool> =
        PluginTool.from(SubagentTools.proposeRegistry(collector, zone)) { setOf(ToolEffect.READ) }

    override fun promptBlocks(context: PluginContext, mountedTools: Set<String>): List<PromptBlock> =
        listOf(PromptBlock(PromptOrder.SUBAGENT, SubagentInstructions.text()))
}
