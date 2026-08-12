package com.pinapia.vana.memory

import java.util.UUID
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class MemoryItem(
    val id: String = UUID.randomUUID().toString(),
    var text: String,
    val kind: Kind,
    val origin: Origin = Origin.ASKED,
    val createdAt: Instant = Clock.System.now(),
    var updatedAt: Instant = Clock.System.now(),
    /** 待跟进到期时间。iOS 字段名 dueAt；旧 Android 用 followUpAt。 */
    @SerialName("dueAt")
    @JsonNames("followUpAt")
    var dueAt: Instant? = null,
    var sourceSessionId: String? = null,
) {
    /** 兼容旧调用点。 */
    var followUpAt: Instant?
        get() = dueAt
        set(value) {
            dueAt = value
        }

    @Serializable
    enum class Kind {
        @SerialName("profile") PROFILE,
        @SerialName("preference") PREFERENCE,
        @SerialName("interpretation") INTERPRETATION,
        @SerialName("followUp") FOLLOW_UP,
        ;

        val label: String
            get() = when (this) {
                PROFILE -> "长期情况"
                PREFERENCE -> "表达偏好"
                INTERPRETATION -> "已有解释"
                FOLLOW_UP -> "待跟进"
            }

        val hint: String
            get() = when (this) {
                PROFILE -> "作息、工作、伤病限制、正在进行的目标"
                PREFERENCE -> "希望 Vana 怎么说话、自己看重哪个指标"
                INTERPRETATION -> "对你而言某个指标的正常范围，或某段异常的原因"
                FOLLOW_UP -> "说好过一阵子再看的事，到点会在 check-in 里提醒你"
            }
    }

    @Serializable
    enum class Origin {
        @SerialName("manual") MANUAL,
        @SerialName("asked") ASKED,
        @SerialName("extracted") EXTRACTED,
    }

    /** 手动/对话记下的永不被抽取覆盖或容量淘汰。 */
    val pinned: Boolean get() = origin != Origin.EXTRACTED

    fun isDue(at: Instant = Clock.System.now()): Boolean =
        dueAt != null && dueAt!! <= at

    fun hasExpired(at: Instant = Clock.System.now(), graceSeconds: Long = FOLLOW_UP_GRACE_SECONDS): Boolean {
        val due = dueAt ?: return false
        return due.toEpochMilliseconds() + graceSeconds * 1000L <= at.toEpochMilliseconds()
    }

    val originLabel: String
        get() = when (origin) {
            Origin.MANUAL -> "你写的"
            Origin.ASKED -> "你让我记的"
            Origin.EXTRACTED -> "从对话中记下"
        }

    companion object {
        /** 到期后再留 3 天宽限期，之后从可读列表里消失。 */
        const val FOLLOW_UP_GRACE_SECONDS = 3L * 86_400L
    }
}

data class MemorySnapshot(
    val items: List<MemoryItem> = emptyList(),
) {
    val isEmpty: Boolean get() = items.isEmpty()

    val instructionBlock: String?
        get() {
            if (items.isEmpty()) return null
            val now = Clock.System.now()
            val lines = items.take(MAX_ITEMS).map { item ->
                val dueNote = if (item.kind == MemoryItem.Kind.FOLLOW_UP && item.isDue(now)) {
                    "（说好的时间已经到了）"
                } else {
                    ""
                }
                "- [${item.kind.label}] ${item.text}$dueNote"
            }
            val body = lines.joinToString("\n").take(MAX_CHARS)
            return """
                关于这位用户（来自过往对话，不是健康数据）：
                $body
                以上只用于理解他的处境和表达方式。任何具体数值一律以本次工具返回的为准，记忆与工具结果冲突时以工具结果为准，也不要把上面的内容当成诊断。
            """.trimIndent()
        }

    fun due(at: Instant = Clock.System.now()): List<MemoryItem> =
        items.filter { it.kind == MemoryItem.Kind.FOLLOW_UP && it.isDue(at) }

    companion object {
        val empty = MemorySnapshot()
        const val MAX_ITEMS = 40
        const val MAX_CHARS = 2000
        val KindOrder = listOf(
            MemoryItem.Kind.PROFILE,
            MemoryItem.Kind.PREFERENCE,
            MemoryItem.Kind.INTERPRETATION,
            MemoryItem.Kind.FOLLOW_UP,
        )
    }
}
