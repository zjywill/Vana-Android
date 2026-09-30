package com.pinapia.vana.intents

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App Shortcuts / deep link 把动作交到界面的信箱。
 *
 * [Ask] 自动发送。只有主对话会来取:主对话永远是家。
 */
object VanaLaunchRouter {
    private val pendingAsk = MutableStateFlow<String?>(null)

    /** 有一句还没被主对话取走。正开着侧聊时,外壳看到它就先回到主对话。 */
    val pending: StateFlow<String?> = pendingAsk.asStateFlow()

    fun ask(question: String) {
        val trimmed = question.trim()
        if (trimmed.isEmpty()) return
        pendingAsk.value = trimmed
    }

    fun consumeAsk(): String? = pendingAsk.getAndUpdate { null }

    private fun <T> MutableStateFlow<T>.getAndUpdate(next: (T) -> T): T {
        while (true) {
            val current = value
            if (compareAndSet(current, next(current))) return current
        }
    }
}
