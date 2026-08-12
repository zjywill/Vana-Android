package com.pinapia.vana.vision

/**
 * 识别出来的一段文字和它在图上的位置。
 *
 * 坐标归一化、原点在左上、y 向下——阅读顺序那一侧。
 */
data class RecognizedFragment(
    val text: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
) {
    val midY: Float get() = y + height / 2f
    val minX: Float get() = x
    val maxX: Float get() = x + width
    val minY: Float get() = y
    val maxY: Float get() = y + height
}

/**
 * 把散落的识别结果重新排回人看得懂的样子。
 *
 * 化验单是表格:按 y 聚成行、按 x 排成列。按识别顺序平铺会把「血红蛋白 132」拆散重排。
 */
object RecognizedTextLayout {
    /** 同一行:两段文字在竖直方向上压过对方一半以上。 */
    const val SAME_ROW_OVERLAP_RATIO = 0.5f

    /** 隔多远算换了一列:横向空白超过这一行字高的这么多倍。 */
    const val COLUMN_GAP_RATIO = 0.75f

    /** 挨得多近才算识别把一句话切成了两段。 */
    const val JOIN_GAP_RATIO = 0.25f

    /** 列与列之间的分隔。用竖线不用空格。 */
    const val COLUMN_SEPARATOR = " | "

    fun reconstruct(fragments: List<RecognizedFragment>): String =
        rows(from = fragments).joinToString("\n") { lineOf(it) }

    /** 聚成行。返回的每一行按 x 从左到右排好。 */
    fun rows(from: List<RecognizedFragment>): List<List<RecognizedFragment>> {
        val usable = from
            .filter { it.text.trim().isNotEmpty() }
            .sortedWith(compareBy({ it.midY }, { it.minX }))

        val rows = mutableListOf<MutableList<RecognizedFragment>>()
        // 锚点是这一行第一个碎片,不是并集——并集会在页面倾斜时把下一行也吸进来。
        var anchor: RecognizedFragment? = null

        for (fragment in usable) {
            val current = anchor
            if (current != null && overlapsVertically(current, fragment)) {
                rows.last().add(fragment)
            } else {
                rows.add(mutableListOf(fragment))
                anchor = fragment
            }
        }
        return rows.map { row -> row.sortedBy { it.minX } }
    }

    fun truncated(text: String, maxCharacters: Int): Pair<String, Int> {
        if (text.length <= maxCharacters) return text to 0
        val lines = text.split("\n")
        var length = 0
        val kept = mutableListOf<String>()
        for (line in lines) {
            val next = length + line.length + if (kept.isEmpty()) 0 else 1
            if (next > maxCharacters) break
            kept += line
            length = next
        }
        if (kept.isEmpty()) {
            return text.take(maxCharacters) to maxOf(lines.size - 1, 0)
        }
        return kept.joinToString("\n") to (lines.size - kept.size)
    }

    private fun overlapsVertically(lhs: RecognizedFragment, rhs: RecognizedFragment): Boolean {
        val overlap = minOf(lhs.maxY, rhs.maxY) - maxOf(lhs.minY, rhs.minY)
        if (overlap <= 0f) return false
        return overlap >= SAME_ROW_OVERLAP_RATIO * minOf(lhs.height, rhs.height)
    }

    private fun lineOf(row: List<RecognizedFragment>): String {
        var line = ""
        var previous: RecognizedFragment? = null
        for (fragment in row) {
            val text = fragment.text.trim()
            val prior = previous
            previous = fragment
            if (prior == null) {
                line = text
                continue
            }
            line += separator(after = prior, before = fragment) + text
        }
        return line
    }

    private fun separator(after: RecognizedFragment, before: RecognizedFragment): String {
        val gap = before.minX - after.maxX
        val unit = minOf(after.height, before.height)
        if (unit > 0f && gap > COLUMN_GAP_RATIO * unit) return COLUMN_SEPARATOR
        if (
            gap < JOIN_GAP_RATIO * unit &&
            isIdeograph(after.text.lastOrNull()) &&
            isIdeograph(before.text.firstOrNull())
        ) {
            return ""
        }
        return " "
    }

    private fun isIdeograph(character: Char?): Boolean {
        if (character == null) return false
        val code = character.code
        return code in 0x3040..0x9FFF || code in 0xF900..0xFAFF
    }
}
