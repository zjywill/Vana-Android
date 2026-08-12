package com.pinapia.vana.health

import kotlinx.serialization.Serializable

@Serializable
data class HealthReport(
    val title: String,
    var columns: List<String> = emptyList(),
    var rows: List<Row> = emptyList(),
    var summary: List<String> = emptyList(),
    var notes: List<String> = emptyList(),
) {
    @Serializable
    data class Row(
        val label: String,
        val values: List<String>,
    )

    val isEmpty: Boolean get() = rows.isEmpty()

    val modelText: String
        get() = buildList {
            add(title)
            if (rows.isNotEmpty()) {
                if (columns.isNotEmpty()) add(columns.joinToString(" | "))
                rows.forEach { add((listOf(it.label) + it.values).joinToString(" | ")) }
            }
            addAll(summary)
            addAll(notes)
        }.joinToString("\n")

    companion object {
        const val MISSING = "—"

        fun empty(title: String, note: String) = HealthReport(title = title, notes = listOf(note))
    }
}
