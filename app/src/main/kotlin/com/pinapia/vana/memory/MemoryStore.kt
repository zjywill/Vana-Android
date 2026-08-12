package com.pinapia.vana.memory

import java.io.File
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class MemoryStore(
    private val directory: File,
    private val json: Json = defaultJson,
) {
    private val file = File(directory, "memory.json")

    @Serializable
    private data class Envelope(val items: List<MemoryItem> = emptyList())

    @Synchronized
    fun load(now: Instant = Clock.System.now()): List<MemoryItem> {
        if (!file.exists()) return emptyList()
        return runCatching {
            json.decodeFromString(Envelope.serializer(), file.readText()).items
                .filterNot { it.hasExpired(now) }
                .sortedWith(memoryComparator)
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun save(items: List<MemoryItem>, now: Instant = Clock.System.now()) {
        directory.mkdirs()
        val capped = evicting(items, now)
        file.writeText(json.encodeToString(Envelope.serializer(), Envelope(capped)))
    }

    fun snapshot(now: Instant = Clock.System.now()): MemorySnapshot =
        MemorySnapshot(load(now))

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
        val dueAt = if (kind == MemoryItem.Kind.FOLLOW_UP) {
            val d = (days ?: 14).coerceIn(1, 180)
            now.plus(d, DateTimeUnit.DAY, TimeZone.currentSystemDefault())
        } else {
            null
        }
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

    fun update(item: MemoryItem, now: Instant = Clock.System.now()) {
        val items = load(now).toMutableList()
        val index = items.indexOfFirst { it.id == item.id }
        if (index < 0) return
        items[index] = item.copy(updatedAt = now)
        save(items, now)
    }

    fun delete(id: String, now: Instant = Clock.System.now()) {
        save(load(now).filterNot { it.id == id }, now)
    }

    fun removeAll() {
        save(emptyList())
    }

    private fun evicting(items: List<MemoryItem>, now: Instant): List<MemoryItem> {
        val kept = items.filterNot { it.hasExpired(now) }.toMutableList()
        fun overCapacity(): Boolean {
            if (kept.size > MemorySnapshot.MAX_ITEMS) return true
            return kept.sumOf { it.text.length } > MemorySnapshot.MAX_CHARS
        }
        while (overCapacity()) {
            val removable = kept.withIndex()
                .filter { !it.value.pinned }
                .minByOrNull { it.value.updatedAt }
                ?: break
            kept.removeAt(removable.index)
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
