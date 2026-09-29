package com.pinapia.vana.tasks

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

/**
 * 每个成员一份 `tasks.json`:提醒、目标、任务。
 *
 * 和 `MemoryStore` 同一套稳妥的读法:**逐条**解码,本版本读不懂的条目(更新版本写下的新种类、坏掉的一条)
 * 原样留在文件里、不会在下一次保存时被悄悄丢掉;整份读不出来时先备份再往下走。写入是临时文件加改名。
 */
class TaskStore(
    private val directory: File,
    private val json: Json = defaultJson,
) {
    private val file = File(directory, FILE_NAME)
    private val _revision = MutableStateFlow(0L)

    /** 每次写入之后加一。界面(任务页、今天卡片)据此重读。 */
    val revision: StateFlow<Long> = _revision.asStateFlow()

    private class Loaded(val tasks: List<Task>, val foreign: List<JsonElement>)

    private fun read(): Loaded {
        if (!file.exists()) return Loaded(emptyList(), emptyList())
        val text = runCatching { file.readText() }.getOrNull() ?: return Loaded(emptyList(), emptyList())
        val root = runCatching { json.parseToJsonElement(text) as JsonObject }.getOrNull()
        val array = when (val element = root?.get("tasks")) {
            null -> if (root == null) null else JsonArray(emptyList())
            is JsonArray -> element
            else -> null
        }
        if (array == null) {
            val backup = File(directory, "$FILE_NAME.bak")
            if (!backup.exists()) runCatching { file.copyTo(backup) }
            return Loaded(emptyList(), emptyList())
        }
        val tasks = mutableListOf<Task>()
        val foreign = mutableListOf<JsonElement>()
        for (element in array) {
            runCatching { json.decodeFromJsonElement(Task.serializer(), element) }
                .onSuccess { tasks += it }
                .onFailure { foreign += element }
        }
        return Loaded(tasks, foreign)
    }

    private fun write(tasks: List<Task>, foreign: List<JsonElement>) {
        val elements = tasks.map { json.encodeToJsonElement(Task.serializer(), it) } + foreign
        AtomicFiles.writeText(file, json.encodeToString(JsonObject.serializer(), JsonObject(mapOf("tasks" to JsonArray(elements)))))
        _revision.value = _revision.value + 1
    }

    @Synchronized
    fun all(): List<Task> = read().tasks.sortedBy { it.createdAt }

    fun active(): List<Task> = all().filter { it.isActive }

    fun byKind(kind: TaskKind): List<Task> = all().filter { it.kind == kind }

    @Synchronized
    fun get(id: String): Task? = read().tasks.firstOrNull { it.id == id }

    /** 按完整 id 或短编号(前缀)找。前缀不唯一时返回 null——宁可找不到,也别动错一条。 */
    @Synchronized
    fun find(idOrHandle: String): Task? {
        val key = idOrHandle.trim()
        if (key.isEmpty()) return null
        val tasks = read().tasks
        tasks.firstOrNull { it.id == key }?.let { return it }
        return tasks.filter { it.id.startsWith(key, ignoreCase = true) }.singleOrNull()
    }

    @Synchronized
    fun add(task: Task): Task {
        val loaded = read()
        write(loaded.tasks + task, loaded.foreign)
        return task
    }

    @Synchronized
    fun update(id: String, now: Instant = Clock.System.now(), transform: (Task) -> Task): Task? {
        val loaded = read()
        val index = loaded.tasks.indexOfFirst { it.id == id }
        if (index < 0) return null
        val next = transform(loaded.tasks[index]).copy(id = id, updatedAt = now)
        val tasks = loaded.tasks.toMutableList().also { it[index] = next }
        write(tasks, loaded.foreign)
        return next
    }

    @Synchronized
    fun delete(id: String): Boolean {
        val loaded = read()
        if (loaded.tasks.none { it.id == id }) return false
        write(loaded.tasks.filterNot { it.id == id }, loaded.foreign)
        return true
    }

    @Synchronized
    fun removeAll() {
        write(emptyList(), emptyList())
    }

    companion object {
        const val FILE_NAME = "tasks.json"

        val defaultJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
