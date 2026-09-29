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
import com.pinapia.vana.tenant.Tenant

/**
 * 健康插件:规则、用药表、测量卡片、动作库、家人身份五组。
 *
 * 可用性按组算,不按整个插件:设置里能单独关掉用药表或测量,后台派生只用得上规则那一组。
 * Android 这一侧没有任何设备健康数据源,所以各组都是 [com.pinapia.vana.agentruntime.PluginDataScope.TENANT]。
 *
 * 急症规则、化验单与影像的谨慎、动作库用法、对核心工具的健康补充,以前整段写在基础规则里
 * (`HealthAssistantInstructions`),现在由这里按「健康开着」「那个工具真的挂出去了」两道门贡献。
 */
object HealthPlugin {
    const val ID = "health"
    const val EXERCISES = "$ID.exercises"
    const val MEDICATIONS = "$ID.medications"
    const val MEASUREMENTS = "$ID.measurements"
}

/**
 * 健康的规则本身,加上它对核心工具(搜索、反问、召回、记忆)的补充。没有工具,所以前台和后台都挂。
 * 记忆抽取器读这里的 [memoryGuidance]:什么算健康方面值得记的、什么不该记。
 */
class HealthRulesPlugin : AgentPlugin {
    override val id = "${HealthPlugin.ID}.rules"
    override val memoryGuidance = HealthInstructions.memoryGuidance

    override fun promptBlocks(context: PluginContext, mountedTools: Set<String>): List<PromptBlock> = buildList {
        add(PromptBlock(PromptOrder.HEALTH_RULES, HealthInstructions.rules()))
        HealthInstructions.toolNotes(mountedTools)?.let { add(PromptBlock(PromptOrder.HEALTH_TOOL_NOTES, it)) }
    }
}

/** 家人身份:用户在替家人问的时候,别把家人的情况和他自己的混在一起。 */
class FamilyPlugin(private val tenant: Tenant) : AgentPlugin {
    override val id = "${HealthPlugin.ID}.family"

    override fun promptBlocks(context: PluginContext, mountedTools: Set<String>): List<PromptBlock> =
        HealthInstructions.familyBlock(tenant)?.let { listOf(PromptBlock(PromptOrder.TENANT, it)) }.orEmpty()
}

class ExercisePlugin(private val library: ExerciseLibrary) : AgentPlugin {
    override val id = HealthPlugin.EXERCISES

    override fun tools(context: PluginContext): List<PluginTool> =
        PluginTool.from(ExerciseTools.registry(library)) { setOf(ToolEffect.READ) }

    override fun promptBlocks(context: PluginContext, mountedTools: Set<String>): List<PromptBlock> =
        if (ExerciseTools.SUGGEST_TOOL_NAME in mountedTools) {
            listOf(PromptBlock(PromptOrder.GUIDE_EXERCISE, HealthInstructions.exerciseGuide))
        } else {
            emptyList()
        }
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
    override val id = HealthPlugin.MEDICATIONS

    // 只在用药表真的开着的时候让路:关了它,「我不能吃布洛芬」就该老老实实进记忆。
    override val memoryExclusions get() = if (store != null) listOf("用药与补剂") else emptyList()

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
    override val id = HealthPlugin.MEASUREMENTS
    override val memoryExclusions get() = if (store != null) listOf("测量数字") else emptyList()

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
