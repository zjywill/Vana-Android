package com.pinapia.vana.notes

import com.pinapia.vana.storage.AtomicFiles
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** 每个成员一份 `notes.json`。读写的稳妥度和 `TaskStore`、`MemoryStore` 一致:逐条解码、读不懂的原样留着、原子写。 */
class NoteStore(
    private val directory: File,
    private val json: Json = defaultJson,
) {
    private val file = File(directory, FILE_NAME)
    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    private class Loaded(val notes: List<Note>, val foreign: List<JsonElement>)

    private fun read(): Loaded {
        if (!file.exists()) return Loaded(emptyList(), emptyList())
        val text = runCatching { file.readText() }.getOrNull() ?: return Loaded(emptyList(), emptyList())
        val root = runCatching { json.parseToJsonElement(text) as JsonObject }.getOrNull()
        val array = when (val element = root?.get("notes")) {
            null -> if (root == null) null else JsonArray(emptyList())
            is JsonArray -> element
            else -> null
        }
        if (array == null) {
            val backup = File(directory, "$FILE_NAME.bak")
            if (!backup.exists()) runCatching { file.copyTo(backup) }
            return Loaded(emptyList(), emptyList())
        }
        val notes = mutableListOf<Note>()
        val foreign = mutableListOf<JsonElement>()
        for (element in array) {
            runCatching { json.decodeFromJsonElement(Note.serializer(), element) }
                .onSuccess { notes += it }
                .onFailure { foreign += element }
        }
        return Loaded(notes, foreign)
    }

    private fun write(notes: List<Note>, foreign: List<JsonElement>) {
        val elements = notes.map { json.encodeToJsonElement(Note.serializer(), it) } + foreign
        AtomicFiles.writeText(file, json.encodeToString(JsonObject.serializer(), JsonObject(mapOf("notes" to JsonArray(elements)))))
        _revision.value = _revision.value + 1
    }

    /** 最近动过的在前。 */
    @Synchronized
    fun all(): List<Note> = read().notes.sortedByDescending { it.updatedAt }

    @Synchronized
    fun get(id: String): Note? = read().notes.firstOrNull { it.id == id }

    /** 按完整 id 或短编号(前缀)找。前缀不唯一时返回 null——宁可找不到,也别动错一条。 */
    @Synchronized
    fun find(idOrHandle: String): Note? {
        val key = idOrHandle.trim()
        if (key.isEmpty()) return null
        val notes = read().notes
        notes.firstOrNull { it.id == key }?.let { return it }
        return notes.filter { it.id.startsWith(key, ignoreCase = true) }.singleOrNull()
    }

    /** 到上限时返回 null,由调用方告诉用户先清理。 */
    @Synchronized
    fun add(note: Note): Note? {
        val loaded = read()
        if (loaded.notes.size >= Note.MAX_NOTES) return null
        write(loaded.notes + note, loaded.foreign)
        return note
    }

    @Synchronized
    fun update(id: String, now: Instant = Clock.System.now(), transform: (Note) -> Note): Note? {
        val loaded = read()
        val index = loaded.notes.indexOfFirst { it.id == id }
        if (index < 0) return null
        val next = transform(loaded.notes[index]).copy(id = id, updatedAt = now)
        val notes = loaded.notes.toMutableList().also { it[index] = next }
        write(notes, loaded.foreign)
        return next
    }

    @Synchronized
    fun delete(id: String): Boolean {
        val loaded = read()
        if (loaded.notes.none { it.id == id }) return false
        write(loaded.notes.filterNot { it.id == id }, loaded.foreign)
        return true
    }

    @Synchronized
    fun removeAll() = write(emptyList(), emptyList())

    companion object {
        const val FILE_NAME = "notes.json"
        val defaultJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
