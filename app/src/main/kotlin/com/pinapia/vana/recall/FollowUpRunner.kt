package com.pinapia.vana.recall

import com.pinapia.vana.memory.MemoryItem
import com.pinapia.vana.memory.MemoryStore
import com.pinapia.vana.session.ChatMessage
import com.pinapia.vana.settings.EngineSettings
import com.pinapia.vana.settings.SecureKeyStore
import com.pinapia.vana.tenant.Tenant
import com.pinapia.vana.thread.ThreadStore
import com.pinapia.vana.thread.ThreadWriter
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * 到期的「待跟进」:不在场时替他回头看一眼,得出结论。
 *
 * 「跑过没有」记在线程 meta 的 `derived` 里(条目 id → 什么时候跑的、得出的一句结论),
 * 不再靠「有没有一个 followup 线的会话文件」判断;结论作为一条 [ChatMessage.Origin.FOLLOW_UP]
 * 主动消息追加进那条线程——他打开 app 就在对话末尾,早上那条通知里带的也是这一句结论。
 */
object FollowUpRunner {
    /** 同一条待跟进，一天最多自己跑一次。 */
    const val MINIMUM_INTERVAL_MS = 86_400_000L

    private fun key(id: String) = "followup:$id"

    suspend fun pending(
        now: Instant = Clock.System.now(),
        memoryStore: MemoryStore,
        writer: ThreadWriter,
        memoryEnabled: Boolean,
    ): MemoryItem? {
        if (!memoryEnabled) return null
        val derived = writer.write { it.meta().derived }
        for (item in memoryStore.snapshot(now).due(at = now)) {
            val previous = derived[key(item.id)]
            if (previous == null) return item
            if (now.toEpochMilliseconds() - previous.at.toEpochMilliseconds() >= MINIMUM_INTERVAL_MS) return item
        }
        return null
    }

    suspend fun run(
        followUp: MemoryItem,
        now: Instant = Clock.System.now(),
        memoryStore: MemoryStore,
        writer: ThreadWriter,
        engineSettings: EngineSettings,
        secureKeyStore: SecureKeyStore,
        tenant: Tenant,
    ): Boolean {
        val text = BackgroundTurn.run(
            question = question(forFollowUp = followUp),
            now = now,
            memoryStore = memoryStore,
            writer = writer,
            engineSettings = engineSettings,
            secureKeyStore = secureKeyStore,
            tenant = tenant,
        ) ?: return false
        val conclusion = BackgroundTurn.firstSentence(text)
        writer.write { store ->
            store.updateMeta { meta ->
                meta.copy(derived = meta.derived + (key(followUp.id) to ThreadStore.DerivedRecord(at = now, conclusion = conclusion)))
            }
        }
        writer.postProactive(
            ChatMessage(role = ChatMessage.Role.ASSISTANT, text = text, origin = ChatMessage.Origin.FOLLOW_UP),
        )
        return true
    }

    suspend fun conclusion(forFollowUp: MemoryItem, writer: ThreadWriter): String? =
        writer.write { it.meta().derived[key(forFollowUp.id)]?.conclusion }

    fun question(forFollowUp: MemoryItem): String =
        "我们说好这时候回头看的：${BackgroundTurn.naturalize(forFollowUp.text)}。现在怎么样了？" +
            "结合之后的对话和记忆，两三句话说清楚现在是什么情况、和当初比有没有变化。"
}
