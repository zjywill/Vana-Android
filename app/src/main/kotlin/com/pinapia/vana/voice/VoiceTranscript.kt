package com.pinapia.vana.voice

/**
 * 说出来的那一段怎么和输入框里已经有的字拼起来。
 *
 * **接在后面，不覆盖。** 打了半句发现不好打、改成说完的，是这颗按钮最常见的用法之一；
 * 而覆盖掉他刚打的字是不可撤销的。
 */
object VoiceTranscript {
    fun merge(base: String, spoken: String): String {
        if (spoken.isEmpty()) return base
        if (base.isEmpty()) return spoken
        val last = base.last()
        val first = spoken.first()
        if (last.isWhitespace()) return base + spoken
        // 中文之间不补空格；英文数字之间要补——「HRV」接在「my」后面挤成一个词就是另一个东西了。
        val needsSpace = last.isLetterOrDigitAscii() && first.isLetterOrDigitAscii()
        return if (needsSpace) "$base $spoken" else base + spoken
    }

    private fun Char.isLetterOrDigitAscii(): Boolean =
        code < 128 && (isLetter() || isDigit())
}
