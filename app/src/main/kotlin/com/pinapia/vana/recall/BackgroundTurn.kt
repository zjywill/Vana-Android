package com.pinapia.vana.recall

import com.pinapia.vana.agent.CloudEngine
import com.pinapia.vana.agentruntime.AgentTurnEvent
import com.pinapia.vana.agentruntime.apply
import com.pinapia.vana.memory.MemoryStore
import com.pinapia.vana.plugins.PluginEnvironment
import com.pinapia.vana.plugins.PluginRegistry
import com.pinapia.vana.plugins.PluginRoute
import com.pinapia.vana.session.ChatMessage
import com.pinapia.vana.settings.AssistantPersona
import com.pinapia.vana.settings.EngineSettings
import com.pinapia.vana.settings.SecureKeyStore
import com.pinapia.vana.tenant.Tenant
import com.pinapia.vana.thread.ThreadWriter
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * 用户不在场时替他问的一轮(待跟进到期的回访)。
 *
 * 形状:**非阻塞、独立上下文、失败即放弃**。它不碰聊天的窗口——自己造一份两条消息的上下文,
 * 只带记忆(只读)和召回(读线程档案)。结论**不存成另一条会话**(以前是一个 `isDerived` 的会话文件,
 * 出现在会话列表里),由调用方作为一条主动消息追加进那条线程。
 *
 * 每一条会出设备的路都要过 provider 同意的闸——后台替他发一轮,更得先问过。
 */
object BackgroundTurn {
    suspend fun run(
        question: String,
        now: Instant = Clock.System.now(),
        memoryStore: MemoryStore,
        writer: ThreadWriter,
        engineSettings: EngineSettings,
        secureKeyStore: SecureKeyStore,
        tenant: Tenant,
    ): String? {
        val key = secureKeyStore.apiKey?.trim().orEmpty()
        if (key.isEmpty()) return null
        val model = engineSettings.model.trim()
        if (model.isEmpty()) return null
        val provider = engineSettings.providerId.ifBlank { EngineSettings.DEFAULT_PROVIDER }
        if (!engineSettings.hasProviderConsent(provider)) return null

        val engine = engine(
            provider = provider,
            model = model,
            apiKey = key,
            environment = backgroundEnvironment(now, memoryStore, writer, engineSettings, tenant),
        )

        var messages = listOf(
            ChatMessage(role = ChatMessage.Role.USER, text = question),
            ChatMessage(role = ChatMessage.Role.ASSISTANT, text = ""),
        )
        try {
            engine.reply(history = messages, pendingInput = null).collect { event ->
                messages = applyEvent(messages, event)
            }
        } catch (_: Throwable) {
            return null
        }
        val reply = messages.lastOrNull() ?: return null
        if (reply.text.isBlank() || reply.textIsPlaceholder) return null
        return reply.text.trim()
    }

    /** 后台路(用户不在场)的装配输入:只读记忆加召回。不带搜索和读网页:多挂一样就多花一份钱。 */
    private fun backgroundEnvironment(
        now: Instant,
        memoryStore: MemoryStore,
        writer: ThreadWriter,
        engineSettings: EngineSettings,
        tenant: Tenant,
    ) = PluginEnvironment(
        isEnabled = engineSettings::isPluginEnabled,
        tenant = tenant,
        archive = writer.archive,
        hiddenBeforePos = { writer.store.meta().windowStartPos },
        memoryStore = memoryStore,
        memorySnapshot = { memoryStore.snapshot(now) },
    )

    /** 后台路的引擎:显式关掉思考(辅助调用的规矩),只读。 */
    private fun engine(
        provider: String,
        model: String,
        apiKey: String,
        environment: PluginEnvironment,
    ): CloudEngine = CloudEngine(
        providerId = provider,
        model = model,
        apiKey = apiKey,
        plugins = PluginRegistry.agentPlugins(environment, PluginRoute.BACKGROUND),
        pluginContext = PluginRegistry.backgroundContext(),
        thinkingEnabled = false,
        persona = AssistantPersona.BALANCED,
    )

    fun firstSentence(of: String): String {
        val trimmed = of.trim()
        val end = trimmed.indexOfFirst { it in "。！？!?" }
        if (end >= 0) {
            val sentence = trimmed.take(end + 1)
            if (sentence.length >= 8) return sentence
        }
        return if (trimmed.length <= 60) trimmed else trimmed.take(60) + "…"
    }

    fun naturalize(promise: String): String =
        promise.trim().trimEnd('。', '．', '.', '！', '!', '？', '?', '；', ';', '，', ',', '、', ' ')

    internal fun applyEvent(messages: List<ChatMessage>, event: AgentTurnEvent): List<ChatMessage> {
        val list = messages.toMutableList()
        when (event) {
            is AgentTurnEvent.HistoryCompacted -> {
                val index = list.indexOfFirst { it.id == event.messageID.toString() }
                if (index >= 0) {
                    list[index].applyCompaction(event.artifact)
                    list[index] = list[index].copy(storedTurn = list[index].storedTurn)
                }
            }
            else -> {
                val last = list.lastOrNull() ?: return messages
                last.apply(event)
                list[list.lastIndex] = last.copy(
                    text = last.text,
                    reasoning = last.reasoning,
                    toolCalls = last.toolCalls.toList(),
                    storedTurn = last.storedTurn,
                    textIsPlaceholder = last.textIsPlaceholder,
                    errorDescription = last.errorDescription,
                )
            }
        }
        return list.toList()
    }
}
