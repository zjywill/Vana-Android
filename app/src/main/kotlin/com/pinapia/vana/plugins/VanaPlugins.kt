package com.pinapia.vana.plugins

import com.pinapia.vana.agentruntime.AgentPlugin
import com.pinapia.vana.agentruntime.PluginContext
import com.pinapia.vana.exercises.ExerciseLibrary
import com.pinapia.vana.location.LocationSnapshot
import com.pinapia.vana.measurements.MeasurementSnapshot
import com.pinapia.vana.measurements.MeasurementStore
import com.pinapia.vana.medications.MedicationItem
import com.pinapia.vana.medications.MedicationSnapshot
import com.pinapia.vana.medications.MedicationStore
import com.pinapia.vana.memory.MemorySnapshot
import com.pinapia.vana.memory.MemoryStore
import com.pinapia.vana.search.WebSearchClient
import com.pinapia.vana.session.SessionStore

/**
 * 两条会跑模型的路各装哪些插件。
 *
 * 开关在调用方就兑现成「给不给 store」:关掉某一项就传 null,那一组工具整个不挂,
 * 快照也由调用方按开关给成空的。隐私会话和后台派生不在这里分,由 [PluginContext]
 * 按工具声明的副作用统一过滤。
 */
object VanaPlugins {
    /**
     * 前台聊天。注册顺序就是工具定义发出去的顺序,和插件化之前一致
     * (动作库 → ask_user → 网页搜索 → 用药 → 测量 → 召回 → 记忆)。
     */
    fun foreground(
        exerciseLibrary: ExerciseLibrary,
        webSearch: WebSearchClient?,
        medicationStore: MedicationStore?,
        medications: MedicationSnapshot,
        focusMedication: MedicationItem?,
        measurementStore: MeasurementStore?,
        measurements: MeasurementSnapshot,
        sessionStore: SessionStore?,
        currentSessionId: String?,
        memoryStore: MemoryStore?,
        memory: MemorySnapshot,
        location: LocationSnapshot,
    ): List<AgentPlugin> = buildList {
        add(ExercisePlugin(exerciseLibrary))
        add(AskUserPlugin())
        webSearch?.let { add(WebSearchPlugin(it)) }
        add(MedicationPlugin(store = medicationStore, snapshot = medications, focus = focusMedication))
        add(MeasurementPlugin(store = measurementStore, snapshot = measurements))
        sessionStore?.let { add(RecallPlugin(store = it, currentSessionId = currentSessionId)) }
        add(MemoryPlugin(store = memoryStore, snapshot = memory))
        add(LocationPlugin(location))
    }

    fun foregroundContext(isPrivate: Boolean, recallUnlocked: Boolean) = PluginContext(
        isPrivate = isPrivate,
        unlockedTriggers = if (recallUnlocked) setOf(RECALL_TRIGGER) else emptySet(),
    )

    /**
     * 后台派生:用户不在场。只带记忆(只读)和召回——不带用药表、测量、位置、
     * 网页搜索和动作库:结论不取决于它们,多挂一样就多花一份钱。
     */
    fun background(
        sessionStore: SessionStore?,
        currentSessionId: String?,
        memoryStore: MemoryStore?,
        memory: MemorySnapshot,
    ): List<AgentPlugin> = buildList {
        sessionStore?.let { add(RecallPlugin(store = it, currentSessionId = currentSessionId)) }
        add(MemoryPlugin(store = memoryStore, snapshot = memory))
    }

    fun backgroundContext(recallUnlocked: Boolean) = PluginContext(
        isBackground = true,
        unlockedTriggers = if (recallUnlocked) setOf(RECALL_TRIGGER) else emptySet(),
    )
}
