package com.pinapia.vana.plugins

import com.pinapia.vana.agentruntime.AgentPlugin
import com.pinapia.vana.agentruntime.PluginContext
import com.pinapia.vana.agentruntime.PluginTool
import com.pinapia.vana.agentruntime.PromptBlock
import com.pinapia.vana.agentruntime.ToolEffect
import com.pinapia.vana.exercises.ExerciseLibrary
import com.pinapia.vana.exercises.ExerciseTools
import com.pinapia.vana.measurements.MeasurementSnapshot
import com.pinapia.vana.measurements.MeasurementStore
import com.pinapia.vana.measurements.MeasurementTools
import com.pinapia.vana.medications.MedicationItem
import com.pinapia.vana.medications.MedicationSnapshot
import com.pinapia.vana.medications.MedicationStore
import com.pinapia.vana.medications.MedicationTools

/**
 * 健康插件:用药表、测量卡片、动作库三组。
 *
 * 可用性按组算,不按整个插件:设置里能单独关掉用药表或测量,后台派生只用得上其中几样。
 * Android 这一侧没有任何设备健康数据源,所以三组都是 [com.pinapia.vana.agentruntime.PluginDataScope.TENANT]。
 *
 * 急症规则、OCR 规则、动作库那段用法这一步还留在 `HealthAssistantInstructions` 里,
 * 拆提示词是下一步的事。
 */
object HealthPlugin {
    const val ID = "health"
}

class ExercisePlugin(private val library: ExerciseLibrary) : AgentPlugin {
    override val id = "${HealthPlugin.ID}.exercises"

    override fun tools(context: PluginContext): List<PluginTool> =
        PluginTool.from(ExerciseTools.registry(library)) { setOf(ToolEffect.READ) }
}

/**
 * 用药表。名单是常驻段,不做按需挂载:漏的代价不对称,要模型自己想起来去查,
 * 它想不起来的那次恰好就是最该说的那次。
 *
 * [store] 为 null 就不挂工具(用药表关着,或者后台派生);[snapshot] 由调用方按开关给。
 */
class MedicationPlugin(
    private val store: MedicationStore?,
    private val snapshot: MedicationSnapshot,
    private val focus: MedicationItem? = null,
) : AgentPlugin {
    override val id = "${HealthPlugin.ID}.medications"
    override val memoryExclusions = listOf("用药与补剂")

    override fun tools(context: PluginContext): List<PluginTool> {
        val store = store ?: return emptyList()
        return PluginTool.from(MedicationTools.registry(store = store, allowsWrites = true)) { name ->
            if (name == MedicationTools.LIST) setOf(ToolEffect.READ) else setOf(ToolEffect.WRITE_LOCAL)
        }
    }

    override fun promptBlocks(context: PluginContext, mountedTools: Set<String>): List<PromptBlock> = buildList {
        snapshot.instructionBlock?.let { add(PromptBlock(PromptOrder.MEDICATIONS, it)) }
        focus?.focusInstruction?.let { add(PromptBlock(PromptOrder.FOCUS_MEDICATION, it)) }
        if (MedicationTools.LOG in mountedTools) {
            add(
                PromptBlock(
                    PromptOrder.GUIDE_MEDICATION_LOG,
                    "用户说出他和某样药或补剂的关系时，调用 log_medication / update_medication 当场记下。",
                ),
            )
        }
        if (MedicationTools.LIST in mountedTools) {
            add(PromptBlock(PromptOrder.GUIDE_MEDICATION_LIST, "需要完整用药表（含停掉的）时调用 list_medications。"))
        }
    }
}

class MeasurementPlugin(
    private val store: MeasurementStore?,
    private val snapshot: MeasurementSnapshot,
) : AgentPlugin {
    override val id = "${HealthPlugin.ID}.measurements"
    override val memoryExclusions = listOf("测量数字")

    override fun tools(context: PluginContext): List<PluginTool> {
        val store = store ?: return emptyList()
        return PluginTool.from(MeasurementTools.registry(store = store, allowsWrites = true)) { name ->
            if (name == MeasurementTools.LIST) setOf(ToolEffect.READ) else setOf(ToolEffect.WRITE_LOCAL)
        }
    }

    override fun promptBlocks(context: PluginContext, mountedTools: Set<String>): List<PromptBlock> = buildList {
        snapshot.instructionBlock?.let { add(PromptBlock(PromptOrder.MEASUREMENTS, it)) }
        if (MeasurementTools.LOG in mountedTools) {
            add(
                PromptBlock(
                    PromptOrder.GUIDE_MEASUREMENT_LOG,
                    "用户口述身高、体重、心率、血压，或任何他提到的身体/化验指标时，" +
                        "调用 log_measurement 记成测量卡片（自由名称 + 数值 + 观测时间）。" +
                        "每次都是追加，不要覆盖旧的；今天和明天的同名数值会留下两条。" +
                        "你不认识的指标名也照记，不要拒，也不要改写成别的名字硬套。" +
                        "观测时间说不清时先用 ask_user（今天/昨天/今早/具体哪天），不要默认成现在。" +
                        "这些数字不要用 remember。",
                ),
            )
        }
        if (MeasurementTools.LIST in mountedTools) {
            add(
                PromptBlock(
                    PromptOrder.GUIDE_MEASUREMENT_LIST,
                    "需要某项指标的历史测量卡片，或系统提示里没有的旧记录时，调用 list_measurements。",
                ),
            )
        }
    }
}
