package com.pinapia.vana.memory

import com.pinapia.vana.storage.AtomicFiles
import java.io.File
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * `memory.json` 的读写。
 *
 * 解码是**逐条**的:本版本认不得的条目(更新版本写下的新 `Kind`、损坏的一条)不会拖垮整份文件,
 * 也不会在下一次保存时被悄悄丢掉——它们原样留在文件里([Loaded.foreign])。整份文件读不出来时
 * 先备份成 `memory.json.bak` 再往下走。以前是 `runCatching { decode }.getOrDefault(empty)`,
 * 下一次 `save` 就把一份读不出来的文件覆盖成空的。
 */
class MemoryStore(
    private val directory: File,
    private val json: Json = defaultJson,
) {
    private val file = File(directory, "memory.json")

    private class Loaded(val items: List<MemoryItem>, val foreign: List<JsonElement>)

    private fun read(): Loaded {
        if (!file.exists()) return Loaded(emptyList(), emptyList())
        val text = runCatching { file.readText() }.getOrNull()
            ?: return Loaded(emptyList(), emptyList())
        val root = runCatching { json.parseToJsonElement(text) as JsonObject }.getOrNull()
        val array = when (val element = root?.get("items")) {
            null -> if (root == null) null else JsonArray(emptyList())
            is JsonArray -> element
            else -> null
        }
        if (array == null) {
            backUpUnreadable()
            return Loaded(emptyList(), emptyList())
        }
        val items = mutableListOf<MemoryItem>()
        val foreign = mutableListOf<JsonElement>()
        for (element in array) {
            runCatching { json.decodeFromJsonElement(MemoryItem.serializer(), element) }
                .onSuccess { items += it }
                .onFailure { foreign += element }
        }
        return Loaded(items, foreign)
    }

    /** 只留第一份:之后再坏的文件不该盖掉当初还读得出来的那份。 */
    private fun backUpUnreadable() {
        val backup = File(directory, "memory.json.bak")
        if (backup.exists()) return
        runCatching { file.copyTo(backup) }
    }

    @Synchronized
    fun load(now: Instant = Clock.System.now()): List<MemoryItem> =
        read().items
            .filterNot { it.hasExpired(now) }
            .sortedWith(memoryComparator)

    @Synchronized
    fun save(items: List<MemoryItem>, now: Instant = Clock.System.now()) {
        val capped = evicting(items, now)
        val foreign = read().foreign
        write(capped.map { json.encodeToJsonElement(MemoryItem.serializer(), it) } + foreign)
    }

    private fun write(elements: List<JsonElement>) {
        val root = JsonObject(mapOf("items" to JsonArray(elements)))
        AtomicFiles.writeText(file, json.encodeToString(JsonObject.serializer(), root))
    }

    fun snapshot(now: Instant = Clock.System.now()): MemorySnapshot =
        MemorySnapshot(load(now))

    @Synchronized
    fun remember(
        text: String,
        kind: MemoryItem.Kind,
        origin: MemoryItem.Origin = MemoryItem.Origin.ASKED,
        days: Int? = null,
        sourceSessionId: String? = null,
        now: Instant = Clock.System.now(),
    ): MemoryItem? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val items = load(now).toMutableList()
        val dueAt = MemoryItem.dueFor(kind, days, now)
        val item = MemoryItem(
            text = trimmed,
            kind = kind,
            origin = origin,
            dueAt = dueAt,
            sourceSessionId = sourceSessionId,
            createdAt = now,
            updatedAt = now,
        )
        items += item
        save(items, now)
        // 容量满时 pinned 可能挤掉新提取项；确认是否还在
        val kept = load(now)
        return kept.firstOrNull { it.id == item.id }
    }

    @Synchronized
    fun update(item: MemoryItem, now: Instant = Clock.System.now()) {
        val items = load(now).toMutableList()
        val index = items.indexOfFirst { it.id == item.id }
        if (index < 0) return
        items[index] = item.copy(updatedAt = now)
        save(items, now)
    }

    @Synchronized
    fun delete(id: String, now: Instant = Clock.System.now()) {
        save(load(now).filterNot { it.id == id }, now)
    }

    /**
     * 「忘掉全部」。连本版本读不懂的条目一起清:用户点的是忘掉,不是「忘掉我看得见的那部分」。
     */
    @Synchronized
    fun removeAll() {
        write(emptyList())
    }

    private fun evicting(items: List<MemoryItem>, now: Instant): List<MemoryItem> {
        val kept = items.filterNot { it.hasExpired(now) }.toMutableList()

        /** 淘汰 [candidates] 里最旧的一条**不受保护的**;都受保护(用户自己写的)就不动,返回 false。 */
        fun evictOldest(candidates: (MemoryItem) -> Boolean): Boolean {
            val removable = kept.withIndex()
                .filter { candidates(it.value) && !it.value.pinned }
                .minByOrNull { it.value.updatedAt }
                ?: return false
            kept.removeAt(removable.index)
            return true
        }

        // 近况先按自己的上限淡掉:它是「最近的事」,攒到十条以上就不是近况了。
        while (kept.count { it.kind == MemoryItem.Kind.EPISODE } > MemoryItem.MAX_EPISODES) {
            if (!evictOldest { it.kind == MemoryItem.Kind.EPISODE }) break
        }
        fun overCapacity(): Boolean {
            if (kept.size > MemorySnapshot.MAX_ITEMS) return true
            return kept.sumOf { it.text.length } > MemorySnapshot.MAX_CHARS
        }
        while (overCapacity()) {
            if (!evictOldest { true }) break
        }
        return kept.sortedWith(memoryComparator)
    }

    companion object {
        val defaultJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        private val memoryComparator = compareBy<MemoryItem>(
            { MemorySnapshot.KindOrder.indexOf(it.kind).let { i -> if (i < 0) 99 else i } },
            { it.createdAt },
            { it.id },
        )
    }
}
