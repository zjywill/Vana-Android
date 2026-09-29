package com.pinapia.vana.agentruntime

/**
 * 一个工具会碰到什么。装配时按它统一过滤,而不是每个调用点各传一串 `allowsXWrites`。
 *
 * 「隐私会话」按写入路径定义,不按名字:它丢掉的就是全部 [WRITE_LOCAL]。以前是三个参数
 * 各堵一条路,加一个会写盘的工具就要记得在每个调用点再加一个参数——漏一次,
 * 「不保存」就成了假话。
 */
enum class ToolEffect {
    /** 只读本机数据或打包资源。 */
    READ,

    /** 往本机盘上写(记忆、用药表、测量卡片)。隐私会话和后台派生都不挂。 */
    WRITE_LOCAL,

    /** 发到模型以外的第三方(网页搜索)。 */
    EXTERNAL,

    /** 产出要等用户点一下才有意义(ask_user 那张卡)。后台派生没人在场,不挂。 */
    NEEDS_USER,
}

/**
 * 插件读的是谁的数据。iOS 上 HealthKit 属于机主、不属于当前选中的成员;
 * Android 没有这类数据源,现在全是 [TENANT],字段留着是为了两端清单同形。
 */
enum class PluginDataScope { TENANT, DEVICE_OWNER }

/**
 * 工具什么时候挂出去。[WhenUnlocked] 由 app 判(比如用户提起了「上次」),
 * 解锁之后在这条会话里粘住不撤——一轮挂一轮撤会把 prompt 缓存的前缀反复打掉。
 */
sealed interface MountPolicy {
    data object Always : MountPolicy
    data class WhenUnlocked(val trigger: String) : MountPolicy
}

class PluginTool(
    val definition: CapabilityDefinition,
    val effects: Set<ToolEffect>,
    val mount: MountPolicy = MountPolicy.Always,
    val execute: suspend (CapabilityInvocation) -> CapabilityExecutionResult,
) {
    val name: String get() = definition.name

    companion object {
        /**
         * 把一个现成的 registry 拆成逐个工具。[effects] 按工具名给出副作用;
         * 执行仍然走原 registry 的闭包。
         */
        fun from(
            registry: CapabilityRegistry,
            mount: MountPolicy = MountPolicy.Always,
            effects: (String) -> Set<ToolEffect>,
        ): List<PluginTool> = registry.definitions.map { definition ->
            PluginTool(
                definition = definition,
                effects = effects(definition.name),
                mount = mount,
                execute = registry::execute,
            )
        }
    }
}

/**
 * 一段进 system 段的文字。[order] 决定它排在哪:同一个插件的几段可以分散在不同位置
 * (比如用药名单排在前面,「怎么用 log_medication」排在工具说明那一片)。
 * 排序是稳定的,同 order 按插件注册顺序。
 */
data class PromptBlock(val order: Int, val text: String)

data class PluginContext(
    /** 隐私会话:不往盘上写。 */
    val isPrivate: Boolean = false,
    /** 后台派生:用户不在场。 */
    val isBackground: Boolean = false,
    /** 当前成员是不是机主。 */
    val isDeviceOwner: Boolean = true,
    /** app 已经判定解锁的触发器(见 [MountPolicy.WhenUnlocked])。 */
    val unlockedTriggers: Set<String> = emptySet(),
)

/**
 * 插件:一组工具加几段提示词,安装、开关、隔离的单位。
 *
 * 插件自己的开关(设置里关掉用药表)和数据由构造它的那一侧决定——关掉了就不构造,
 * 或者 [tools] 返回空。「不挂出去」,而不是「挂了返回空」:给一个只会报错的工具,
 * 模型得先调一次才知道不行。
 */
interface AgentPlugin {
    val id: String
    val dataScope: PluginDataScope get() = PluginDataScope.TENANT

    /** 平台权限声明(iOS 的 HealthKit 读权限一类)。Android 现在一个都没有。 */
    val permissions: List<String> get() = emptyList()

    /** 抽记忆时要让路的话题:这个插件自己存着的东西,别在记忆里再存一份。 */
    val memoryExclusions: List<String> get() = emptyList()

    fun tools(context: PluginContext): List<PluginTool> = emptyList()

    /**
     * [mountedTools] 是装配完之后真的挂出去的全部工具名(所有插件的)。
     * 工具说明照着它拼:工具没挂出去,那段「该调 xxx」也不发。
     */
    fun promptBlocks(context: PluginContext, mountedTools: Set<String>): List<PromptBlock> = emptyList()
}

class PluginAssembly(
    val registry: CapabilityRegistry,
    val blocks: List<PromptBlock>,
) {
    /** 按 order 稳定排序后用空行接起来。 */
    fun instruction(): String = blocks
        .sortedBy { it.order }
        .map { it.text }
        .filter { it.isNotEmpty() }
        .joinToString(separator = "\n\n")
}

object PluginHost {
    /** 这个上下文里某个工具能不能挂出去。 */
    fun allows(tool: PluginTool, context: PluginContext): Boolean {
        if (context.isPrivate && ToolEffect.WRITE_LOCAL in tool.effects) return false
        if (context.isBackground &&
            (ToolEffect.WRITE_LOCAL in tool.effects || ToolEffect.NEEDS_USER in tool.effects)
        ) {
            return false
        }
        return when (val mount = tool.mount) {
            MountPolicy.Always -> true
            is MountPolicy.WhenUnlocked -> mount.trigger in context.unlockedTriggers
        }
    }

    fun assemble(
        plugins: List<AgentPlugin>,
        context: PluginContext,
        coreBlocks: List<PromptBlock> = emptyList(),
    ): PluginAssembly {
        val active = plugins.filter {
            it.dataScope == PluginDataScope.TENANT || context.isDeviceOwner
        }
        val tools = active.flatMap { plugin -> plugin.tools(context).filter { allows(it, context) } }
        val byName = tools.associateBy { it.name }
        val registry = if (tools.isEmpty()) {
            CapabilityRegistry.empty
        } else {
            CapabilityRegistry(definitions = tools.map { it.definition }) { invocation ->
                val tool = byName[invocation.name]
                    ?: return@CapabilityRegistry CapabilityExecutionResult(
                        output = AgentToolOutput(
                            kind = AgentToolOutput.Kind.TEXT,
                            text = "不支持名为 ${invocation.name} 的工具。",
                        ),
                        isError = true,
                    )
                tool.execute(invocation)
            }
        }
        val mounted = byName.keys
        val blocks = coreBlocks + active.flatMap { it.promptBlocks(context, mounted) }
        return PluginAssembly(registry = registry, blocks = blocks)
    }
}
