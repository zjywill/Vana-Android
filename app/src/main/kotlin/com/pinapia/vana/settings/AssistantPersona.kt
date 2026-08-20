package com.pinapia.vana.settings

import com.pinapia.vana.ui.L10n
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class AssistantPersona(val raw: String) {
    @SerialName("balanced") BALANCED("balanced"),
    @SerialName("data") DATA("data"),
    @SerialName("coach") COACH("coach"),
    @SerialName("companion") COMPANION("companion"),
    @SerialName("direct") DIRECT("direct"),
    ;

    val label: String
        get() = when (this) {
            BALANCED -> L10n.text("均衡", "Balanced")
            DATA -> L10n.text("数据派", "Data-focused")
            COACH -> L10n.text("教练", "Coach")
            COMPANION -> L10n.text("陪伴者", "Companion")
            DIRECT -> L10n.text("直说", "Direct")
        }

    val instruction: String
        get() = when (this) {
            BALANCED -> L10n.text("语气均衡：清楚、克制，结论先行，再给关键数据。", "Balanced: clear and restrained, with the conclusion first and key details after.")
            DATA -> L10n.text("偏数据：多引用工具返回的数字和趋势，少做主观评价。", "Data-focused: cite recorded values and trends, with less subjective commentary.")
            COACH -> L10n.text("像教练：给出可执行的下一步，但不要下诊断或剂量建议。", "Coach: offer practical next steps without diagnosis or dosage advice.")
            COMPANION -> L10n.text("偏陪伴：语气更温和，先回应感受，再给数据与建议。", "Companion: respond warmly to feelings before giving information and suggestions.")
            DIRECT -> L10n.text("直说：少铺垫，先给结论，再补必要依据。", "Direct: minimal preamble, conclusion first, then only necessary support.")
        }

    companion object {
        fun fromRaw(raw: String?): AssistantPersona =
            entries.firstOrNull { it.raw.equals(raw, ignoreCase = true) } ?: BALANCED
    }
}
