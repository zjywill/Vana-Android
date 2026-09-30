package com.pinapia.vana.agent

import com.pinapia.vana.agentruntime.AgentHookDispatcher
import com.pinapia.vana.agentruntime.AgentLoop
import com.pinapia.vana.agentruntime.AgentModelProfile
import com.pinapia.vana.agentruntime.AgentPlugin
import com.pinapia.vana.agentruntime.AgentPendingInputProvider
import com.pinapia.vana.agentruntime.AgentTurnEvent
import com.pinapia.vana.agentruntime.CapabilityDefinition
import com.pinapia.vana.agentruntime.ContextPolicy
import com.pinapia.vana.agentruntime.PluginAssembly
import com.pinapia.vana.agentruntime.PluginContext
import com.pinapia.vana.agentruntime.PluginHost
import com.pinapia.vana.agentruntime.PromptBlock
import com.pinapia.vana.agentruntime.TokenEstimate
import com.pinapia.vana.agentruntime.TranscriptCompactor
import com.pinapia.vana.plugins.PromptOrder
import com.pinapia.vana.session.ChatMessage
import com.pinapia.vana.settings.ApiKeyNormalizer
import com.pinapia.vana.settings.AssistantPersona
import com.pinapia.vana.settings.CloudCatalog
import com.pinapia.vana.settings.SecureKeyStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class CloudEngine(
    private val providerId: String,
    private val model: String,
    private val apiKey: String,
    private val plugins: List<AgentPlugin>,
    private val pluginContext: PluginContext = PluginContext(),
    private val thinkingEnabled: Boolean = true,
    private val persona: AssistantPersona = AssistantPersona.BALANCED,
    private val hooks: AgentHookDispatcher? = null,
    private val maxToolRounds: Int = DEFAULT_TOOL_ROUNDS,
    /** 这一轮在哪条侧聊里(它的名字)。主对话、后台、不留痕都是 null。 */
    private val sideChatTitle: String? = null,
) : AgentEngine {
    override val name: String = "云端模型"

    /** 挂哪些工具、拼哪几段,整个由插件决定;引擎只补自己那几段(基础规则、今天、目标、插话、人格)。 */
    private fun assemble(acceptsInterjections: Boolean): PluginAssembly =
        PluginHost.assemble(
            plugins = plugins,
            context = pluginContext,
            coreBlocks = coreBlocks(acceptsInterjections),
        )

    override val supportsVision: Boolean
        get() = CloudCatalog.model(model, providerId)?.supportsVision ?: false

    override fun reply(
        history: List<ChatMessage>,
        pendingInput: AgentPendingInputProvider?,
    ): Flow<AgentTurnEvent> = flow {
        val provider = CloudCatalog.provider(providerId)
            ?: throw AgentError.NeedsModelSelection
        val wire = provider.wireProtocol
            ?: throw AgentError.NeedsModelSelection
        val modelInfo = CloudCatalog.model(model, providerId)
            ?: CloudCatalog.ModelInfo(id = model)
        val client = OpenAICompatibleModelClient(
            profile = AgentModelProfile(
                providerId = providerId,
                modelId = model,
                contextWindow = modelInfo.contextWindow,
                maxOutputTokens = modelInfo.maxOutputTokens,
            ),
            apiKey = apiKey,
            baseUrl = provider.apiBaseUrl,
            wireProtocol = wire,
            thinkingEnabled = thinkingEnabled,
            supportsReasoning = modelInfo.supportsReasoning,
        )
        val assembly = assemble(acceptsInterjections = pendingInput != null)
        val loop = AgentLoop(
            client = client,
            capabilities = assembly.registry,
            systemInstruction = assembly.instruction(),
            compactor = TranscriptCompactor.chat,
            // 聊天路径不主动叫模型写摘要:历史靠「窗口 + 记忆 + 可检索的档案」,递归摘要会漂,还多花一路钱。
            // 摘要器只留给别处(见 AgentEngine.kt),这里给 null 就退回纯机械压缩。
            summarizer = null,
            policy = ContextPolicy.chat,
            maxToolRounds = maxToolRounds,
            pendingInput = pendingInput,
            truncatedToolCallNotice = chatTruncatedToolCallNotice,
            hooks = hooks,
        )
        try {
            loop.run(history.toAgentDTOs()).collect { emit(it) }
        } catch (error: Throwable) {
            throw AgentError.wrapping(error)
        }
    }

    fun systemInstruction(acceptsInterjections: Boolean = false): String =
        assemble(acceptsInterjections).instruction()

    /** 这一轮会挂出去的工具定义,按发出去的顺序。 */
    fun toolDefinitions(): List<CapabilityDefinition> =
        assemble(acceptsInterjections = false).registry.definitions

    /**
     * 每一轮请求都要带的固定开销:system 段加全部工具定义的 token 估计。窗口预算按整个请求算,先把它扣掉。
     *
     * 只装配一次;按聊天路径的实际情况算(会接受插话,所以有那一小段插话说明)。工具的参数说明按**发出去的
     * JSON** 计,不是 Kotlin 的 `toString()`——后者带着 `ObjectValue(value={…StringValue(value=…` 这类
     * 外壳,比真实大小长得多,会把窗口无谓地挤窄。
     */
    fun requestOverheadTokens(): Int {
        val assembly = assemble(acceptsInterjections = true)
        return TokenEstimate.text(assembly.instruction()) +
            assembly.registry.definitions.sumOf(TokenEstimate::definition)
    }

    /**
     * 引擎自己的几段:身份与规则、插话、侧聊说明、人格(静态区),今天(易变区)。目标由任务插件贡献。
     * 成员身份(替家人问)不在这里:那是健康插件的事,由 `FamilyPlugin` 贡献。
     */
    private fun coreBlocks(acceptsInterjections: Boolean): List<PromptBlock> = buildList {
        add(PromptBlock(PromptOrder.BASE, CoreInstructions.text()))
        add(PromptBlock(PromptOrder.TODAY, CoreInstructions.today()))
        if (acceptsInterjections) {
            add(
                PromptBlock(
                    PromptOrder.INTERJECTION,
                    "用户可能在你查资料或回答的中途补一句。那是接着当前话题说的，不要当成一个全新的问题从头讲一遍。",
                ),
            )
        }
        if (sideChatTitle != null) {
            add(PromptBlock(PromptOrder.SIDE_CHAT, CoreInstructions.sideChat(sideChatTitle)))
        }
        if (persona.instruction.isNotBlank()) {
            add(PromptBlock(PromptOrder.PERSONA, persona.instruction))
        }
    }

    companion object {
        const val DEFAULT_TOOL_ROUNDS = 6

        fun create(
            providerId: String,
            model: String,
            secureKeyStore: SecureKeyStore,
            plugins: List<AgentPlugin>,
            pluginContext: PluginContext,
            thinkingEnabled: Boolean,
            persona: AssistantPersona,
            hooks: AgentHookDispatcher? = null,
            sideChatTitle: String? = null,
        ): CloudEngine {
            val normalized = ApiKeyNormalizer.normalize(secureKeyStore.apiKey)
            when {
                normalized.error?.contains("非法字符") == true ->
                    throw AgentError.InvalidAPIKey(normalized.error)
                !normalized.isValid ->
                    throw AgentError.NeedsAPIKey
            }
            if (providerId.isBlank() || model.isBlank()) throw AgentError.NeedsModelSelection
            return CloudEngine(
                providerId = providerId,
                model = model,
                apiKey = normalized.value,
                plugins = plugins,
                pluginContext = pluginContext,
                thinkingEnabled = thinkingEnabled,
                persona = persona,
                hooks = hooks,
                sideChatTitle = sideChatTitle,
            )
        }
    }
}
