package com.pinapia.vana.plugins

import com.pinapia.vana.exercises.ExerciseTools
import com.pinapia.vana.measurements.MeasurementTools
import com.pinapia.vana.medications.MedicationTools
import com.pinapia.vana.ui.L10n

/**
 * 「这条回答算不算健康话题」——决定要不要在它下面补那句医疗免责。
 *
 * 以前每条助手消息下面都是同一句「不构成诊断或用药建议」,对一个日常助手是噪音:
 * 整理待办的回答底下写着「不构成用药建议」。现在通用的「AI 生成、可能有误」每条都有,
 * 医疗那半句只在话题真的沾上健康时才出现。
 *
 * 判据是**确定性**的,不靠再问一次模型:这一轮调用过健康插件的工具,或者用户的话/回答里
 * 出现健康词表里的词。宁可多显示——词表故意宽,漏掉一次比多出一句更糟。
 * 不看健康插件的开关:开关管的是工具和提示词;免责声明是保护读这条回答的人,
 * 他关掉健康插件不等于他不会拿一条谈症状的回答当医嘱。
 */
object HealthTopics {
    val TOOL_NAMES: Set<String> = setOf(
        ExerciseTools.SUGGEST_TOOL_NAME,
        MedicationTools.LIST,
        MedicationTools.LOG,
        MedicationTools.UPDATE,
        MeasurementTools.LIST,
        MeasurementTools.LOG,
    )

    private val keywords = listOf(
        "症状", "用药", "吃药", "服药", "药", "剂量", "化验", "体检", "检查报告", "诊断", "确诊", "疾病", "病史",
        "血压", "血糖", "血脂", "心率", "体重", "发烧", "发热", "头痛", "头疼", "头晕", "失眠", "咳嗽", "感冒",
        "疼", "痛", "过敏", "医院", "医生", "就医", "急诊", "手术", "怀孕", "孕期",
        "symptom", "medication", "medicine", "dosage", "dose", "prescription", "diagnos", "lab result",
        "blood pressure", "blood sugar", "heart rate", "fever", "headache", "allergy", "doctor", "hospital",
    )

    fun mentions(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val lowered = text.lowercase()
        return keywords.any { lowered.contains(it) }
    }

    /** [toolNames] 是这一轮调用过的工具;[texts] 是用户那句话和助手的回答。 */
    fun applies(toolNames: Collection<String>, vararg texts: String?): Boolean =
        toolNames.any { it in TOOL_NAMES } || texts.any { mentions(it) }

    /** 每条助手消息下都有的那半句。 */
    val generalDisclaimer = Localized(
        zh = "以上由 AI 生成，可能有误，重要信息请自行核对。",
        en = "AI-generated content may be wrong. Check anything important yourself.",
    )

    /** 只在健康话题下补的那半句。 */
    val medicalDisclaimer = Localized(
        zh = "不构成诊断或用药建议，关键数值请对照原始记录核对。",
        en = "It is not a diagnosis or medication advice. Check important values against the original record.",
    )

    fun disclaimer(healthRelated: Boolean): String =
        if (healthRelated) {
            generalDisclaimer.text + L10n.text("", " ") + medicalDisclaimer.text
        } else {
            generalDisclaimer.text
        }
}
