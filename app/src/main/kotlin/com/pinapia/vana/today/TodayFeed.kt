package com.pinapia.vana.today

import com.pinapia.vana.medications.MedicationSnapshot
import com.pinapia.vana.memory.MemorySnapshot
import com.pinapia.vana.plugins.PluginIds
import com.pinapia.vana.plugins.PluginRegistry
import com.pinapia.vana.tasks.Task
import com.pinapia.vana.tasks.TaskKind
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

    /** 顶栏「今天」上的角标:需要他现在看一眼的(已过点或今天到点的提醒)。 */
    fun attention(cards: List<TodayCard>): Int =
        cards.count { it.priority >= TodayPriority.DUE_TODAY_REMINDER }

    /**
     * 「之后」那一节:还在排着、「今天要做」里没列过的提醒,按到点先后。
     *
     * **按卡片认,不按时间再算一遍**([TodayCard.taskId]):两处各算一次「今天结束在哪一刻」,过零点那一下
     * 就会一条出现两遍或者一条都不见。
     */
    fun later(cards: List<TodayCard>, tasks: List<Task>): List<Task> {
        val listed = cards.mapNotNullTo(HashSet()) { it.taskId }
        return tasks.filter { it.kind == TaskKind.REMINDER && it.isActive && it.id !in listed }.sortedBy { it.dueAt }
    }
}

/**
 * 「今天」的数据源:任务、记忆、用药任何一处变了,或者过了一分钟(提醒到点就该出现),就重算一遍;
 * 打开「今天」那一页、回到前台时也各重算一次([refresh])。全是读本机文件,一次模型调用都不发。
 * 归主对话的 view model 持有:顶栏那颗角标和「今天」那一页读的是同一份。
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
