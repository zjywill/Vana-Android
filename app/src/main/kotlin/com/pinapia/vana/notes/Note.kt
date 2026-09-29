package com.pinapia.vana.notes

import java.util.UUID
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class NoteKind {
    /** 一段文字:想法、草稿、要抄的一段话。 */
    @SerialName("note") NOTE,

    /** 一张清单:购物单、行李单,逐条能勾。 */
    @SerialName("list") LIST,
}

@Serializable
data class NoteItem(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val done: Boolean = false,
)

/**
 * 用户自己的内容。和记忆的分工:**记忆是关于他这个人的事实、常驻在上下文里;笔记是他要留着的东西、
 * 按需读写、不常驻**——购物单不该占着每一次对话的 system 段,也不该被当成「关于他的事」抽进记忆。
 */
@Serializable
data class Note(
    val id: String = UUID.randomUUID().toString(),
    val kind: NoteKind,
    val title: String,
    val body: String = "",
    val items: List<NoteItem> = emptyList(),
    val createdAt: Instant = Clock.System.now(),
    val updatedAt: Instant = createdAt,
) {
    /** 短编号:给模型和用户指到某一条用。 */
    val handle: String get() = id.take(HANDLE_LENGTH)

    /** 列表里第二行的字:一段话的开头,或清单的进度。 */
    val preview: String
        get() = when (kind) {
            NoteKind.NOTE -> body.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(60).orEmpty()
            NoteKind.LIST -> if (items.isEmpty()) "" else "${items.count { it.done }}/${items.size}"
        }

    companion object {
        const val HANDLE_LENGTH = 8
        const val MAX_NOTES = 100
        const val MAX_TITLE = 60
        const val MAX_BODY = 8000
        const val MAX_ITEMS = 100
        const val MAX_ITEM_CHARS = 120
    }
}
