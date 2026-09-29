package com.pinapia.vana.memory

import com.pinapia.vana.ui.L10n
import java.util.UUID
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
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

        /** 近况:最近发生、还没了结的事。带过期时间,到点自己淡出,不是长期成立的事实。 */
        @SerialName("episode") EPISODE,

        /** 「已有解释」。哪个插件拥有它由 `VanaPlugin.memoryKinds` 声明,不写死在这里。 */
        @SerialName("interpretation") INTERPRETATION,
        @SerialName("followUp") FOLLOW_UP,
        ;

        /** 到期时间语义:待跟进到期后还留宽限期(那正是它要派上用场的时候),近况到点就消失。 */
        val graceSeconds: Long
            get() = if (this == FOLLOW_UP) FOLLOW_UP_GRACE_SECONDS else 0L

        /**
         * **给模型看的**标签,固定中文,不随界面语言变。模型可见的提示词其余部分本来就是中文;
         * 标签跟着界面语言变(英文界面下成了 `[Communication preferences]`)会让同一份记忆在不同语言下
         * 渲染成不同长度、不同前缀——既让预算不稳,也让缓存前缀凭空多出一个变量。
         */
        val promptLabel: String
            get() = when (this) {
                PROFILE -> "长期情况"
                PREFERENCE -> "表达偏好"
                EPISODE -> "近况"
                INTERPRETATION -> "已有解释"
                FOLLOW_UP -> "待跟进"
            }

        /** 界面上的标签,跟界面语言走。 */
        val label: String
            get() = when (this) {
                PROFILE -> L10n.text("长期情况", "Long-term context")
                PREFERENCE -> L10n.text("表达偏好", "Communication preferences")
                EPISODE -> L10n.text("近况", "Recent")
                INTERPRETATION -> L10n.text("已有解释", "Established interpretation")
                FOLLOW_UP -> L10n.text("待跟进", "Follow-up")
            }

        val hint: String
            get() = when (this) {
                PROFILE -> L10n.text(
                    "作息、工作或学习、身体上的限制、家人，还有正在进行的目标",
                    "Schedule, work or study, physical limits, family and ongoing goals",
                )
                PREFERENCE -> L10n.text(
                    "希望 Vana 怎么说话、怎么做事，自己看重什么",
                    "How you want Vana to communicate and act, and what matters to you",
                )
                EPISODE -> L10n.text(
                    "最近发生、还没完的事，过一阵子会自己淡出",
                    "Recent happenings still in progress; they fade out on their own",
                )
                INTERPRETATION -> L10n.text(
                    "已经讨论清楚的结论，比如某个指标对你而言的正常范围",
                    "A conclusion already settled, such as what is normal for you on some measure",
                )
                FOLLOW_UP -> L10n.text(
                    "说好过一阵子再看的事，到点会在 check-in 里提醒你",
                    "Something to revisit later; Vana includes it in a check-in when due",
                )
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

    fun hasExpired(at: Instant = Clock.System.now(), graceSeconds: Long = kind.graceSeconds): Boolean {
        val due = dueAt ?: return false
        return due.toEpochMilliseconds() + graceSeconds * 1000L <= at.toEpochMilliseconds()
    }

    val originLabel: String
        get() = when (origin) {
            Origin.MANUAL -> L10n.text("你写的", "Added by you")
            Origin.ASKED -> L10n.text("你让我记的", "Saved at your request")
            Origin.EXTRACTED -> L10n.text("从对话中记下", "Remembered from a conversation")
        }

    companion object {
        /** 待跟进到期后再留 3 天宽限期,之后从可读列表里消失。 */
        const val FOLLOW_UP_GRACE_SECONDS = 3L * 86_400L

        const val DEFAULT_EXPIRY_DAYS = 14
        const val MAX_FOLLOW_UP_DAYS = 180
        const val MAX_EPISODE_DAYS = 60

        /** 一条记忆一句话:抽取器、`remember`、`revise_memory` 共用这一个上限。 */
        const val MAX_TEXT_CHARS = 120

        /** 近况是「最近的事」,不是第二份长期记忆:攒多了先淡掉最旧的。 */
        const val MAX_EPISODES = 10

        /** 哪些种类带过期时间。 */
        fun expires(kind: Kind): Boolean = kind == Kind.FOLLOW_UP || kind == Kind.EPISODE

        /** [kind] 的过期时间;不带过期的种类返回 null。[days] 缺省 14 天,按种类夹在各自的上限里。 */
        fun dueFor(kind: Kind, days: Int?, now: Instant): Instant? {
            val upper = when (kind) {
                Kind.FOLLOW_UP -> MAX_FOLLOW_UP_DAYS
                Kind.EPISODE -> MAX_EPISODE_DAYS
                else -> return null
            }
            val clamped = (days ?: DEFAULT_EXPIRY_DAYS).coerceIn(1, upper)
            return now.plus(clamped, DateTimeUnit.DAY, TimeZone.currentSystemDefault())
        }

        /** 比较用:去掉空白和标点、不分大小写。「不吃香菜。」和「不吃 香菜」是同一句。 */
        fun normalized(text: String): String =
            text.lowercase().filter { it.isLetterOrDigit() }
    }
}

