package com.pinapia.vana.session

import java.util.UUID
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * 一条跨天延续的会话线。
 */
sealed class SessionThread {
    data object CheckIn : SessionThread()
    data class FollowUp(val itemId: String) : SessionThread()
    data class Goal(val goalId: String) : SessionThread()
    data class Medication(val medicationId: String) : SessionThread()

    val id: String
        get() = when (this) {
            CheckIn -> "checkin"
            is FollowUp -> "followup:$itemId"
            is Goal -> "goal:$goalId"
            is Medication -> "med:$medicationId"
        }

    val title: String
        get() = when (this) {
            CheckIn -> "每日 check-in"
            is FollowUp -> "说好回头看的事"
            is Goal -> "长期目标"
            is Medication -> "药和补剂"
        }

    val isGoal: Boolean get() = this is Goal
    val isMedication: Boolean get() = this is Medication
    val isLongRunning: Boolean get() = isGoal || isMedication

    companion object {
        fun parse(id: String?): SessionThread? {
            if (id.isNullOrBlank()) return null
            if (id == "checkin") return CheckIn
            fun uuidAfter(prefix: String): String? {
                if (!id.startsWith(prefix)) return null
                val rest = id.removePrefix(prefix)
                return runCatching { UUID.fromString(rest).toString() }.getOrNull()?.let { rest }
            }
            uuidAfter("followup:")?.let { return FollowUp(it) }
            uuidAfter("goal:")?.let { return Goal(it) }
            uuidAfter("med:")?.let { return Medication(it) }
            return null
        }

        fun goal(id: String = UUID.randomUUID().toString()) = Goal(id)
        fun followUp(id: String) = FollowUp(id)
        fun medication(id: String) = Medication(id)
    }
}

data class GoalSummary(
    val threadId: String,
    val title: String,
    val updatedAt: Instant,
    val latestSessionId: String,
    val segmentCount: Int,
    val messageCount: Int,
) {
    val id: String get() = threadId
    val thread: SessionThread? get() = SessionThread.parse(threadId)
}

object SessionThreadPolicy {
    const val MAX_IDLE_DAYS = 4
    const val MAX_GOAL_IDLE_DAYS = 21
    const val MAX_MESSAGES = 40

    fun canContinue(entry: SessionIndexEntry, at: Instant = Clock.System.now()): Boolean {
        if (entry.isEmpty || entry.messageCount >= MAX_MESSAGES) return false
        val idleDays = if (entry.thread?.isLongRunning == true) MAX_GOAL_IDLE_DAYS else MAX_IDLE_DAYS
        val idleMs = idleDays.toLong() * 86_400_000L
        return at.toEpochMilliseconds() - entry.updatedAt.toEpochMilliseconds() < idleMs
    }
}

data class SessionIndexEntry(
    val id: String,
    val title: String,
    val updatedAt: Instant,
    val messageCount: Int,
    val threadId: String? = null,
    val threadTitle: String? = null,
    val isDerived: Boolean = false,
    val topicId: String? = null,
    /** 这条会话里查过哪几个工具,去重且保序。 */
    val toolNames: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = messageCount == 0
    val thread: SessionThread? get() = SessionThread.parse(threadId)
}
