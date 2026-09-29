package com.pinapia.vana.tasks

import java.time.Instant as JInstant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.time.Duration.Companion.minutes
import kotlinx.datetime.Instant

/**
 * 后台助手(子 agent)的预算。**每一条都为了「用户不在场时也不会失控」**:
 * 轮数和时长挡住一个绕圈子的模型,排队和每日次数挡住一个被反复触发的入口,
 * 估算的 token 上限挡住一次搜出几十页内容的任务。任何一条撞上都是「失败即放弃」,不自动重试。
 */
object SubagentLimits {
    const val MAX_TOOL_ROUNDS = 12
    val MAX_WALL_CLOCK = 5.minutes

    /** 估算的 token 总量(字符数 ÷ 2,偏保守)。真实用量取决于 provider,这里只求「量级上别失控」。 */
    const val MAX_ESTIMATED_TOKENS = 80_000

    /** 排队 + 正在跑的最多这么多件。 */
    const val MAX_QUEUED = 3
    const val MAX_RUNS_PER_DAY = 10
    const val MAX_BRIEF_CHARS = 1500

    /** 被系统打断后自动接着跑的次数上限(含第一次)。 */
    const val MAX_ATTEMPTS = 2

    fun estimateTokens(chars: Int): Int = (chars + 1) / 2

    /** 现在还能不能再开一件。不能就返回要说给用户/模型听的原因。 */
    fun problem(store: TaskStore, now: Instant, zone: ZoneId): String? {
        val jobs = store.byKind(TaskKind.JOB)
        val waiting = jobs.count { it.status == TaskStatus.QUEUED || it.status == TaskStatus.RUNNING }
        if (waiting >= MAX_QUEUED) {
            return "后台已经有 $waiting 件在排队或进行中了，等它们做完再开新的。"
        }
        val today = LocalDate.ofInstant(JInstant.ofEpochMilli(now.toEpochMilliseconds()), zone)
        val ranToday = jobs.count { job ->
            job.startedAt?.let { LocalDate.ofInstant(JInstant.ofEpochMilli(it.toEpochMilliseconds()), zone) == today } == true
        }
        if (ranToday >= MAX_RUNS_PER_DAY) {
            return "今天已经派出去 $ranToday 件后台任务了，明天再继续。"
        }
        return null
    }
}
