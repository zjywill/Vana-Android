package com.pinapia.vana.agentruntime

/**
 * 本地估算和 provider 实际计费的比值。
 *
 * 本地 tokenizer 和服务端从来对不齐,差个 10%~30% 很正常。拿过去几轮的 usage 回来校准,
 * 后面几轮的预算就准得多。
 *
 * 两条纪律:
 * - 比值**按模型归档**。换了 provider 或模型,tokenizer 就换了,旧比值直接作废,宁可回到
 *   裸估算也不要拿别人的尺子量。
 * - 取**中位数**而不是最近一次。命中缓存的那一轮 `inputTokens.total` 可能只报没走缓存的
 *   部分,单看它会把比值压到 0.1;只信最近一次的话,下一轮就拿这把废尺子去量。
 */
data class ContextCalibration(
    private var samples: MutableList<Double> = mutableListOf(),
) {
    /** 中位数。没有样本就是 null——回到裸估算。 */
    val scale: Double?
        get() {
            if (samples.isEmpty()) return null
            val sorted = samples.sorted()
            val middle = sorted.size / 2
            return if (sorted.size % 2 == 0) {
                (sorted[middle - 1] + sorted[middle]) / 2.0
            } else {
                sorted[middle]
            }
        }

    constructor(scale: Double?) : this(
        samples = scale?.let { mutableListOf(it) } ?: mutableListOf(),
    )

    /** 从历史里找最近几轮「同一个模型」留下的估算/实际对照。 */
    constructor(history: List<AgentChatMessageDTO>, profile: AgentModelProfile) : this(
        samples = history.asReversed().mapNotNull { message ->
            if (message.role != AgentChatMessageDTO.Role.ASSISTANT) return@mapNotNull null
            val context = message.storedTurn.context ?: return@mapNotNull null
            if (!context.matches(
                    providerId = profile.providerId,
                    requestedModelId = profile.modelId,
                )
            ) {
                return@mapNotNull null
            }
            val estimated = context.estimatedPromptTokens ?: return@mapNotNull null
            val actual = context.actualPromptTokens ?: return@mapNotNull null
            if (estimated <= 0 || actual <= 0) return@mapNotNull null
            accepted(actual.toDouble() / estimated.toDouble())
        }.take(MAXIMUM_SAMPLES).asReversed().toMutableList(),
    )

    fun note(actual: Int?, estimated: Int) {
        if (actual == null || actual <= 0 || estimated <= 0) return
        val sample = accepted(actual.toDouble() / estimated.toDouble()) ?: return
        samples += sample
        if (samples.size > MAXIMUM_SAMPLES) {
            samples = samples.takeLast(MAXIMUM_SAMPLES).toMutableList()
        }
    }

    /**
     * provider 已经说了「装不下」,那就是我们估小了。把尺子按比例放大,让后面的压缩更狠一点。
     *
     * 比重新估一遍更实在:估算器本来就是那个报错的源头,再问它一次还是同一个答案。
     */
    fun inflate(by: Double) {
        if (by <= 1) return
        val current = scale ?: 1.0
        samples = mutableListOf(minOf(SAMPLE_RANGE.endInclusive, current * by))
    }

    fun apply(to: Int): Int {
        val scale = this.scale
        if (scale == null || scale <= 0) return to
        return maxOf(1, kotlin.math.round(to.toDouble() * scale).toInt())
    }

    companion object {
        /** 单个样本的容许区间。落在外面的多半是口径问题(缓存、计费单位),不是估算误差。 */
        private val SAMPLE_RANGE = 0.25..4.0
        private const val MAXIMUM_SAMPLES = 5

        private fun accepted(ratio: Double): Double? =
            if (ratio in SAMPLE_RANGE) ratio else null
    }
}
