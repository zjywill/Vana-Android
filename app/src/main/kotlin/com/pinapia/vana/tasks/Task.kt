package com.pinapia.vana.tasks

import java.util.UUID
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 两种 kind 共用一个形状。以前还有第三种 `job`(交给子 agent 在后台做的事),2026-09-30 连同子 agent
 * 一起撤掉了——独立的活由用户自己开的侧聊来做。盘上留下来的那几条认不出 kind,由 [TaskStore] 原样留着、
 * 不再显示。
 */
@Serializable
enum class TaskKind {
    /** 用户自己长期在做的一件事(备半马、学吉他)。取代原来的「目标线」会话。 */
    @SerialName("goal") GOAL,

    /** 到点发通知,**到点不调模型**。 */
    @SerialName("reminder") REMINDER,
}

/**
 * 两种共用一组状态,各取其中几个:
 * - REMINDER:[QUEUED](等着到点)→[DONE](响过了)/[CANCELLED];
 * - GOAL:[RUNNING](进行中)→[DONE]/[CANCELLED]。
 */
@Serializable
enum class TaskStatus {
    @SerialName("queued") QUEUED,
    @SerialName("running") RUNNING,
    @SerialName("done") DONE,
    @SerialName("cancelled") CANCELLED,
    ;

    val isActive: Boolean get() = this == QUEUED || this == RUNNING
}

@Serializable
enum class Repeat {
    @SerialName("none") NONE,
    @SerialName("daily") DAILY,
    @SerialName("weekly") WEEKLY,
}

@Serializable
data class PlanItem(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val done: Boolean = false,
)

/** 目标上的一条进展记录:什么时候、记了什么。 */
@Serializable
data class GoalNote(val at: Instant, val text: String)

@Serializable
data class Task(
    val id: String = UUID.randomUUID().toString(),
    val kind: TaskKind,
    val title: String,
    val status: TaskStatus,
    val createdAt: Instant = Clock.System.now(),
    val updatedAt: Instant = createdAt,
    // ---- REMINDER ----
    val dueAt: Instant? = null,
    val repeat: Repeat = Repeat.NONE,
    // ---- GOAL ----
    val why: String = "",
    val plan: List<PlanItem> = emptyList(),
    val notes: List<GoalNote> = emptyList(),
) {
    val isActive: Boolean get() = status.isActive

    /** 短编号:给模型和用户指到某一条任务用。 */
    val handle: String get() = id.take(HANDLE_LENGTH)

    companion object {
        const val HANDLE_LENGTH = 8
    }
}
