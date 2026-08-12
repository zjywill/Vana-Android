package com.pinapia.vana.medications

import java.util.UUID
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class MedicationItem(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var status: Status = Status.AS_NEEDED,
    /** 什么情况下吃。「头疼时」「冬天」「每天早上」——asNeeded 主要用。 */
    @SerialName("when")
    @JsonNames("situation")
    var whenText: String = "",
    /** 为什么吃 / 谁让吃的。 */
    var reason: String = "",
    /** 他自己的效果评价——这张表最值钱的一列。 */
    var outcome: String = "",
    /** 一般功效说明（MedicationBriefer 生成，不是给他的建议）。 */
    var brief: String = "",
    var briefIsUserWritten: Boolean = false,
    var note: String = "",
    val origin: Origin = Origin.MANUAL,
    var followUpAt: Instant? = null,
    var startedAt: Instant? = null,
    val createdAt: Instant = Clock.System.now(),
    var updatedAt: Instant = Clock.System.now(),
    var healthConceptId: String? = null,
) {
    @Serializable
    enum class Status {
        @SerialName("cannotTake") CANNOT_TAKE,
        @SerialName("ongoing") ONGOING,
        @SerialName("asNeeded") AS_NEEDED,
        @SerialName("tried") TRIED,
        ;

        val label: String
            get() = when (this) {
                CANNOT_TAKE -> "不能吃"
                ONGOING -> "长期在吃"
                AS_NEEDED -> "需要时吃"
                TRIED -> "试过了"
            }

        val hint: String
            get() = when (this) {
                CANNOT_TAKE -> "过敏、不耐受、医生说不能用的。Vana 给建议之前一定会先看这一组。"
                ONGOING -> "每天或按疗程在吃的。它会成为解读你健康数据时的前提。"
                AS_NEEDED -> "有需要才吃的。记下什么情况下吃，下次问起来 Vana 才接得上。"
                TRIED -> "试过之后的结论。记下来，Vana 就不会再推荐一次你已经试过的东西。"
            }
    }

    @Serializable
    enum class Origin {
        @SerialName("manual") MANUAL,
        @SerialName("asked") ASKED,
        @SerialName("health") HEALTH,
    }

    fun isFollowUpDue(at: Instant = Clock.System.now()): Boolean =
        followUpAt != null && followUpAt!! <= at

    val snapshotDetail: String
        get() {
            val parts = when (status) {
                Status.CANNOT_TAKE -> listOf(reason, note)
                Status.ONGOING -> listOf(reason, whenText)
                Status.AS_NEEDED -> listOf(whenText, outcome)
                Status.TRIED -> listOf(outcome, reason)
            }
            return parts.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("，")
        }

    val focusInstruction: String
        get() = buildString {
            append("这条对话围绕他记下的「$name」。状态：${status.label}。")
            reason.trim().takeIf { it.isNotEmpty() }?.let { append("他记的原因是「$it」。") }
            whenText.trim().takeIf { it.isNotEmpty() }?.let { append("他记的服用情况是「$it」。") }
            outcome.trim().takeIf { it.isNotEmpty() }?.let { append("他自己的评价是「$it」。") }
            append("照常查健康数据回答，把变化和这件事挂上钩，")
            append("但不要建议他调整剂量、停药或换药——那要问开药的医生或药师。")
        }

    val openingQuestions: List<String>
        get() = when (status) {
            Status.CANNOT_TAKE -> listOf("为什么我会对它有反应？", "有什么要避开的？", "有别的选择吗？")
            Status.ONGOING -> listOf("它和我最近的数据有关系吗？", "吃了这么久有变化吗？", "有什么要注意的？")
            Status.AS_NEEDED -> listOf("什么情况下该吃它？", "吃得太频繁了吗？", "有别的办法吗？")
            Status.TRIED -> listOf("为什么对我没用？", "要不要换一个试试？", "是不是时间不够？")
        }

    val subtitle: String
        get() = outcome.ifBlank { whenText.ifBlank { reason.ifBlank { brief } } }

    val originLabel: String
        get() = when (origin) {
            Origin.MANUAL -> "你自己加的"
            Origin.ASKED -> "你在对话里让我记的"
            Origin.HEALTH -> "来自「健康」App"
        }

    companion object {
        fun normalize(name: String): String =
            name.trim().lowercase()
    }
}

data class MedicationSnapshot(
    val items: List<MedicationItem> = emptyList(),
) {
    val isEmpty: Boolean get() = items.isEmpty()

    val instructionBlock: String?
        get() {
            if (items.isEmpty()) return null
            val kept = trimmed(items)
            val lines = mutableListOf("关于他和药/补剂（他自己记的，不是健康数据）：")
            for (status in StatusOrder) {
                for (item in kept.filter { it.status == status }) {
                    val detail = item.snapshotDetail
                    lines += "- 【${status.label}】${item.name}" +
                        if (detail.isEmpty()) "" else " — $detail"
                }
            }
            lines += "要提到吃什么之前先看这份表：他明确不能吃的绝对不要提；" +
                "他试过没用的不要再推荐一次，要提也得先说一句「你之前试过」。"
            lines += "剂量一律不给建议，也不要建议他停药或换药——那要问开药的医生或药师。"
            lines += "这份表只是他自己记下的，不是完整病历；" +
                "更全的内容（含没列在上面的）用 list_medications 查。"
            return lines.joinToString("\n")
        }

    fun due(at: Instant = Clock.System.now()): List<MedicationItem> =
        items.filter { it.isFollowUpDue(at) }

    fun item(named: String): MedicationItem? {
        val target = MedicationItem.normalize(named)
        if (target.isEmpty()) return null
        return items.firstOrNull { MedicationItem.normalize(it.name) == target }
    }

    companion object {
        val empty = MedicationSnapshot()
        const val MAX_LINES = 24
        const val MAX_CHARS = 800
        val StatusOrder = listOf(
            MedicationItem.Status.CANNOT_TAKE,
            MedicationItem.Status.ONGOING,
            MedicationItem.Status.AS_NEEDED,
            MedicationItem.Status.TRIED,
        )

        fun trimmed(items: List<MedicationItem>): List<MedicationItem> {
            val kept = items.toMutableList()
            fun isOver(): Boolean {
                if (kept.size > MAX_LINES) return true
                val chars = kept.sumOf { it.name.length + it.snapshotDetail.length }
                return chars > MAX_CHARS
            }
            while (isOver()) {
                val removable = kept.withIndex()
                    .filter { it.value.status != MedicationItem.Status.CANNOT_TAKE }
                    .minByOrNull { it.value.updatedAt }
                    ?: break
                kept.removeAt(removable.index)
            }
            return kept
        }
    }
}
