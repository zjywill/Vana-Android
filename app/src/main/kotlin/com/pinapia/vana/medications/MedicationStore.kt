package com.pinapia.vana.medications

import java.io.File
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class MedicationStore(
    private val directory: File,
    private val json: Json = defaultJson,
) {
    private val file = File(directory, "medications.json")

    @Serializable
    private data class Envelope(val items: List<MedicationItem> = emptyList())

    @Synchronized
    fun load(): List<MedicationItem> {
        if (!file.exists()) return emptyList()
        return runCatching {
            json.decodeFromString(Envelope.serializer(), file.readText()).items
                .sortedWith(medicationComparator)
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun save(items: List<MedicationItem>) {
        directory.mkdirs()
        file.writeText(
            json.encodeToString(
                Envelope.serializer(),
                Envelope(items.take(MAX_ITEMS).sortedWith(medicationComparator)),
            ),
        )
    }

    fun snapshot(): MedicationSnapshot = MedicationSnapshot(load())

    fun item(named: String): MedicationItem? = snapshot().item(named)

    fun dueFollowUps(at: Instant = Clock.System.now()): List<MedicationItem> =
        snapshot().due(at)

    fun add(item: MedicationItem): MedicationItem? {
        val trimmed = item.name.trim()
        if (trimmed.isEmpty()) return null
        val items = load().toMutableList()
        val existing = items.indexOfFirst {
            MedicationItem.normalize(it.name) == MedicationItem.normalize(trimmed)
        }
        if (existing >= 0) {
            val old = items[existing]
            val merged = old.copy(
                name = trimmed,
                status = item.status,
                whenText = item.whenText.ifBlank { old.whenText },
                reason = item.reason.ifBlank { old.reason },
                outcome = item.outcome.ifBlank { old.outcome },
                note = item.note.ifBlank { old.note },
                followUpAt = item.followUpAt ?: old.followUpAt,
                brief = if (item.brief.isNotBlank() && !old.briefIsUserWritten) item.brief else old.brief,
                briefIsUserWritten = old.briefIsUserWritten || item.briefIsUserWritten,
                startedAt = old.startedAt ?: item.startedAt,
                updatedAt = Clock.System.now(),
            )
            items[existing] = merged
            save(items)
            return merged
        }
        if (items.size >= MAX_ITEMS) return null
        val now = Clock.System.now()
        val created = item.copy(
            name = trimmed,
            startedAt = item.startedAt ?: if (
                item.status == MedicationItem.Status.ONGOING ||
                item.status == MedicationItem.Status.AS_NEEDED
            ) {
                now
            } else {
                null
            },
            createdAt = now,
            updatedAt = now,
        )
        items += created
        save(items)
        return created
    }

    fun upsert(
        name: String,
        status: MedicationItem.Status,
        whenText: String = "",
        reason: String = "",
        outcome: String = "",
        followUpDays: Int? = null,
        origin: MedicationItem.Origin = MedicationItem.Origin.ASKED,
    ): MedicationItem? {
        val now = Clock.System.now()
        val followUpAt = followUpDays?.coerceIn(3, 180)?.let {
            now.plus(it, DateTimeUnit.DAY, TimeZone.currentSystemDefault())
        }
        return add(
            MedicationItem(
                name = name,
                status = status,
                whenText = whenText.take(30),
                reason = reason.take(30),
                outcome = outcome.take(30),
                origin = origin,
                followUpAt = followUpAt,
            ),
        )
    }

    fun update(item: MedicationItem) {
        val items = load().toMutableList()
        val index = items.indexOfFirst { it.id == item.id }
        if (index < 0) return
        val trimmed = item.name.trim()
        if (trimmed.isEmpty()) return
        items[index] = item.copy(name = trimmed, updatedAt = Clock.System.now())
        save(items)
    }

    fun update(
        name: String,
        status: MedicationItem.Status? = null,
        outcome: String? = null,
        whenText: String? = null,
        reason: String? = null,
        clearFollowUp: Boolean = false,
    ): MedicationItem? {
        val items = load().toMutableList()
        val index = items.indexOfFirst {
            MedicationItem.normalize(it.name) == MedicationItem.normalize(name)
        }
        if (index < 0) return null
        val old = items[index]
        val cleared = clearFollowUp || !outcome.isNullOrBlank()
        val updated = old.copy(
            status = status ?: old.status,
            outcome = outcome?.takeIf { it.isNotBlank() }?.take(30) ?: old.outcome,
            whenText = whenText?.takeIf { it.isNotBlank() }?.take(30) ?: old.whenText,
            reason = reason?.takeIf { it.isNotBlank() }?.take(30) ?: old.reason,
            followUpAt = if (cleared) null else old.followUpAt,
            updatedAt = Clock.System.now(),
        )
        items[index] = updated
        save(items)
        return updated
    }

    fun setGeneratedBrief(id: String, text: String): Boolean {
        val items = load().toMutableList()
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return false
        val item = items[index]
        if (item.briefIsUserWritten) return false
        items[index] = item.copy(brief = text, updatedAt = Clock.System.now())
        save(items)
        return true
    }

    fun clearFollowUp(id: String) {
        val items = load().toMutableList()
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return
        items[index] = items[index].copy(followUpAt = null, updatedAt = Clock.System.now())
        save(items)
    }

    fun delete(id: String) {
        save(load().filterNot { it.id == id })
    }

    fun removeAll() {
        save(emptyList())
    }

    companion object {
        const val MAX_ITEMS = 200
        val defaultJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        private val medicationComparator = compareBy<MedicationItem>(
            { MedicationSnapshot.StatusOrder.indexOf(it.status).let { i -> if (i < 0) 99 else i } },
            { it.createdAt },
            { it.id },
        )
    }
}
