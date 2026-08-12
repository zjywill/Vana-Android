package com.pinapia.vana.memory

import com.pinapia.vana.health.HealthTools
import kotlin.math.pow

/**
 * 这个人实际关心什么。
 *
 * **数出来的,不是模型抽出来的。** 数的是工具调用而不是问句里的关键词。
 */
data class InterestProfile(
    val weights: Map<String, Double>,
) {
    fun weight(forTool: String?): Double {
        val tool = forTool ?: return 0.0
        return weights[tool] ?: 0.0
    }

    /** 从高到低,只留够得上「倾向」的。 */
    val ranked: List<String>
        get() = weights
            .filter { it.value >= MEANINGFUL_WEIGHT }
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
            .map { it.key }

    /** 给场景说明的一句话。没形成倾向就返回 null。 */
    val summary: String?
        get() {
            val top = ranked.take(2).map { HealthTools.label(it) }
            if (top.isEmpty()) return null
            var sentence = "他最常问的是${top.joinToString("、")}。"
            val untouched = HealthTools.TOOL_NAMES
                .filter { weights[it] == null }
                .take(2)
                .map { HealthTools.label(it) }
            if (untouched.isNotEmpty()) {
                sentence += "几乎不问${untouched.joinToString("、")}。"
            }
            return sentence
        }

    companion object {
        val EMPTY = InterestProfile(weights = emptyMap())

        private const val DECAY_PER_SESSION = 0.93
        private const val MEANINGFUL_WEIGHT = 1.5

        data class Entry(
            val isDerived: Boolean,
            val toolNames: List<String>,
        )

        /** 传进来的要**最近的在前**。 */
        fun build(from: List<Entry>): InterestProfile {
            val weights = mutableMapOf<String, Double>()
            from.forEachIndexed { index, entry ->
                if (entry.isDerived) return@forEachIndexed
                val weight = DECAY_PER_SESSION.pow(index.toDouble())
                for (tool in entry.toolNames) {
                    weights[tool] = (weights[tool] ?: 0.0) + weight
                }
            }
            return InterestProfile(weights)
        }
    }
}
