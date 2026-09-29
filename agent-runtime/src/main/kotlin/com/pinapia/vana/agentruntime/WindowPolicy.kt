package com.pinapia.vana.agentruntime

/**
 * 一条永远的对话里,「最近的消息」这个窗口怎么滑。
 *
 * 窗口之外的历史不丢:记忆管长期成立的事实,档案(逐字保留的全部历史)按需检索。窗口只决定
 * **每一轮请求里带多少原文**。三条规矩,都是踩过别人的坑:
 *
 * - **批量淘汰,不逐条丢。** 涨到 [budgetTokens] 才动,而且一次砍到 [budgetTokens] × [lowRatio]。
 *   逐条丢会让请求前缀每一轮都变,prompt 缓存整个失效——用户用自己的 key,这是真钱。批量之后
 *   两次淘汰之间「system + 工具定义 + 窗口」是纯追加,缓存前缀稳定。
 * - **只在轮边界切。** 一轮是一条用户消息加其后的助手/工具消息;绝不切在一次工具调用和它的结果之间。
 *   所以这里吃的是「每一轮多少 token」,不是逐条消息。
 * - **最近的几轮不动。** 至少留 [minTailTurns] 轮(含正在进行的这一轮),再大的单轮也不例外——
 *   宁可这一轮超预算,让上下文降级去兜,也不能把用户刚说的话淘汰掉。
 *
 * 纯逻辑,没有 android、没有模型:秒级测试。
 */
class WindowPolicy(
    val budgetTokens: Int,
    val lowRatio: Double = DEFAULT_LOW_RATIO,
    val minTailTurns: Int = DEFAULT_MIN_TAIL_TURNS,
) {
    init {
        require(budgetTokens > 0) { "budget must be positive" }
        require(lowRatio > 0.0 && lowRatio < 1.0) { "lowRatio must be in (0, 1)" }
        require(minTailTurns >= 1) { "at least the newest turn is always kept" }
    }

    val lowWatermark: Int get() = (budgetTokens * lowRatio).toInt()

    /**
     * [turns] 是从窗口起点起、按时间顺序每一轮的 token 估计;最后一轮是进行中的这一轮。
     * 返回要从最前面**整轮**淘汰掉几轮;0 表示还没涨到高水位,不动。
     */
    fun turnsToEvict(turns: List<Int>): Int {
        if (turns.isEmpty()) return 0
        val total = turns.sum().toLong()
        if (total <= budgetTokens) return 0
        val evictable = (turns.size - minTailTurns).coerceAtLeast(0)
        var remaining = total
        var evicted = 0
        while (evicted < evictable && remaining > lowWatermark) {
            remaining -= turns[evicted]
            evicted++
        }
        return evicted
    }

    companion object {
        const val DEFAULT_LOW_RATIO = 0.4
        const val DEFAULT_MIN_TAIL_TURNS = 6

        private const val UNKNOWN_CONTEXT_BUDGET = 16_000
        private const val MIN_BUDGET = 12_000
        private const val MAX_BUDGET = 32_000

        /**
         * 窗口预算:模型上下文的三成半,夹在 12k–32k 之间;不知道上下文多大就按 16k。
         * 封顶 32k 是花钱的考虑:窗口每一轮都要全额发出去,用户自己付这笔钱。
         */
        fun budgetFor(contextWindow: Int?): Int {
            if (contextWindow == null || contextWindow <= 0) return UNKNOWN_CONTEXT_BUDGET
            return (contextWindow * 0.35).toInt().coerceIn(MIN_BUDGET, MAX_BUDGET)
        }

        fun forContext(contextWindow: Int?): WindowPolicy = WindowPolicy(budgetTokens = budgetFor(contextWindow))
    }
}
