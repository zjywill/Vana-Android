package com.pinapia.vana.thread

import com.pinapia.vana.session.ChatMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant

/**
 * 档案:整条线程逐字保留的全部历史,给模型按需检索。
 *
 * 窗口里带的是最近的原文,更早的不在请求里——不是丢了,是在这里。模型想起用户提过的旧事,
 * 用 `search_sessions` 找、`read_session` 读。为什么是「检索原文」而不是「摘要」:抽成摘要是有损的,
 * 递归摘要会漂(摘要的摘要把最早的细节磨平);保留原文再检索没有这个问题。
 *
 * 索引在**进程内存**里,由后台线程启动时扫一遍盘建起来,之后靠 [ThreadStore.ChangeListener]
 * 增量更新——不落盘:它随时能从线程重建,落盘只会多一份要保持同步的东西。每条消息只存截断后的文字
 * (用户 800 字、助手 320 字),够搜索和「读那一段」用,不把整轮 transcript 攒在内存里。
 */
class ThreadArchive(private val store: ThreadStore) : ThreadStore.ChangeListener {
    /** 一条消息的索引行。 */
    data class Row(
        val id: String,
        val pos: Double,
        val createdAt: Instant,
        val isUser: Boolean,
        val text: String,
        val toolNames: List<String>,
    )

    private val rows = java.util.TreeMap<Double, Row>()
    private val byId = HashMap<String, Double>()
    private val ready = CompletableDeferred<Unit>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        store.changeListener = this
        scope.launch {
            store.scan { pos, message -> put(pos, message) }
            ready.complete(Unit)
        }
    }

    /** 索引建好之前的查询等在这里。 */
    suspend fun await() = ready.await()

    private fun put(pos: Double, message: ChatMessage) {
        val text = message.text.trim()
        if (message.textIsPlaceholder || text.isEmpty()) {
            remove(message.id)
            return
        }
        val limit = if (message.role == ChatMessage.Role.USER) USER_LIMIT else ASSISTANT_LIMIT
        val row = Row(
            id = message.id,
            pos = pos,
            createdAt = message.createdAt,
            isUser = message.role == ChatMessage.Role.USER,
            text = text.take(limit),
            toolNames = message.toolCalls.map { it.name }.distinct(),
        )
        synchronized(rows) {
            byId[message.id]?.let { rows.remove(it) }
            rows[pos] = row
            byId[message.id] = pos
        }
    }

    private fun remove(id: String) {
        synchronized(rows) { byId.remove(id)?.let { rows.remove(it) } }
    }

    override fun onChanged(puts: List<Pair<Double, ChatMessage>>, deletes: List<String>) {
        puts.forEach { (pos, message) -> put(pos, message) }
        deletes.forEach { remove(it) }
    }

    override fun onCleared() {
        synchronized(rows) {
            rows.clear()
            byId.clear()
        }
    }

    /** 位置在 [beforePos] 之前的全部行(按位置)。窗口里的原文模型本来就看得见,不该再搜出来。 */
    fun rowsBefore(beforePos: Double): List<Row> =
        synchronized(rows) { rows.headMap(beforePos, false).values.toList() }

    fun hasRowsBefore(beforePos: Double): Boolean =
        synchronized(rows) { rows.isNotEmpty() && rows.firstKey() < beforePos }

    /** 以 [id] 那一行为中心,往前 [before] 行、往后 [after] 行。 */
    fun around(id: String, before: Int = 1, after: Int = 5): List<Row>? = synchronized(rows) {
        val pos = byId[id] ?: return@synchronized null
        val all = rows.values.toList()
        val index = all.indexOfFirst { it.id == id }
        if (index < 0) return@synchronized null
        all.subList((index - before).coerceAtLeast(0), (index + after + 1).coerceAtMost(all.size))
    }

    val size: Int get() = synchronized(rows) { rows.size }

    companion object {
        const val USER_LIMIT = 800
        const val ASSISTANT_LIMIT = 320
    }
}
