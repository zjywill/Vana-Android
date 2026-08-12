package com.pinapia.vana.recall

import com.pinapia.vana.health.HealthTools
import com.pinapia.vana.memory.MemoryStore
import com.pinapia.vana.session.GoalSummary
import com.pinapia.vana.session.SessionStore
import com.pinapia.vana.settings.EngineSettings
import com.pinapia.vana.settings.SecureKeyStore
import com.pinapia.vana.tenant.Tenant
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

object GoalDigest {
    const val MINIMUM_INTERVAL_MS = 7L * 86_400_000L
    const val ABANDONED_AFTER_MS = 30L * 86_400_000L

    suspend fun pending(
        now: Instant = Clock.System.now(),
        sessionStore: SessionStore,
    ): GoalSummary? {
        for (goal in sessionStore.goals()) {
            if (now.toEpochMilliseconds() - goal.updatedAt.toEpochMilliseconds() >= ABANDONED_AFTER_MS) {
                continue
            }
            val thread = goal.thread ?: continue
            val lastDigest = sessionStore.entries(thread).firstOrNull { it.isDerived }
            if (lastDigest == null) return goal
            if (now.toEpochMilliseconds() - lastDigest.updatedAt.toEpochMilliseconds() >= MINIMUM_INTERVAL_MS) {
                return goal
            }
        }
        return null
    }

    suspend fun run(
        goal: GoalSummary,
        now: Instant = Clock.System.now(),
        memoryStore: MemoryStore,
        sessionStore: SessionStore,
        engineSettings: EngineSettings,
        secureKeyStore: SecureKeyStore,
        tenant: Tenant,
        healthTools: HealthTools? = null,
    ): Boolean {
        val thread = goal.thread ?: return false
        return DerivedTurn.run(
            question = question(forGoal = goal),
            thread = thread,
            threadTitle = goal.title,
            now = now,
            memoryStore = memoryStore,
            sessionStore = sessionStore,
            engineSettings = engineSettings,
            secureKeyStore = secureKeyStore,
            tenant = tenant,
            healthTools = healthTools,
        ) != null
    }

    suspend fun conclusion(forGoal: GoalSummary, inStore: SessionStore): String? {
        val thread = forGoal.thread ?: return null
        return DerivedTurn.conclusion(on = thread, inStore = inStore)
    }

    fun question(forGoal: GoalSummary): String =
        "关于「${forGoal.title}」这件事：先翻一下我们之前聊到哪儿、当时定的是什么，" +
            "再查现在的数据，两三句话说清楚这段时间有没有进展、和上次比变了多少。" +
            "没有明显变化就直说没有，不用凑一个好消息出来。"
}
