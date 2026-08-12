package com.pinapia.vana.agent

import com.pinapia.vana.agentruntime.AgentToolOutput
import com.pinapia.vana.agentruntime.CapabilityExecutionResult
import com.pinapia.vana.agentruntime.CapabilityRegistry
import com.pinapia.vana.ask.AskUserTools
import com.pinapia.vana.exercises.ExerciseLibrary
import com.pinapia.vana.exercises.ExerciseTools
import com.pinapia.vana.health.HealthTools
import com.pinapia.vana.medications.MedicationStore
import com.pinapia.vana.medications.MedicationTools
import com.pinapia.vana.measurements.MeasurementStore
import com.pinapia.vana.measurements.MeasurementTools
import com.pinapia.vana.memory.MemoryStore
import com.pinapia.vana.memory.MemoryTools
import com.pinapia.vana.recall.SessionRecallTools
import com.pinapia.vana.search.WebSearchClient
import com.pinapia.vana.search.WebSearchTools
import com.pinapia.vana.session.SessionStore

fun CapabilityRegistry.Companion.healthChat(
    includesHealthTools: Boolean = true,
    allowsMemoryWrites: Boolean = true,
    allowsMedicationWrites: Boolean = true,
    allowsMeasurementWrites: Boolean = true,
    allowsRecall: Boolean = false,
    asksUser: Boolean = true,
    healthTools: HealthTools? = null,
    memoryStore: MemoryStore? = null,
    medicationStore: MedicationStore? = null,
    measurementStore: MeasurementStore? = null,
    sessionStore: SessionStore? = null,
    currentSessionId: String? = null,
    webSearch: WebSearchClient? = null,
    exerciseLibrary: ExerciseLibrary? = null,
    memoryEnabled: Boolean = true,
    medicationsEnabled: Boolean = true,
    measurementsEnabled: Boolean = true,
): CapabilityRegistry {
    val registries = mutableListOf<CapabilityRegistry>()
    if (includesHealthTools && healthTools != null) {
        registries += healthTools.registry()
    }
    // 动作库不跟着 Health Connect 走：不读健康数据、不落盘、不联网。
    if (exerciseLibrary != null) {
        registries += ExerciseTools.registry(exerciseLibrary)
    }
    if (asksUser) {
        registries += AskUserTools.registry()
    }
    if (webSearch != null) {
        registries += WebSearchTools.registry(webSearch)
    }
    if (medicationsEnabled && medicationStore != null) {
        registries += MedicationTools.registry(
            store = medicationStore,
            allowsWrites = allowsMedicationWrites,
        )
    }
    if (measurementsEnabled && measurementStore != null) {
        registries += MeasurementTools.registry(
            store = measurementStore,
            allowsWrites = allowsMeasurementWrites,
        )
    }
    if (memoryEnabled && allowsRecall && sessionStore != null) {
        registries += SessionRecallTools.registry(
            store = sessionStore,
            currentSessionId = currentSessionId,
        )
    }
    if (memoryEnabled && allowsMemoryWrites && memoryStore != null) {
        registries += MemoryTools.registry(store = memoryStore)
    }
    return combining(registries)
}

fun CapabilityRegistry.Companion.combining(registries: List<CapabilityRegistry>): CapabilityRegistry {
    if (registries.isEmpty()) return empty
    if (registries.size == 1) return registries.first()
    return CapabilityRegistry(definitions = registries.flatMap { it.definitions }) { invocation ->
        for (registry in registries) {
            if (registry.definition(named = invocation.name) != null) {
                return@CapabilityRegistry registry.execute(invocation)
            }
        }
        CapabilityExecutionResult(
            output = AgentToolOutput(
                kind = AgentToolOutput.Kind.TEXT,
                text = "不支持名为 ${invocation.name} 的工具。",
            ),
            isError = true,
        )
    }
}
