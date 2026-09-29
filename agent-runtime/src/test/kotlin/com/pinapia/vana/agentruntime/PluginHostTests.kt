package com.pinapia.vana.agentruntime

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginHostTests {
    private fun tool(
        name: String,
        vararg effects: ToolEffect,
        mount: MountPolicy = MountPolicy.Always,
    ) = PluginTool(
        definition = CapabilityDefinition(name = name, inputSchema = RuntimeJSONValue.ObjectValue(emptyMap())),
        effects = effects.toSet(),
        mount = mount,
    ) { invocation ->
        CapabilityExecutionResult(output = AgentToolOutput(kind = AgentToolOutput.Kind.TEXT, text = "ran ${invocation.name}"))
    }

    private class FakePlugin(
        override val id: String,
        private val tools: List<PluginTool>,
        private val blocks: (Set<String>) -> List<PromptBlock> = { emptyList() },
        override val dataScope: PluginDataScope = PluginDataScope.TENANT,
    ) : AgentPlugin {
        override fun tools(context: PluginContext) = tools
        override fun promptBlocks(context: PluginContext, mountedTools: Set<String>) = blocks(mountedTools)
    }

    private val notes = FakePlugin(
        id = "notes",
        tools = listOf(tool("list_notes", ToolEffect.READ), tool("write_note", ToolEffect.WRITE_LOCAL)),
    )
    private val ask = FakePlugin(id = "ask", tools = listOf(tool("ask_user", ToolEffect.NEEDS_USER)))
    private val search = FakePlugin(id = "search", tools = listOf(tool("web_search", ToolEffect.EXTERNAL)))

    private fun names(plugins: List<AgentPlugin>, context: PluginContext) =
        PluginHost.assemble(plugins, context).registry.definitions.map { it.name }

    @Test
    fun keepsRegistrationOrderAcrossPlugins() {
        assertEquals(
            listOf("ask_user", "list_notes", "write_note", "web_search"),
            names(listOf(ask, notes, search), PluginContext()),
        )
    }

    @Test
    fun privateSessionDropsEveryLocalWrite() {
        assertEquals(
            listOf("ask_user", "list_notes", "web_search"),
            names(listOf(ask, notes, search), PluginContext(isPrivate = true)),
        )
    }

    @Test
    fun backgroundTurnDropsWritesAndUserPrompts() {
        assertEquals(
            listOf("list_notes", "web_search"),
            names(listOf(ask, notes, search), PluginContext(isBackground = true)),
        )
    }

    @Test
    fun unlockedToolsOnlyMountAfterTheirTrigger() {
        val recall = FakePlugin(
            id = "recall",
            tools = listOf(tool("search_sessions", ToolEffect.READ, mount = MountPolicy.WhenUnlocked("recall"))),
        )
        assertEquals(emptyList<String>(), names(listOf(recall), PluginContext()))
        assertEquals(
            listOf("search_sessions"),
            names(listOf(recall), PluginContext(unlockedTriggers = setOf("recall"))),
        )
    }

    @Test
    fun deviceOwnerPluginsDisappearForOtherMembers() {
        val owned = FakePlugin(
            id = "owned",
            tools = listOf(tool("owner_data", ToolEffect.READ)),
            dataScope = PluginDataScope.DEVICE_OWNER,
        )
        assertEquals(listOf("owner_data"), names(listOf(owned), PluginContext()))
        assertEquals(emptyList<String>(), names(listOf(owned), PluginContext(isDeviceOwner = false)))
    }

    @Test
    fun blocksSortByOrderAndSeeOnlyMountedTools() {
        val guided = FakePlugin(
            id = "guided",
            tools = listOf(tool("write_note", ToolEffect.WRITE_LOCAL)),
            blocks = { mounted ->
                buildList {
                    add(PromptBlock(order = 30, text = "snapshot"))
                    if ("write_note" in mounted) add(PromptBlock(order = 100, text = "use write_note"))
                    if ("ask_user" in mounted) add(PromptBlock(order = 90, text = "use ask_user"))
                }
            },
        )
        val core = listOf(PromptBlock(order = 0, text = "base"), PromptBlock(order = 200, text = "persona"))

        val normal = PluginHost.assemble(listOf(ask, guided), PluginContext(), core)
        assertEquals("base\n\nsnapshot\n\nuse ask_user\n\nuse write_note\n\npersona", normal.instruction())

        val private = PluginHost.assemble(listOf(ask, guided), PluginContext(isPrivate = true), core)
        assertEquals("base\n\nsnapshot\n\nuse ask_user\n\npersona", private.instruction())
    }

    @Test
    fun executesThroughTheOwningTool() = runTest {
        val registry = PluginHost.assemble(listOf(ask, notes), PluginContext()).registry
        val ran = registry.execute(CapabilityInvocation(toolCallId = "1", name = "list_notes", input = "{}"))
        assertEquals("ran list_notes", ran.output.text)

        val filtered = PluginHost.assemble(listOf(notes), PluginContext(isPrivate = true)).registry
        val denied = filtered.execute(CapabilityInvocation(toolCallId = "2", name = "write_note", input = "{}"))
        assertTrue(denied.isError)
    }

    // ---- 记忆排除项:各插件声明「我自己存着这个」,别的插件不必认识它就能读到 ----

    private class MemoryAwarePlugin(
        override val id: String,
        override val memoryExclusions: List<String> = emptyList(),
        override val memoryGuidance: List<String> = emptyList(),
        override val dataScope: PluginDataScope = PluginDataScope.TENANT,
    ) : AgentPlugin

    /** 只看 context 的插件:模拟 `MemoryPlugin` 按别人的声明拼 `remember` 的描述。 */
    private class ReadsExclusions : AgentPlugin {
        override val id = "reader"
        var seenByTools: List<String>? = null
        var seenByBlocks: List<String>? = null

        override fun tools(context: PluginContext): List<PluginTool> {
            seenByTools = context.memoryExclusions
            return emptyList()
        }

        override fun promptBlocks(context: PluginContext, mountedTools: Set<String>): List<PromptBlock> {
            seenByBlocks = context.memoryExclusions
            return emptyList()
        }
    }

    @Test
    fun hostHandsEveryPluginTheExclusionsDeclaredByTheOthers() {
        val reader = ReadsExclusions()
        val meds = MemoryAwarePlugin("meds", memoryExclusions = listOf("用药与补剂"))
        val vitals = MemoryAwarePlugin("vitals", memoryExclusions = listOf("测量数字", "用药与补剂"))

        PluginHost.assemble(listOf(reader, meds, vitals), PluginContext())

        assertEquals(listOf("用药与补剂", "测量数字"), reader.seenByTools)
        assertEquals(listOf("用药与补剂", "测量数字"), reader.seenByBlocks)
    }

    @Test
    fun aPluginThatDeclaresNothingLeavesTheListEmpty() {
        val reader = ReadsExclusions()
        PluginHost.assemble(listOf(reader, notes), PluginContext())
        assertEquals(emptyList<String>(), reader.seenByTools)
    }

    @Test
    fun memoryPolicyCollectsExclusionsAndGuidanceFromActivePlugins() {
        val a = MemoryAwarePlugin("a", memoryExclusions = listOf("X"), memoryGuidance = listOf("g1"))
        val b = MemoryAwarePlugin("b", memoryExclusions = listOf("X", "Y"), memoryGuidance = listOf("g2"))

        val policy = PluginHost.memoryPolicy(listOf(a, b))

        assertEquals(listOf("X", "Y"), policy.exclusions)
        assertEquals(listOf("g1", "g2"), policy.guidance)
    }

    @Test
    fun memoryPolicySkipsDeviceOwnerPluginsForOtherMembers() {
        val owner = MemoryAwarePlugin(
            "owner",
            memoryExclusions = listOf("机主的数据"),
            dataScope = PluginDataScope.DEVICE_OWNER,
        )
        val tenant = MemoryAwarePlugin("tenant", memoryExclusions = listOf("成员的数据"))

        val policy = PluginHost.memoryPolicy(listOf(owner, tenant), PluginContext(isDeviceOwner = false))

        assertEquals(listOf("成员的数据"), policy.exclusions)
    }
}
