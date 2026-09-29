package com.pinapia.vana.storage

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 整文件写入用「先写同目录临时文件、落盘、再改名」。
 *
 * 直接 `writeText` 在写到一半时进程被杀，留下的是半个 JSON；而读的一侧多半是
 * `runCatching { decode }.getOrNull()`，半个文件读出来就是「什么都没有」，下一次保存
 * 再把它当空的覆盖掉——整份历史就这样没了。改名在同一目录内是原子的，读的一侧
 * 永远只会看到旧的完整文件或新的完整文件。
 */
object AtomicFiles {
    fun writeText(file: File, text: String) {
        val parent = file.absoluteFile.parentFile
        parent?.mkdirs()
        val temp = File(parent, "${file.name}.tmp")
        FileOutputStream(temp).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        try {
            Files.move(
                temp.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
