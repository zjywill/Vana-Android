package com.pinapia.vana.tasks

import com.pinapia.vana.agent.AgentEngine
import com.pinapia.vana.agent.UserFacingModelFailure
import com.pinapia.vana.agentruntime.AgentTurnEvent
import com.pinapia.vana.plugins.PluginRegistry
import com.pinapia.vana.recall.BackgroundTurn
import com.pinapia.vana.session.ChatMessage
import com.pinapia.vana.session.ToolCallRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * 跑一件后台任务。**不碰 Android、不碰通知、不碰对话线程**——那些在外面([SubagentScheduler]):
 * 这里只负责「一个隔离的上下文、只读的工具、一份预算、把过程和结果记进任务」,所以能在 JVM 上秒级测。
 *
 * 隔离:上下文是它自己造的(system + brief 一条消息),不带主窗口的任何内容——所以 brief 必须自足,
 * 也所以后台一轮不会把用户的对话历史整个发给模型。
 * 失败即放弃:任何一条预算撞上、模型报错,都是记下原因、状态记为失败,由用户决定要不要再试;不自动重试。
 */
class SubagentRunner(
    private val store: TaskStore,
    /** 造一个只读引擎。工具集合由调用方按「后台路 + 提议工具」装配,提议都攒进 [collector]。 */
    private val engineFor: (collector: ProposalCollector) -> AgentEngine,
    private val now: () -> Instant = { Clock.System.now() },
) {
    sealed interface Outcome {
        data class Done(val task: Task) : Outcome
        data class Failed(val task: Task) : Outcome
        data object Skipped : Outcome
    }

    suspend fun run(taskId: String): Outcome {
        val queued = store.get(taskId)?.takeIf { it.kind == TaskKind.JOB && it.status == TaskStatus.QUEUED }
            ?: return Outcome.Skipped
        val started = now()
        store.update(queued.id, started) {
            it.copy(status = TaskStatus.RUNNING, startedAt = started, attempts = it.attempts + 1, error = null, steps = emptyList(), result = null)
        }

        val collector = ProposalCollector()
        var messages = listOf(
            ChatMessage(role = ChatMessage.Role.USER, text = prompt(queued)),
            ChatMessage(role = ChatMessage.Role.ASSISTANT, text = ""),
        )
        var estimatedChars = messages.first().text.length

        try {
            withTimeout(SubagentLimits.MAX_WALL_CLOCK) {
                engineFor(collector).reply(history = messages, pendingInput = null).collect { event ->
                    messages = BackgroundTurn.applyEvent(messages, event)
                    when (event) {
                        is AgentTurnEvent.TextDelta -> estimatedChars += event.text.length
                        is AgentTurnEvent.ReasoningDelta -> estimatedChars += event.text.length
                        is AgentTurnEvent.ToolCallStarted -> recordStep(queued.id, event)
                        is AgentTurnEvent.ToolCallFinished -> estimatedChars += event.output.text.length
                        else -> Unit
                    }
                    if (SubagentLimits.estimateTokens(estimatedChars) > SubagentLimits.MAX_ESTIMATED_TOKENS) {
                        throw BudgetExceeded()
                    }
                }
            }
        } catch (_: BudgetExceeded) {
            return fail(queued.id, estimatedChars, "这件事用到的内容超出了预算，先停下了。可以把范围缩小一点再试。")
        } catch (_: TimeoutCancellationException) {
            return fail(queued.id, estimatedChars, "超过 ${SubagentLimits.MAX_WALL_CLOCK.inWholeMinutes} 分钟还没做完，先停下了。")
        } catch (cancelled: CancellationException) {
            // 用户点了「停止」:状态由停止的那一头写,这里不覆盖。
            throw cancelled
        } catch (error: Throwable) {
            return fail(queued.id, estimatedChars, UserFacingModelFailure.message(error))
        }

        val reply = messages.lastOrNull()
        val result = reply?.takeIf { !it.textIsPlaceholder }?.text?.let { SubagentResult.parse(it, collector.all()) }
        if (result == null) {
            val detail = reply?.errorDescription?.takeIf { it.isNotBlank() } ?: "后台助手没有给出结果。"
            return fail(queued.id, estimatedChars, detail)
        }
        val done = store.update(queued.id, now()) {
            it.copy(status = TaskStatus.DONE, result = result, tokensUsed = SubagentLimits.estimateTokens(estimatedChars), error = null)
        } ?: return Outcome.Skipped
        return Outcome.Done(done)
    }

    private fun prompt(task: Task): String =
        "任务：${task.title}\n\n${task.brief}"

    private fun recordStep(id: String, event: AgentTurnEvent.ToolCallStarted) {
        val record = ToolCallRecord(id = event.record.id, name = event.record.name, input = event.record.input)
        val step = TaskStep(at = now(), label = PluginRegistry.toolLabel(record), detail = event.record.input.take(120))
        store.update(id) { task -> task.copy(steps = (task.steps + step).takeLast(MAX_STEPS)) }
    }

    private fun fail(id: String, estimatedChars: Int, reason: String): Outcome {
        val failed = store.update(id, now()) {
            it.copy(status = TaskStatus.FAILED, error = reason, tokensUsed = SubagentLimits.estimateTokens(estimatedChars))
        } ?: return Outcome.Skipped
        return Outcome.Failed(failed)
    }

    private class BudgetExceeded : RuntimeException("budget")

    private companion object {
        const val MAX_STEPS = 40
    }
}
