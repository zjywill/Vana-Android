package com.pinapia.vana.recall

import com.pinapia.vana.plugins.OtherThreadsScope
import com.pinapia.vana.plugins.RecallReach
import com.pinapia.vana.thread.SideChatStore
import com.pinapia.vana.thread.ThreadWriter

/**
 * 召回除了这条对话自己,还够得着哪些线。
 *
 * 窗口各管各的,互通就靠召回和记忆:主对话够得着有内容的侧聊(并挂一份侧聊名单),侧聊够得着主对话和别的侧聊;
 * 别的线整条都算看不见。线的名字是给模型看的,**固定中文**,不跟着界面语言走。
 */
object SideChatRecall {
    /**
     * @param current 这是哪条侧聊;null 是主对话。
     * @return 别的线,和它们统称什么;一条都没有时第二个是 null。
     */
    suspend fun gather(
        main: ThreadWriter,
        sides: SideChatStore,
        current: String?,
    ): Pair<List<HistoryRecallTools.Source>, OtherThreadsScope?> {
        val isMain = current == null
        val others = ArrayList<HistoryRecallTools.Source>()
        val listings = ArrayList<RecallReach.Listing>()
        var reachesOtherSideChats = false
        if (!isMain) {
            main.archive.await()
            if (main.archive.size > 0) others += HistoryRecallTools.Source(label = "主对话", archive = main.archive)
        }
        for (chat in sides.all()) {
            if (chat.id == current) continue
            val archive = sides.writer(chat.id).archive
            archive.await()
            if (archive.size == 0) continue
            val title = chat.title.ifEmpty { "新侧聊" }
            others += HistoryRecallTools.Source(label = "侧聊「$title」", archive = archive)
            reachesOtherSideChats = true
            if (isMain) listings += RecallReach.Listing(title = title, lastActiveAt = chat.lastActiveAt)
        }
        if (others.isEmpty()) return others to null
        val scope = OtherThreadsScope(
            others = when {
                isMain -> "他开的侧聊"
                reachesOtherSideChats -> "主对话和别的侧聊"
                else -> "主对话"
            },
            sideChats = listings.take(RecallReach.MAX_LISTED),
        )
        return others to scope
    }
}
