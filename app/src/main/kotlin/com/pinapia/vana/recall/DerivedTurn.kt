package com.pinapia.vana.recall

import com.pinapia.vana.Features
import com.pinapia.vana.agent.CloudEngine
import com.pinapia.vana.agent.healthChat
import com.pinapia.vana.agentruntime.AgentTurnEvent
import com.pinapia.vana.agentruntime.CapabilityRegistry
import com.pinapia.vana.agentruntime.apply
import com.pinapia.vana.health.HealthTools
import com.pinapia.vana.location.LocationSnapshot
import com.pinapia.vana.medications.MedicationSnapshot
import com.pinapia.vana.memory.MemorySnapshot
import com.pinapia.vana.memory.MemoryStore
import com.pinapia.vana.session.ChatMessage
import com.pinapia.vana.session.ChatSession
import com.pinapia.vana.session.SessionStore
import com.pinapia.vana.session.SessionThread
import com.pinapia.vana.settings.AssistantPersona
import com.pinapia.vana.settings.EngineSettings
import com.pinapia.vana.settings.SecureKeyStore
import com.pinapia.vana.tenant.Tenant
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * 用户不在场时替他问的一轮（待跟进 / 目标周报）。
 */
object DerivedTurn {
    suspend fun run(
        question: String,
        thread: SessionThread,
        threadTitle: String? = null,
        now: Instant = Clock.System.now(),
        supersedingUnread: Boolean = true,
        memoryStore: MemoryStore,
        sessionStore: SessionStore,
        engineSettings: EngineSettings,
        secureKeyStore: SecureKeyStore,
        tenant: Tenant,
        healthTools: HealthTools? = null,
    ): ChatSession? {
        val key = secureKeyStore.apiKey?.trim().orEmpty()
        if (key.isEmpty()) return null
        val model = engineSettings.model.trim()
        if (model.isEmpty()) return null
        val provider = engineSettings.providerId.ifBlank { EngineSettings.DEFAULT_PROVIDER }

        var session = ChatSession(
            threadId = thread.id,
            threadTitle = threadTitle,
            isDerived = true,
            createdAt = now,
            updatedAt = now,
            messages = listOf(
                ChatMessage(role = ChatMessage.Role.USER, text = question),
                ChatMessage(role = ChatMessage.Role.ASSISTANT, text = ""),
            ),
        )

        val memory = if (engineSettings.memoryEnabled) {
            memoryStore.snapshot(now)
        } else {
            MemorySnapshot.empty
        }
        val registry = CapabilityRegistry.healthChat(
            includesHealthTools = Features.HEALTH_CONNECT && tenant.isOwner && healthTools != null,
            allowsMemoryWrites = false,
            allowsMedicationWrites = false,
            allowsRecall = SessionRecallTrigger.mentionsPast(question),
            asksUser = false,
            healthTools = healthTools,
            memoryStore = memoryStore,
            medicationStore = null,
            sessionStore = sessionStore,
            currentSessionId = session.id,
            webSearch = null,
            exerciseLibrary = null,
            memoryEnabled = engineSettings.memoryEnabled,
            medicationsEnabled = false,
        )

        val engine = CloudEngine(
            providerId = provider,
            model = model,
            apiKey = key,
            tenant = tenant,
            memory = memory,
            medications = MedicationSnapshot.empty,
            location = LocationSnapshot.unknown,
            capabilityRegistry = registry,
            thinkingEnabled = false,
            persona = AssistantPersona.BALANCED,
            goal = if (thread.isGoal) threadTitle else null,
        )

        try {
            engine.reply(history = session.messages, pendingInput = null).collect { event ->
                session = applyEvent(session, event)
            }
        } catch (_: Throwable) {
            return null
        }

        val reply = session.messages.lastOrNull() ?: return null
        if (reply.text.isBlank() || reply.textIsPlaceholder) return null

        session = session.copy(updatedAt = Clock.System.now())
        sessionStore.save(session)
        if (supersedingUnread) {
            unreadDerived(on = thread, besides = session.id, inStore = sessionStore)?.let {
                sessionStore.delete(it)
            }
        }
        return session
    }

    suspend fun conclusion(on: SessionThread, inStore: SessionStore): String? {
        val entry = inStore.latestInThread(on) ?: return null
        val session = inStore.load(entry.id) ?: return null
        val last = session.messages.lastOrNull {
            it.role == ChatMessage.Role.ASSISTANT && !it.textIsPlaceholder && it.text.isNotBlank()
        } ?: return null
        return firstSentence(last.text)
    }

    fun unreadDerived(
        on: SessionThread,
        besides: String,
        inStore: SessionStore,
    ): String? {
        for (entry in inStore.entries(on)) {
            if (entry.id == besides) continue
            if (!entry.isDerived) continue
            if (entry.messageCount > 2) continue
            return entry.id
        }
        return null
    }

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

    private fun applyEvent(session: ChatSession, event: AgentTurnEvent): ChatSession {
        val messages = session.messages.toMutableList()
        when (event) {
            is AgentTurnEvent.HistoryCompacted -> {
                val index = messages.indexOfFirst { it.id == event.messageID.toString() }
                if (index >= 0) {
                    messages[index].applyCompaction(event.artifact)
                    messages[index] = messages[index].copy(storedTurn = messages[index].storedTurn)
                }
            }
            else -> {
                val last = messages.lastOrNull() ?: return session
                last.apply(event)
                messages[messages.lastIndex] = last.copy(
                    text = last.text,
                    reasoning = last.reasoning,
                    toolCalls = last.toolCalls.toList(),
                    storedTurn = last.storedTurn,
                    textIsPlaceholder = last.textIsPlaceholder,
                    errorDescription = last.errorDescription,
                )
            }
        }
        return session.copy(messages = messages.toList())
    }
}
