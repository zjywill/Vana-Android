package com.pinapia.vana.vision

import android.graphics.Bitmap
import android.util.Base64
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 附件落盘。会话 JSON 里只留文件名；原图归这里。
 * 隐私会话不写盘——内存缓存里还能看见，进程死了就没了。
 */
class AttachmentStore(parent: File) {
    private val directory = File(parent, "attachments").also { it.mkdirs() }
    private val memory = ConcurrentHashMap<String, ByteArray>()

    fun store(bytes: ByteArray, id: String = UUID.randomUUID().toString()): String {
        val name = fileName(id)
        memory[name] = bytes
        File(directory, name).writeBytes(bytes)
        return name
    }

    fun cache(bytes: ByteArray, id: String): String {
        val name = fileName(id)
        memory[name] = bytes
        return name
    }

    fun data(named: String): ByteArray? {
        memory[named]?.let { return it }
        val file = File(directory, named)
        if (!file.exists()) return null
        return file.readBytes().also { memory[named] = it }
    }

    fun remove(named: List<String>) {
        for (name in named) {
            memory.remove(name)
            File(directory, name).delete()
        }
    }

    companion object {
        fun fileName(id: String): String = "$id.jpg"
    }
}

object AttachmentImageCache {
    private val bitmaps = ConcurrentHashMap<String, Bitmap>()

    fun put(id: String, bitmap: Bitmap) {
        bitmaps[id] = bitmap
    }

    fun get(id: String): Bitmap? = bitmaps[id]

    fun remove(id: String) {
        bitmaps.remove(id)?.recycle()
    }

    fun clear() {
        bitmaps.keys.toList().forEach { remove(it) }
    }
}

fun ByteArray.toBase64(): String = Base64.encodeToString(this, Base64.NO_WRAP)
