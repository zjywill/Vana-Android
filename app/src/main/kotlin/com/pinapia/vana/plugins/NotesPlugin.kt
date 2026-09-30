package com.pinapia.vana.plugins

import com.pinapia.vana.agentruntime.AgentPlugin
import com.pinapia.vana.agentruntime.PluginContext
import com.pinapia.vana.agentruntime.PluginTool
import com.pinapia.vana.agentruntime.PromptBlock
import com.pinapia.vana.agentruntime.ToolEffect
import com.pinapia.vana.notes.NoteStore
import com.pinapia.vana.notes.NotesTools
import com.pinapia.vana.session.ToolCallRecord
import com.pinapia.vana.ui.L10n

/**
 * 笔记与清单:用户自己要留着的内容(购物单、想法、草稿)。按需读写,**不常驻上下文**;
 * 它自己存着这些,所以让记忆抽取器让路(购物单不是「关于他的事」)。
 */
class NotesAgentPlugin(private val store: NoteStore) : AgentPlugin {
    override val id = "notes"

    override val memoryExclusions = listOf("购物单、待办清单、草稿这类他要留着的内容（有笔记存着）")

    override fun tools(context: PluginContext): List<PluginTool> =
        PluginTool.from(NotesTools.registry(store)) { name ->
            if (name in NotesTools.READ_TOOLS) setOf(ToolEffect.READ) else setOf(ToolEffect.WRITE_LOCAL)
        }

    override fun promptBlocks(context: PluginContext, mountedTools: Set<String>): List<PromptBlock> {
        if (NotesTools.LIST !in mountedTools) return emptyList()
        val writes = if (NotesTools.SAVE in mountedTools) {
            "用户要你记下购物单、行李单、想法、草稿时，用 ${NotesTools.SAVE} 存成笔记或清单；要往里加东西、勾掉、改写，用 ${NotesTools.UPDATE}。"
        } else {
            ""
        }
        return listOf(
            PromptBlock(
                PromptOrder.GUIDE_NOTES,
                "用户有自己的笔记和清单，不在你的上下文里，需要时才读：他提到「我记的那个清单」「上次写的草稿」，" +
                    "先用 ${NotesTools.LIST} 找、再用 ${NotesTools.READ} 读，不要凭印象说里面写了什么。$writes" +
                    "笔记是他的内容，记忆是关于他这个人的事实（偏好、家人、习惯），两者不要混：购物单不是记忆。",
            ),
        )
    }
}

object NotesVanaPlugin : VanaPlugin {
    override val manifest = PluginManifest(
        id = PluginIds.NOTES,
        name = Localized("笔记与清单", "Notes and lists"),
        summary = Localized("购物单、想法、草稿，让 Vana 按需读写", "Shopping lists, ideas and drafts that Vana can read and write on demand"),
        defaultEnabled = true,
    )

    override val surfaces = listOf(
        PluginSurface(
            id = PluginSurface.NOTES,
            title = Localized("笔记与清单", "Notes and lists"),
            subtitle = Localized("自己记，或者让 Vana 帮你记", "Write them yourself, or ask Vana to"),
        ),
    )

    override val welcomeBlurb = Localized(zh = "记购物清单和想法", en = "keeping lists and notes")

    override fun toolLabel(call: ToolCallRecord): String? = when (call.name) {
        NotesTools.SAVE -> L10n.text("存了一条笔记", "Saved a note")
        NotesTools.LIST -> L10n.text("查找了笔记", "Looked through your notes")
        NotesTools.READ -> L10n.text("读了一条笔记", "Read a note")
        NotesTools.UPDATE -> L10n.text("更新了笔记", "Updated a note")
        else -> null
    }

    override fun agentPlugins(env: PluginEnvironment, route: PluginRoute): List<AgentPlugin> {
        // 只前台挂:后台那一路(待跟进回访)没有理由读用户的清单。
        if (route != PluginRoute.FOREGROUND) return emptyList()
        return listOfNotNull(env.noteStore?.let { NotesAgentPlugin(it) })
    }
}
