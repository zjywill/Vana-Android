package com.pinapia.vana.recall

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 后台模型活的单飞锁。ProcessLifecycle 一次开合会触发两次，
 * 「今天跑过没有」又要等盘上会话写完才成立——没有这把锁会并行烧两轮。
 */
object BackgroundModelWork {
    private val mutex = Mutex()

    /** 排队等这把锁,不是「忙就跳过」:用户点了开始的后台任务不能因为这一刻恰好有别的活就丢掉。 */
    suspend fun <T> runExclusive(work: suspend () -> T): T = mutex.withLock { work() }

    suspend fun <T> run(work: suspend () -> T): T? {
        if (!mutex.tryLock()) return null
        return try {
            work()
        } finally {
            mutex.unlock()
        }
    }
}
