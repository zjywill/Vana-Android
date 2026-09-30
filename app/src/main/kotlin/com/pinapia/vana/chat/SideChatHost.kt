package com.pinapia.vana.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 侧聊的 view model 由谁持有。
 *
 * **离开侧聊时正在写的回复不停**:他问完一个要查一会儿的问题,回主对话去说别的,回来时那段回答应该已经写好了
 * ——这正是撤掉子 agent 之后侧聊要接住的那件事。所以离开那一页时,还在写的那个对象留在这儿,写完亮一个未读点;
 * 回来时接上的是**同一个对象**,不是重新读盘——两个对象同时写一条线,位置和已删的记账会各记各的。
 *
 * 没在写的那个离开就放掉(顺手收割一次记忆)。宿主本身挂在主对话那一项的返回栈上(`SideChatHost` 是那一项的
 * view model):换成员、清空全部对话时主对话那一项整个换掉,还在写的那几个一起停下。
 *
 * 宿主不认识 [ChatViewModel] 本身,只认 [Hosted]:测试里换一个假的,不用起一整套聊天。
 */
class SideChatHost : ViewModel() {
    /** 宿主眼里的一条侧聊。 */
    interface Hosted {
        val isReplying: StateFlow<Boolean>

        /** 离开:停下正在写的、落盘、按需收割。返回落盘那件事,要删它的人得等它写完。 */
        fun leave(harvesting: Boolean): Job?

        /** 放掉:这个对象从此不再用,取消它自己的协程。 */
        fun dispose()
    }

    private class Entry(val chat: Hosted) {
        var watchReplying: Job? = null
        var releaseWhenDone: Job? = null

        fun cancelWatches() {
            watchReplying?.cancel()
            releaseWhenDone?.cancel()
        }
    }

    private val entries = HashMap<String, Entry>()

    private val _unread = MutableStateFlow<Set<String>>(emptySet())

    /** 关着的时候写完了、他还没看的那几条。只记在内存里:进程被收走时在飞的回复本来也就停了。 */
    val unread: StateFlow<Set<String>> = _unread.asStateFlow()

    private val _replying = MutableStateFlow<Set<String>>(emptySet())

    /** 回复还在写的那几条(列表那一行写「正在回复」)。 */
    val replying: StateFlow<Set<String>> = _replying.asStateFlow()

    /** 打开一条侧聊。还在写的那一个就接着用,不再造新的。 */
    @Suppress("UNCHECKED_CAST")
    fun <T : Hosted> open(id: String, make: () -> T): T {
        _unread.update { it - id }
        entries[id]?.let { entry ->
            entry.releaseWhenDone?.cancel()
            entry.releaseWhenDone = null
            return entry.chat as T
        }
        val chat = make()
        val entry = Entry(chat)
        entry.watchReplying = viewModelScope.launch {
            chat.isReplying.collect { replying -> _replying.update { if (replying) it + id else it - id } }
        }
        entries[id] = entry
        return chat
    }

    /** 手里的那一个(测试和界面看一眼,不改任何状态)。 */
    fun hosted(id: String): Hosted? = entries[id]?.chat

    /** 那一页离开了。还在写就留着,写完亮未读点再放掉;没在写就现在放掉。 */
    fun close(id: String) {
        val entry = entries[id] ?: return
        if (!entry.chat.isReplying.value) return release(id)
        entry.releaseWhenDone?.cancel()
        entry.releaseWhenDone = viewModelScope.launch {
            entry.chat.isReplying.first { !it }
            _unread.update { it + id }
            release(id)
        }
    }

    /**
     * 要删这条侧聊:当场停下,不收割(删完再去读它没有意义)。返回的 job 是停下之后的那次落盘,删目录要排在
     * 它后面——先删再写,删掉的内容会作为孤儿目录落回盘上。
     */
    fun discard(id: String): Job? {
        _unread.update { it - id }
        val entry = entries.remove(id) ?: return null
        entry.cancelWatches()
        _replying.update { it - id }
        val leaving = entry.chat.leave(harvesting = false)
        entry.chat.dispose()
        return leaving
    }

    /** 全部放掉。还在写的那几个停下(那是上一位成员的对话,或者刚被清空的对话)。 */
    fun releaseAll() {
        entries.keys.toList().forEach(::release)
    }

    override fun onCleared() {
        releaseAll()
        super.onCleared()
    }

    private fun release(id: String) {
        val entry = entries.remove(id) ?: return
        entry.cancelWatches()
        _replying.update { it - id }
        entry.chat.leave(harvesting = true)
        entry.chat.dispose()
    }
}

/**
 * 宿主里的一条侧聊:聊天 view model 加它自己的那一格 [ViewModelStore]。放掉时清那一格——那样 view model 走
 * 正常的 `onCleared`,`viewModelScope` 里那几个收着线程流的协程跟着取消,不会挂在一个永远不死的写者上。
 */
class HostedSideChat private constructor(
    val viewModel: ChatViewModel,
    private val store: ViewModelStore,
) : SideChatHost.Hosted {
    override val isReplying: StateFlow<Boolean> get() = viewModel.isReplying

    override fun leave(harvesting: Boolean): Job? = viewModel.leaveSideChat(harvesting)

    override fun dispose() = store.clear()

    companion object {
        fun make(factory: ViewModelProvider.Factory): HostedSideChat {
            val store = ViewModelStore()
            return HostedSideChat(ViewModelProvider(store, factory)[ChatViewModel::class.java], store)
        }
    }
}

/**
 * 侧聊那一页在返回栈上的「到访」。那一项被弹掉(返回、被快捷方式顶回主对话)时它被清掉,顺手告诉宿主
 * 「那一页离开了」。挂在返回栈项上而不是界面上:从侧聊里推出设置页那一下,界面离开了,返回栈项还在,不算离开。
 */
class SideChatVisit(private val onLeave: () -> Unit) : ViewModel() {
    override fun onCleared() {
        onLeave()
        super.onCleared()
    }
}
