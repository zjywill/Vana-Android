package com.pinapia.vana.voice

import com.pinapia.vana.medications.MedicationItem
import com.pinapia.vana.medications.MedicationSnapshot
import com.pinapia.vana.memory.MemorySnapshot

/**
 * 说给识别器听的那份词表（`RecognizerIntent.EXTRA_BIASING_STRINGS`）。
 *
 * **这是自己做语音输入的唯一硬理由。** 系统键盘上那颗麦克风本来就在输入框里能用；
 * 它缺的不是「能说话」，是不知道用户在跟一个健康 app 说话。
 *
 * Android 的 biasing 对中文是否真正生效因厂商识别服务而异；偏置无效时仍保留按住说话，
 * 用户仍可用输入法自带语音（见 CLAUDE.md）。词表照样带上——有效时药名识别收益最大。
 */
object VoiceVocabulary {
    const val MAX_TERMS = 100
    const val MAX_TERM_CHARACTERS = 16
    fun terms(medications: MedicationSnapshot, memory: MemorySnapshot): List<String> {
        val result = mutableListOf<String>()
        for (status in MedicationItem.Status.entries) {
            for (item in medications.items) {
                if (item.status == status) result += item.name
            }
        }
        result += memoryTerms(memory)
        result += metricTerms
        return normalized(result)
    }

    val metricTerms: List<String>
        get() = normalized(FINE_GRAINED_TERMS)

    private val FINE_GRAINED_TERMS = listOf(
        "静息心率", "心率变异性", "HRV", "血氧", "血氧饱和度", "呼吸频率", "体温",
        "深睡", "核心睡眠", "快速眼动", "REM", "睡眠效率", "入睡时间", "睡眠分期",
        "体脂", "体脂率", "去脂体重", "BMI", "腰围",
        "收缩压", "舒张压", "高压", "低压",
        "身高", "体重", "心率", "血压", "血糖", "体温", "腰围",
        "化验单", "体检报告", "空腹血糖", "糖化血红蛋白", "血红蛋白", "白细胞", "血小板",
        "总胆固醇", "低密度脂蛋白", "高密度脂蛋白", "甘油三酯", "尿酸", "肌酐",
        "转氨酶", "促甲状腺激素", "维生素 D",
    )

    fun memoryTerms(memory: MemorySnapshot): List<String> =
        normalized(memory.items.flatMap { termsInMemoryText(it.text) })

    fun termsInMemoryText(text: String): List<String> {
        val found = quoted(text).toMutableList()
        var latin = StringBuilder()
        for (character in text + "\u0000") {
            val isLatin = character.code < 128 && (character.isLetter() || character.isDigit())
            if (isLatin) {
                latin.append(character)
                continue
            }
            val token = latin.toString()
            latin = StringBuilder()
            if (token.length >= 2 && token.any { it.isLetter() }) {
                found += token
            }
        }
        return found
    }

    private fun quoted(text: String): List<String> {
        val pairs = listOf('「' to '」', '“' to '”', '《' to '》', '『' to '』')
        val found = mutableListOf<String>()
        for ((open, close) in pairs) {
            var current: StringBuilder? = null
            for (character in text) {
                when (character) {
                    open -> current = StringBuilder()
                    close -> {
                        current?.let { found += it.toString() }
                        current = null
                    }
                    else -> current?.append(character)
                }
            }
        }
        return found
    }

    fun normalized(terms: List<String>): List<String> {
        val seen = mutableSetOf<String>()
        val result = mutableListOf<String>()
        for (term in terms) {
            val trimmed = term.trim()
            if (trimmed.length < 2 || trimmed.length > MAX_TERM_CHARACTERS) continue
            if (!seen.add(trimmed.lowercase())) continue
            result += trimmed
            if (result.size == MAX_TERMS) break
        }
        return result
    }
}
