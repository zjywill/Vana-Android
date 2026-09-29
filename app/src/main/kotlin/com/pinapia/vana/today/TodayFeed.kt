package com.pinapia.vana.today

import com.pinapia.vana.medications.MedicationSnapshot
import com.pinapia.vana.memory.MemorySnapshot
import com.pinapia.vana.plugins.PluginIds
import com.pinapia.vana.plugins.PluginRegistry
import com.pinapia.vana.tasks.Task
import java.time.ZoneId
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/** 「今天」怎么从本机数据算出来。纯函数,好测。 */
object TodayCompute {
    fun cards(
        now: Instant,
        zone: ZoneId,
        tasks: List<Task>,
        memory: MemorySnapshot,
        medications: MedicationSnapshot,
        isEnabled: (String) -> Boolean,
    ): List<TodayCard> {
        // 关掉记忆的人不指望 Vana 还翻他的「说好回头看」。
        val dueFollowUps = if (isEnabled(PluginIds.MEMORY)) memory.due(now) else emptyList()
        return PluginRegistry.todayCards(
            TodayContext(
                now = now,
                zone = zone,
                tasks = tasks,
                dueFollowUps = dueFollowUps,
                medications = if (isEnabled(PluginIds.HEALTH_MEDICATIONS)) medications else MedicationSnapshot.empty,
                isEnabled = isEnabled,
            ),
        )
    }

    /** 顶栏角标:需要他现在看一眼的(要确认的任务、已过点或今天到点的提醒)。 */
    fun attention(cards: List<TodayCard>): Int =
        cards.count { it.priority >= TodayPriority.DUE_TODAY_REMINDER }
}

/**
 * 「今天」的数据源:任务、记忆、用药任何一处变了,或者过了一分钟(提醒到点就该出现),就重算一遍。
 * 全是读本机文件,一次模型调用都不发。
 */
class TodayFeed(
    private val scope: CoroutineScope,
    private val loadTasks: () -> List<Task>,
    private val loadMemory: () -> MemorySnapshot,
    private val loadMedications: () -> MedicationSnapshot,
    private val isEnabled: (String) -> Boolean,
    private val now: () -> Instant = { Clock.System.now() },
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    private val _cards = MutableStateFlow<List<TodayCard>>(emptyList())
    val cards: StateFlow<List<TodayCard>> = _cards.asStateFlow()

    private val _attention = MutableStateFlow(0)
    val attention: StateFlow<Int> = _attention.asStateFlow()

    fun start(changes: kotlinx.coroutines.flow.Flow<*>) {
        scope.launch(Dispatchers.Default) {
            changes.collect { refreshNow() }
        }
        scope.launch(Dispatchers.Default) {
            while (true) {
                delay(TICK)
                refreshNow()
            }
        }
    }

    fun refresh() {
        scope.launch(Dispatchers.Default) { refreshNow() }
    }

    private fun refreshNow() {
        val computed = runCatching {
            TodayCompute.cards(
                now = now(),
                zone = zone(),
                tasks = loadTasks(),
                memory = loadMemory(),
                medications = loadMedications(),
                isEnabled = isEnabled,
            )
        }.getOrNull() ?: return
        _cards.value = computed
        _attention.value = TodayCompute.attention(computed)
    }

    private companion object {
        val TICK = 60.seconds
    }
}
