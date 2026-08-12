package com.pinapia.vana.recall

import com.pinapia.vana.health.HealthTools
import com.pinapia.vana.memory.MemoryItem
import com.pinapia.vana.memory.MemoryStore
import com.pinapia.vana.session.SessionStore
import com.pinapia.vana.session.SessionThread
import com.pinapia.vana.settings.EngineSettings
import com.pinapia.vana.settings.SecureKeyStore
import com.pinapia.vana.tenant.Tenant
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

object FollowUpRunner {
    /** 同一条待跟进，一天最多自己跑一次。 */
    const val MINIMUM_INTERVAL_MS = 86_400_000L

    suspend fun pending(
        now: Instant = Clock.System.now(),
        memoryStore: MemoryStore,
        sessionStore: SessionStore,
        memoryEnabled: Boolean,
    ): MemoryItem? {
        if (!memoryEnabled) return null
        for (item in memoryStore.snapshot(now).due(at = now)) {
            val previous = sessionStore.latestInThread(SessionThread.followUp(item.id))
            if (previous == null) return item
            if (now.toEpochMilliseconds() - previous.updatedAt.toEpochMilliseconds() >= MINIMUM_INTERVAL_MS) {
                return item
            }
        }
        return null
    }

    suspend fun run(
        followUp: MemoryItem,
        now: Instant = Clock.System.now(),
        memoryStore: MemoryStore,
        sessionStore: SessionStore,
        engineSettings: EngineSettings,
        secureKeyStore: SecureKeyStore,
        tenant: Tenant,
        healthTools: HealthTools? = null,
    ): Boolean {
        return DerivedTurn.run(
            question = question(forFollowUp = followUp),
            thread = SessionThread.followUp(followUp.id),
            now = now,
            supersedingUnread = false,
            memoryStore = memoryStore,
            sessionStore = sessionStore,
            engineSettings = engineSettings,
            secureKeyStore = secureKeyStore,
            tenant = tenant,
            healthTools = healthTools,
        ) != null
    }

    suspend fun conclusion(forFollowUp: MemoryItem, inStore: SessionStore): String? =
        DerivedTurn.conclusion(on = SessionThread.followUp(forFollowUp.id), inStore = inStore)

    fun question(forFollowUp: MemoryItem): String =
        "我们说好这时候回头看的：${DerivedTurn.naturalize(forFollowUp.text)}。现在怎么样了？" +
            "查一下数据，两三句话说清楚现在是什么情况、和当初比有没有变化。"
}
