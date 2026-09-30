package com.pinapia.vana.thread

import com.pinapia.vana.session.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 一条线程的**单写者**。
 *
 * 往同一条线程里写的不止聊天界面:早晚 check-in、到点的提醒、待跟进回访的结论、从侧聊带回来的话都要落进来。
 * 让它们各自直接动文件,前台正在流式生成时后台插一条进来,顺序和落盘都会乱。所以所有写入排一条队,
 * 在 IO 线程上一件一件做;后台追加之后拨一下 [revision],界面看到数字变了才把新来的并进列表——
 * 并且是在**一轮结束之后**并,不是回答写到一半的时候(沿用「插话在轮边界接入」那条规则)。
 */
class ThreadWriter(val store: ThreadStore) {
    /** 档案索引,和线程同生共死:线程里的每次写入它都增量跟上。 */
    val archive = ThreadArchive(store)

    private val mutex = Mutex()
    private val _revision = MutableStateFlow(0L)

    /** 每次有**后台**追加,这个数加一。界面据此知道该去拿新消息了。 */
    val revision: StateFlow<Long> = _revision.asStateFlow()

    suspend fun <T> write(block: (ThreadStore) -> T): T =
        withContext(Dispatchers.IO) { mutex.withLock { block(store) } }

    /** 后台来的主动消息:落到线程末尾,并通知界面。 */
    suspend fun postProactive(message: ChatMessage) {
        write { it.appendAtEnd(message) }
        _revision.value = _revision.value + 1
    }
}
