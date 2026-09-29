package com.pinapia.vana.thread

import java.io.File

/**
 * 旧的「一个会话一个文件」的存储,上线新线程存储时**整个清掉,不迁移**。
 *
 * 决定是用户做的(2026-09-29):旧对话不带过去。这省掉了迁移、校验和回退路径,代价是升级的
 * 用户看不到旧对话——发版说明里要写。记忆、用药、测量不在这里动,它们没有换存储。
 *
 * 旧会话引用的照片一并清掉:它们只被旧会话引用,留着就是永远删不掉的孤儿。
 * 用一个标记文件记住「清过了」,只清一次;全新安装没有旧目录,照样写标记。
 */
object LegacySessions {
    fun clearIfNeeded(root: File): Boolean {
        val marker = File(File(root, "thread"), ThreadStore.LEGACY_MARKER)
        if (marker.exists()) return false
        File(root, "sessions").deleteRecursively()
        File(root, "attachments").deleteRecursively()
        marker.parentFile?.mkdirs()
        marker.writeText("旧的会话存储已在新线程存储上线时清除，不迁移。")
        return true
    }
}
