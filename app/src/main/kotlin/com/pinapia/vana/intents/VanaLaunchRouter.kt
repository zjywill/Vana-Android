package com.pinapia.vana.intents

/**
 * App Shortcuts / deep link 把动作交到界面的信箱。
 *
 * [Ask] 自动发送;[Brief] 显示本地算好的那句话。
 */
object VanaLaunchRouter {
    @Volatile
    private var pendingAsk: String? = null

    @Volatile
    private var pendingBrief: String? = null

    fun ask(question: String) {
        val trimmed = question.trim()
        if (trimmed.isEmpty()) return
        pendingAsk = trimmed
    }

    fun brief(line: String) {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return
        pendingBrief = trimmed
    }

    fun consumeAsk(): String? {
        val value = pendingAsk
        pendingAsk = null
        return value
    }

    fun consumeBrief(): String? {
        val value = pendingBrief
        pendingBrief = null
        return value
    }
}
