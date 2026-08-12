package com.pinapia.vana.measurements

import java.io.File
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class MeasurementStore(
    private val directory: File,
    private val json: Json = defaultJson,
) {
    private val file = File(directory, "measurements.json")

    @Serializable
    private data class Envelope(val cards: List<MeasurementCard> = emptyList())

    @Synchronized
    fun load(): List<MeasurementCard> {
        if (!file.exists()) return emptyList()
        return runCatching {
            json.decodeFromString(Envelope.serializer(), file.readText()).cards
                .sortedWith(cardComparator)
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun save(cards: List<MeasurementCard>) {
        directory.mkdirs()
        val kept = cards
            .sortedWith(cardComparator)
            .take(MAX_ITEMS)
        file.writeText(
            json.encodeToString(Envelope.serializer(), Envelope(kept)),
        )
    }

    fun snapshot(): MeasurementSnapshot = MeasurementSnapshot(load())

    fun add(
        name: String,
        value: String,
        unit: String = "",
        observedAt: Instant,
        note: String = "",
    ): MeasurementCard? {
        val trimmedName = name.trim().take(MAX_NAME)
        val trimmedValue = value.trim().take(MAX_VALUE)
        if (trimmedName.isEmpty() || trimmedValue.isEmpty()) return null
        val cards = load().toMutableList()
        if (cards.size >= MAX_ITEMS) {
            // 满了就丢掉观测时间最旧的，给新卡腾位置。
            val oldestId = cards.minByOrNull { it.observedAt }?.id
            if (oldestId != null) cards.removeAll { it.id == oldestId }
        }
        val card = MeasurementCard(
            name = trimmedName,
            value = trimmedValue,
            unit = unit.trim().take(MAX_UNIT),
            observedAt = observedAt,
            note = note.trim().take(MAX_NOTE),
            recordedAt = Clock.System.now(),
        )
        cards += card
        save(cards)
        return card
    }

    fun list(
        name: String? = null,
        since: Instant? = null,
        limit: Int = 40,
    ): List<MeasurementCard> {
        val key = name?.let { MeasurementCard.normalize(it) }.orEmpty()
        return load()
            .asSequence()
            .filter { since == null || it.observedAt >= since }
            .filter { key.isEmpty() || MeasurementCard.normalize(it.name) == key }
            .take(limit.coerceIn(1, 100))
            .toList()
    }

    fun delete(id: String) {
        save(load().filterNot { it.id == id })
    }

    fun removeAll() {
        save(emptyList())
    }

    companion object {
        const val MAX_ITEMS = 500
        const val MAX_NAME = 40
        const val MAX_VALUE = 40
        const val MAX_UNIT = 16
        const val MAX_NOTE = 40

        val defaultJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        private val cardComparator = compareByDescending<MeasurementCard> { it.observedAt }
            .thenByDescending { it.recordedAt }
            .thenBy { it.id }
    }
}
