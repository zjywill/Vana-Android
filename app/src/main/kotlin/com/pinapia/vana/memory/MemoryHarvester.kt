package com.pinapia.vana.memory

import com.pinapia.vana.VanaApplication
import com.pinapia.vana.plugins.PluginEnvironment
import com.pinapia.vana.plugins.PluginRegistry
import com.pinapia.vana.recall.BackgroundModelWork
import com.pinapia.vana.settings.EngineSettings
import com.pinapia.vana.settings.SecureKeyStore
import com.pinapia.vana.tenant.TenantScope
import com.pinapia.vana.thread.ThreadWriter

/**
 * 记忆收割,和窗口**解耦**。
 *
 * 以前抽取只在「切会话」时触发,一条永远的对话里没有这个事件;而且每次把整段重发、只留末尾 6000 字,
 * 既会把抽过的再看一遍,又会漏掉更早还没抽的。现在线程 meta 里记一个**水位线**(`harvestedUpToPos`):
 * 只喂水位线之后的消息,从最旧的开始按转写字符数分块,一次一块;抽完才把水位线推到这一块的末尾。
 *
 * 不阻塞窗口淘汰:档案逐字保留,没来得及抽的以后仍抽得到。失败即放弃、水位线不动,下一个触发点再来。
 * 全部走 [BackgroundModelWork] 那把「同时只准跑一件」的锁,拿不到位子就不排队。
 * 每一条会出设备的路都要过 provider 同意的闸——这里也一样。
 */
class MemoryHarvester(
    private val writer: ThreadWriter,
    private val memory: MemoryStore,
    private val settings: EngineSettings,
    private val secureKeyStore: SecureKeyStore,
    private val environment: () -> PluginEnvironment,
) {
    enum class Outcome {
        /** 没到该抽的时候(关着记忆、攒得不够)。 */
        NOT_DUE,

        /** 条件不具备:没配 key、没同意过发给这家。 */
        SKIPPED,

        /** 别的后台模型活正占着位子。 */
        BUSY,
        DONE,
        FAILED,
    }

    suspend fun runIfDue(): Outcome {
        if (!settings.memoryEnabled) return Outcome.NOT_DUE
        val key = secureKeyStore.apiKey?.trim().orEmpty()
        if (key.isEmpty()) return Outcome.SKIPPED
        val provider = settings.providerId.ifBlank { EngineSettings.DEFAULT_PROVIDER }
        val model = settings.model.trim()
        if (model.isEmpty()) return Outcome.SKIPPED
        // 没同意过发给这家的不抽。同意几乎必然在(对话发生过);这一句兜「聊完之后换了 provider」那条缝。
        if (!settings.hasProviderConsent(provider)) return Outcome.SKIPPED

        val pending = writer.write { it.messagesAfter(it.meta().harvestedUpToPos) }
        if (MemoryHarvest.userMessageCount(pending.map { it.second }) < MemoryHarvest.MINIMUM_USER_MESSAGES) {
            return Outcome.NOT_DUE
        }
        val chunk = MemoryHarvest.chunk(pending.map { it.second })
        val reachedPos = pending[chunk.size - 1].first
        val env = environment()
        val snapshot = PluginRegistry.visibleMemory(memory.snapshot(), env.isEnabled)
        val policy = PluginRegistry.memoryPolicy(env)

        val succeeded = BackgroundModelWork.run {
            runCatching {
                val operations = MemoryExtractor(
                    providerId = provider,
                    model = model,
                    apiKey = key,
                    snapshot = snapshot,
                    policy = policy,
                ).operations(from = chunk)
                memory.apply(operations)
                writer.write { it.updateMeta { meta -> meta.copy(harvestedUpToPos = reachedPos) } }
                true
            }.getOrDefault(false)
        } ?: return Outcome.BUSY
        return if (succeeded) Outcome.DONE else Outcome.FAILED
    }

    companion object {
        /**
         * app 层触发点用(切到后台):没有聊天界面在手,就照当前成员的仓库现造一个。
         * 装配环境只用来回答「哪些插件开着、各自声明了哪些别记进记忆的话题」,不会真的去调工具。
         */
        fun forCurrentTenant(app: VanaApplication): MemoryHarvester {
            val stores = TenantScope.currentStores
            return MemoryHarvester(
                writer = stores.threadWriter,
                memory = stores.memory,
                settings = app.engineSettings,
                secureKeyStore = app.secureKeyStore,
                environment = {
                    PluginEnvironment(
                        isEnabled = app.engineSettings::isPluginEnabled,
                        tenant = TenantScope.current,
                        archive = null,
                        memoryStore = stores.memory,
                        medicationStore = stores.medications,
                        measurementStore = stores.measurements,
                    )
                },
            )
        }
    }
}
