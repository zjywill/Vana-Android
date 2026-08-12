package com.pinapia.vana.agent

/**
 * 「让模型写几行短句」的收尾:剥壳、按长度筛、取前几条。
 *
 * 首屏建议和追问 chip 共用一份。
 */
object ModelLines {
    private val SHELL = Regex("""^[0-9.\-–—*·「」"“” 、]+|[0-9.\-–—*·「」"“” 、]+$""")

    fun parse(
        text: String,
        minCharacters: Int,
        maxCharacters: Int,
        limit: Int,
    ): List<String> {
        val usable = text.lineSequence()
            .map { it.trim().replace(SHELL, "").trim() }
            .filter { it.length in minCharacters..maxCharacters }
            .take(limit)
            .toList()
        return usable
    }

    /** 多行先拼回一段,再剥壳,最后按总长筛。 */
    fun single(text: String, minCharacters: Int, maxCharacters: Int): String? {
        val joined = text.lineSequence()
            .map { it.trim().replace(SHELL, "").trim() }
            .filter { it.isNotEmpty() }
            .joinToString("")
        return joined.takeIf { it.length in minCharacters..maxCharacters }
    }
}
