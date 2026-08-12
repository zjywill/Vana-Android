package com.pinapia.vana.settings

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
            BALANCED -> "均衡"
            DATA -> "数据派"
            COACH -> "教练"
            COMPANION -> "陪伴者"
            DIRECT -> "直说"
        }

    val instruction: String
        get() = when (this) {
            BALANCED -> "语气均衡：清楚、克制，结论先行，再给关键数据。"
            DATA -> "偏数据：多引用工具返回的数字和趋势，少做主观评价。"
            COACH -> "像教练：给出可执行的下一步，但不要下诊断或剂量建议。"
            COMPANION -> "偏陪伴：语气更温和，先回应感受，再给数据与建议。"
            DIRECT -> "直说：少铺垫，先给结论，再补必要依据。"
        }

    companion object {
        fun fromRaw(raw: String?): AssistantPersona =
            entries.firstOrNull { it.raw.equals(raw, ignoreCase = true) } ?: BALANCED
    }
}
