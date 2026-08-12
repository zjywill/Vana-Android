package com.pinapia.vana.exercises

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ExerciseMove(
    val id: String,
    val zh: String,
    val en: String = "",
    val src: String = "",
    val scenes: List<String> = emptyList(),
    val part: String = "",
    val gear: String = "",
    val steps: List<String> = emptyList(),
    val cue: String = "",
    val avoid: String = "",
    val risk: List<String> = emptyList(),
    val floor: Boolean = false,
    val files: List<String> = emptyList(),
)

@Serializable
private data class ExerciseFile(
    val scenes: List<String> = emptyList(),
    val moves: List<ExerciseMove> = emptyList(),
)

class ExerciseLibrary private constructor(
    val moves: List<ExerciseMove>,
    val scenes: List<String>,
) {
    private val byId = moves.associateBy { it.id }

    operator fun get(id: String): ExerciseMove? = byId[id]

    fun moves(ids: List<String>): List<ExerciseMove> = ids.mapNotNull { byId[it] }

    fun suggest(
        scene: String,
        excludeJoints: List<String> = emptyList(),
        avoidsFloor: Boolean = false,
        limit: Int = 3,
    ): List<ExerciseMove> {
        val excluded = excludeJoints.toSet()
        return moves
            .asSequence()
            .filter { scene in it.scenes }
            .filter { excluded.intersect(it.risk.toSet()).isEmpty() }
            .filter { !(avoidsFloor && it.floor) }
            .take(limit.coerceIn(1, 4))
            .toList()
    }

    companion object {
        val attributions: List<String> = listOf(
            "动作图示 · everkinetic/data(CC BY-SA 4.0)",
            "动作图示 · Yoga icons created by dDara – Flaticon",
        )

        @Volatile
        private var cached: ExerciseLibrary? = null

        fun shared(context: Context): ExerciseLibrary {
            cached?.let { return it }
            return load(context.applicationContext).also { cached = it }
        }

        fun load(context: Context): ExerciseLibrary {
            return runCatching {
                val raw = context.assets.open("exercises.json").bufferedReader().use { it.readText() }
                val file = Json { ignoreUnknownKeys = true }.decodeFromString(ExerciseFile.serializer(), raw)
                ExerciseLibrary(
                    moves = file.moves.filter { it.files.isNotEmpty() },
                    scenes = file.scenes,
                )
            }.getOrElse {
                ExerciseLibrary(moves = emptyList(), scenes = emptyList())
            }
        }
    }
}
