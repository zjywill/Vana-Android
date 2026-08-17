package com.pinapia.vana.intents

/**
 * App Shortcuts / deep link 把动作交到界面的信箱。
 *
 * [Ask] 自动发送。
 */
object VanaLaunchRouter {
    @Volatile
    private var pendingAsk: String? = null

    fun ask(question: String) {
        val trimmed = question.trim()
        if (trimmed.isEmpty()) return
        pendingAsk = trimmed
    }

    fun consumeAsk(): String? {
        val value = pendingAsk
        pendingAsk = null
        return value
    }
}