data class MemorySnapshot(
    val items: List<MemoryItem> = emptyList(),
) {
    val isEmpty: Boolean get() = items.isEmpty()

    /** 只留 [keep] 认可的条目。插件关掉之后,它拥有的那类记忆不再带进对话(数据还在盘上)。 */
    fun filtered(keep: (MemoryItem) -> Boolean): MemorySnapshot = MemorySnapshot(items.filter(keep))

    /**
     * 进 system 段的那一块。每行带短编号(M1、M2…):对话里「忘掉…」「不对，其实是…」靠它指到某一条。
     * 编号就是条目在 [items] 里的位置,和抽取器看到的 [handleListing] 是同一套,排序稳定才不会指错。
     *
     * 按渲染出来的整行算预算,并且**在行边界上截**——以前是整段 `.take(2000)`,会把最后一行切在半截。
     * 到期的待跟进优先留:那是用户自己定下的约定。
     */
    val instructionBlock: String?
        get() {
            if (items.isEmpty()) return null
            val now = Clock.System.now()
            val indexed = items.take(MAX_ITEMS).withIndex().toList()
            fun line(entry: IndexedValue<MemoryItem>): String {
                val item = entry.value
                val dueNote = if (item.kind == MemoryItem.Kind.FOLLOW_UP && item.isDue(now)) {
                    "（说好的时间已经到了）"
                } else {
                    ""
                }
                return "- ${handle(entry.index)} [${item.kind.promptLabel}] ${item.text}$dueNote"
            }
            var budget = BLOCK_BUDGET
            val kept = mutableSetOf<Int>()
            indexed.filter { it.value.kind == MemoryItem.Kind.FOLLOW_UP && it.value.isDue(now) }.forEach {
                kept += it.index
                budget -= line(it).length + 1
            }
            for (entry in indexed) {
                if (entry.index in kept) continue
                val length = line(entry).length + 1
                if (length <= budget) {
                    kept += entry.index
                    budget -= length
                }
            }
            val body = indexed.filter { it.index in kept }.joinToString("\n") { line(it) }
            // 不用 `"""…$body…""".trimIndent()`:多行的 $body 从第二行起没有缩进,trimIndent 算出的最小缩进是 0,
            // 标题、第一条和结尾说明就都带着模板里那十几个空格发给了模型。
            return listOf(
                "关于这位用户（来自过往对话）：",
                body,
                "以上只用于理解他的处境和表达方式。任何具体数值一律以本次工具返回的为准，记忆与工具结果冲突时以工具结果为准。",
            ).joinToString("\n")
        }

    fun due(at: Instant = Clock.System.now()): List<MemoryItem> =
        items.filter { it.kind == MemoryItem.Kind.FOLLOW_UP && it.isDue(at) }

    companion object {
        val empty = MemorySnapshot()
        const val MAX_ITEMS = 40
        const val MAX_CHARS = 2000

        /** 每行除了正文还有「- M12 [长期情况] 」这段前缀。预算把它算上,存满 40 条也能整块放下。 */
        private const val LINE_OVERHEAD = 20
        const val BLOCK_BUDGET = MAX_CHARS + MAX_ITEMS * LINE_OVERHEAD

        fun handle(index: Int): String = "M${index + 1}"

        val KindOrder = listOf(
            MemoryItem.Kind.PROFILE,
            MemoryItem.Kind.PREFERENCE,
            MemoryItem.Kind.EPISODE,
            MemoryItem.Kind.INTERPRETATION,
            MemoryItem.Kind.FOLLOW_UP,
        )
    }
}
