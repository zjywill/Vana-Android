package com.pinapia.vana.tasks

import java.util.UUID
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class TaskKind {
    /** 交给子 agent 在后台做的一件独立的事(P6)。 */
    @SerialName("job") JOB,

    /** 用户自己长期在做的一件事(备半马、学吉他)。取代原来的「目标线」会话。 */
    @SerialName("goal") GOAL,

    /** 到点发通知,**到点不调模型**。 */
    @SerialName("reminder") REMINDER,
}

/**
 * 三种任务共用一组状态,各取其中几个:
 * - REMINDER:[QUEUED](等着到点)→[DONE](响过了)/[CANCELLED];
 * - GOAL:[RUNNING](进行中)→[DONE]/[CANCELLED];
 * - JOB:[PROPOSED](等用户点确认)→[QUEUED]→[RUNNING]→[DONE]/[FAILED]/[CANCELLED],中途要用户拍板是 [NEEDS_YOU]。
 */
@Serializable
enum class TaskStatus {
    @SerialName("proposed") PROPOSED,
    @SerialName("queued") QUEUED,
    @SerialName("running") RUNNING,
    @SerialName("needsYou") NEEDS_YOU,
    @SerialName("done") DONE,
    @SerialName("failed") FAILED,
    @SerialName("cancelled") CANCELLED,
    ;

    val isActive: Boolean get() = this == PROPOSED || this == QUEUED || this == RUNNING || this == NEEDS_YOU
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

/** 任务运行的审计轨迹:每一步做了什么(工具名、参数摘要)。 */
@Serializable
data class TaskStep(val at: Instant, val label: String, val detail: String? = null)

@Serializable
enum class ProposalStatus {
    @SerialName("pending") PENDING,
    @SerialName("accepted") ACCEPTED,
    @SerialName("dismissed") DISMISSED,
}

/** 子 agent 只读、不能写盘,想做的写操作只能「提议」,由用户点了才执行。 */
@Serializable
data class TaskProposal(
    val id: String = UUID.randomUUID().toString(),
    /** 提议做什么:`reminder` / `goal` / `memory`。 */
    val kind: String,
    val text: String,
    /** `reminder` 提议要用的时间。 */
    val at: Instant? = null,
    val why: String? = null,
    val status: ProposalStatus = ProposalStatus.PENDING,
)

@Serializable
data class TaskResult(
    /** 一两句结论。它同时作为一条主动消息进对话窗口,模型看得到。 */
    val summary: String,
    val body: String = "",
    val proposals: List<TaskProposal> = emptyList(),
    val sources: List<String> = emptyList(),
)

@Serializable
data class Task(
    val id: String = UUID.randomUUID().toString(),
    val kind: TaskKind,
    val title: String,
    val status: TaskStatus,
    val createdAt: Instant = Clock.System.now(),
    val updatedAt: Instant = createdAt,
    // ---- JOB ----
    /** 交给子 agent 的说明。必须自足:子 agent 不带主窗口。 */
    val brief: String = "",
    val result: TaskResult? = null,
    val steps: List<TaskStep> = emptyList(),
    val tokensUsed: Int = 0,
    val error: String? = null,
    /** 最近一次开跑的时间。「今天跑了几次」按它数。 */
    val startedAt: Instant? = null,
    /** 开跑过几次(含被系统打断后自动接着跑的那一次)。 */
    val attempts: Int = 0,
    // ---- REMINDER ----
    val dueAt: Instant? = null,
    val repeat: Repeat = Repeat.NONE,
    // ---- GOAL ----
    val why: String = "",
    val plan: List<PlanItem> = emptyList(),
    val notes: List<GoalNote> = emptyList(),
    val digestEnabled: Boolean = false,
    val lastDigestAt: Instant? = null,
) {
    val isActive: Boolean get() = status.isActive

    /** 短编号:给模型和用户指到某一条任务用。 */
    val handle: String get() = id.take(HANDLE_LENGTH)

    companion object {
        const val HANDLE_LENGTH = 8
    }
}
