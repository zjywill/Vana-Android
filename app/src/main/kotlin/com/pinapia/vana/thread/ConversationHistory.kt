package com.pinapia.vana.thread

import kotlinx.datetime.Instant

/**
 * 「设置 › 对话历史」那三样:占用空间、清 N 天前的、清空全部。
 *
 * 范围是**一位成员名下的主对话加全部侧聊**——「清空全部对话」里的「全部」就是这个意思,占用空间和清理的
 * 范围也必须对得上。单删一条侧聊在「侧聊」页,单删一问一答在对话里长按。
 */
class ConversationHistory(
    private val main: ThreadWriter,
    private val sides: SideChatStore,
) {
    suspend fun sizeBytes(): Long = main.write { it.sizeBytes() } + sides.sizeBytes()

    /** 清掉 [cutoff] 之前的消息(连同只被它们引用的照片),返回一共清了多少条。 */
    suspend fun clearOlderThan(cutoff: Instant): Int =
        main.write { it.deleteOlderThan(cutoff) } + sides.deleteOlderThan(cutoff)

    /**
     * 主对话和全部侧聊一条不留,照片也一张不留。每条线程只删它自己引用的照片([ThreadStore.deleteAll]),
     * 所以最后再把仓库整个清一遍:崩在半路留下的、没人引用的那几张也一起走。
     */
    suspend fun clearAll() {
        main.write { it.deleteAll() }
        sides.deleteAll()
        main.write { it.attachments?.removeAll() }
    }
}
