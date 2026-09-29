package com.pinapia.vana.agent

import com.pinapia.vana.agentruntime.AgentHookDispatcher
import com.pinapia.vana.agentruntime.AgentLoop
import com.pinapia.vana.agentruntime.AgentModelProfile
import com.pinapia.vana.agentruntime.AgentPlugin
import com.pinapia.vana.agentruntime.AgentPendingInputProvider
import com.pinapia.vana.agentruntime.AgentTurnEvent
import com.pinapia.vana.agentruntime.CapabilityDefinition
import com.pinapia.vana.agentruntime.ContextPolicy
import com.pinapia.vana.agentruntime.ModelSummarizer
import com.pinapia.vana.agentruntime.PluginAssembly
import com.pinapia.vana.agentruntime.PluginContext
import com.pinapia.vana.agentruntime.PluginHost
import com.pinapia.vana.agentruntime.PromptBlock
import com.pinapia.vana.agentruntime.TranscriptCompactor
import com.pinapia.vana.plugins.PromptOrder
import com.pinapia.vana.session.ChatMessage
import com.pinapia.vana.settings.ApiKeyNormalizer
import com.pinapia.vana.settings.AssistantPersona
import com.pinapia.vana.settings.CloudCatalog
import com.pinapia.vana.settings.SecureKeyStore
import com.pinapia.vana.tenant.Tenant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class CloudEngine(
    private val providerId: String,
    private val model: String,
    private val apiKey: String,
    private val tenant: Tenant,
    private val plugins: List<AgentPlugin>,
    private val pluginContext: PluginContext = PluginContext(),
    private val thinkingEnabled: Boolean = true,
    private val persona: AssistantPersona = AssistantPersona.BALANCED,
    private val hooks: AgentHookDispatcher? = null,
    private val goal: String? = null,
) : AgentEngine {
    override val name: String = "云端模型"

    /** 挂哪些工具、拼哪几段,整个由插件决定;引擎只补自己那几段(基础规则、成员身份、目标、插话、人格)。 */
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
            compactor = TranscriptCompactor.healthChat,
            summarizer = ModelSummarizer.healthChat(client),
            policy = ContextPolicy.healthChat,
            maxToolRounds = MAX_TOOL_ROUNDS,
            pendingInput = pendingInput,
            truncatedToolCallNotice = healthChatTruncatedToolCallNotice,
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

    private fun coreBlocks(acceptsInterjections: Boolean): List<PromptBlock> = buildList {
        add(PromptBlock(PromptOrder.BASE, HealthAssistantInstructions.text()))
        tenant.instructionBlock?.let { add(PromptBlock(PromptOrder.TENANT, it)) }
        goal?.trim()?.takeIf { it.isNotEmpty() }?.let {
            add(
                PromptBlock(
                    PromptOrder.GOAL,
                    "这条对话围绕他定下的长期目标「$it」。" +
                        "结合当前对话、记忆、用药和用户记录的测量，把变化和这件事挂上钩，不要另开一个无关的话题。",
                ),
            )
        }
        if (acceptsInterjections) {
            add(
                PromptBlock(
                    PromptOrder.INTERJECTION,
                    "用户可能在你查数据或回答的中途补一句。那是接着当前话题说的，不要当成一个全新的问题从头讲一遍。",
                ),
            )
        }
        if (persona.instruction.isNotBlank()) {
            add(PromptBlock(PromptOrder.PERSONA, persona.instruction))
        }
    }

    companion object {
        private const val MAX_TOOL_ROUNDS = 6

        fun create(
            providerId: String,
            model: String,
            secureKeyStore: SecureKeyStore,
            tenant: Tenant,
            plugins: List<AgentPlugin>,
            pluginContext: PluginContext,
            thinkingEnabled: Boolean,
            persona: AssistantPersona,
            hooks: AgentHookDispatcher? = null,
            goal: String? = null,
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
                tenant = tenant,
                plugins = plugins,
                pluginContext = pluginContext,
                thinkingEnabled = thinkingEnabled,
                persona = persona,
                hooks = hooks,
                goal = goal,
            )
        }
    }
}
