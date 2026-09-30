package com.pinapia.vana.memory

import com.pinapia.vana.VanaApplication
import com.pinapia.vana.agentruntime.MemoryPolicy
import com.pinapia.vana.session.ChatMessage
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
    /**
     * 这一次要收的那几条线程。主对话那一路是「主对话 + 全部侧聊」(记忆只有一份,侧聊里说的和主对话里
     * 说的一样该记;水位线各记各的,不会重复看);侧聊那一路只收它自己。
     */
    private val writers: suspend () -> List<ThreadWriter>,
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

    /** 真的去调模型的那一步。测试注入一个假的。 */
    fun interface Extract {
        suspend operator fun invoke(snapshot: MemorySnapshot, policy: MemoryPolicy, chunk: List<ChatMessage>): List<MemoryOperation>
    }

    /** 每条线程一个结果,按收的顺序。 */
    suspend fun runIfDue(): List<Outcome> {
        if (!settings.memoryEnabled) return listOf(Outcome.NOT_DUE)
        val key = secureKeyStore.apiKey?.trim().orEmpty()
        if (key.isEmpty()) return listOf(Outcome.SKIPPED)
        val provider = settings.providerId.ifBlank { EngineSettings.DEFAULT_PROVIDER }
        val model = settings.model.trim()
        if (model.isEmpty()) return listOf(Outcome.SKIPPED)
        // 没同意过发给这家的不抽。同意几乎必然在(对话发生过);这一句兜「聊完之后换了 provider」那条缝。
        if (!settings.hasProviderConsent(provider)) return listOf(Outcome.SKIPPED)

        val extract = Extract { snapshot, policy, chunk ->
            MemoryExtractor(
                providerId = provider,
                model = model,
                apiKey = key,
                snapshot = snapshot,
                policy = policy,
            ).operations(from = chunk)
        }
        return runIfDue(writers(), memory, environment(), extract)
    }

    companion object {
        /**
         * 一位成员名下的几条线程(主对话和侧聊)挨个收一遍,各收一块。记忆只有一份,水位线各记各的。
         *
         * 撞上 [Outcome.BUSY] 就停:别的后台活占着位子,换一条线程再试也是同一个答案。
         */
        suspend fun runIfDue(
            writers: List<ThreadWriter>,
            memory: MemoryStore,
            env: PluginEnvironment,
            extract: Extract,
        ): List<Outcome> {
            val outcomes = ArrayList<Outcome>()
            for (writer in writers) {
                val outcome = harvest(writer, memory, env, extract)
                outcomes += outcome
                if (outcome == Outcome.BUSY) break
            }
            return outcomes
        }

        /** 一条线程收一块:水位线之后、从最旧的开始。抽完才把水位线推到这一块的末尾。 */
        private suspend fun harvest(
            writer: ThreadWriter,
            memory: MemoryStore,
            env: PluginEnvironment,
            extract: Extract,
        ): Outcome {
            val pending = writer.write { it.messagesAfter(it.meta().harvestedUpToPos) }
            if (MemoryHarvest.userMessageCount(pending.map { it.second }) < MemoryHarvest.MINIMUM_USER_MESSAGES) {
                return Outcome.NOT_DUE
            }
            val chunk = MemoryHarvest.chunk(pending.map { it.second })
            val reachedPos = pending[chunk.size - 1].first
            val snapshot = PluginRegistry.visibleMemory(memory.snapshot(), env.isEnabled)
            val policy = PluginRegistry.memoryPolicy(env)

            val succeeded = BackgroundModelWork.run {
                runCatching {
                    memory.apply(extract(snapshot, policy, chunk))
                    writer.write { it.updateMeta { meta -> meta.copy(harvestedUpToPos = reachedPos) } }
                    true
                }.getOrDefault(false)
            } ?: return Outcome.BUSY
            return if (succeeded) Outcome.DONE else Outcome.FAILED
        }

        /**
         * app 层触发点用(切到后台):没有聊天界面在手,就照当前成员的仓库现造一个。主对话连同全部侧聊。
         * 装配环境只用来回答「哪些插件开着、各自声明了哪些别记进记忆的话题」,不会真的去调工具。
         */
        fun forCurrentTenant(app: VanaApplication): MemoryHarvester {
            val stores = TenantScope.currentStores
            return MemoryHarvester(
                writers = { listOf(stores.threadWriter) + stores.sides.allWriters() },
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
