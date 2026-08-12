package com.pinapia.vana.measurements

import com.pinapia.vana.agentruntime.AgentToolOutput
import com.pinapia.vana.agentruntime.CapabilityDefinition
import com.pinapia.vana.agentruntime.CapabilityExecutionResult
import com.pinapia.vana.agentruntime.CapabilityInvocation
import com.pinapia.vana.agentruntime.CapabilityRegistry
import com.pinapia.vana.agentruntime.RuntimeJSONValue
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.toInstant

object MeasurementTools {
    const val LIST = "list_measurements"
    const val LOG = "log_measurement"

    fun registry(store: MeasurementStore, allowsWrites: Boolean): CapabilityRegistry {
        val definitions = buildList {
            add(listDefinition())
            if (allowsWrites) add(logDefinition())
        }
        return CapabilityRegistry(definitions = definitions) { invocation ->
            when (invocation.name) {
                LIST -> list(store, invocation)
                LOG -> if (allowsWrites) log(store, invocation) else writeDenied()
                else -> CapabilityExecutionResult(
                    output = AgentToolOutput(
                        kind = AgentToolOutput.Kind.TEXT,
                        text = "不支持名为 ${invocation.name} 的工具。",
                    ),
                    isError = true,
                )
            }
        }
    }

    private fun writeDenied() = CapabilityExecutionResult(
        output = AgentToolOutput(kind = AgentToolOutput.Kind.TEXT, text = "当前会话不允许写入测量卡片。"),
        isError = true,
    )

    private fun list(store: MeasurementStore, invocation: CapabilityInvocation): CapabilityExecutionResult {
        val input = runCatching { RuntimeJSONValue.decode(from = invocation.input) }.getOrNull()
        val name = input?.get("name")?.stringValue?.trim()
        val days = input?.get("days")?.intValue?.coerceIn(1, 365)
        val since = days?.let {
            Clock.System.now().minus(it, DateTimeUnit.DAY, TimeZone.currentSystemDefault())
        }
        val cards = store.list(name = name, since = since)
        if (cards.isEmpty()) {
            val scope = buildString {
                if (!name.isNullOrBlank()) append("「$name」")
                if (days != null) append("最近 ${days} 天")
            }.ifBlank { "全部" }
            return CapabilityExecutionResult(
                output = AgentToolOutput(
                    kind = AgentToolOutput.Kind.TEXT,
                    text = "测量卡片里没有${scope}记录。",
                ),
            )
        }
        val text = cards.joinToString("\n") { card ->
            buildString {
                append("- ${card.name}：${card.displayValue}（${card.observedLabel}）")
                if (card.note.isNotBlank()) append("；${card.note}")
            }
        }
        return CapabilityExecutionResult(
            output = AgentToolOutput(kind = AgentToolOutput.Kind.TEXT, text = text),
        )
    }

    private fun log(store: MeasurementStore, invocation: CapabilityInvocation): CapabilityExecutionResult {
        val input = runCatching { RuntimeJSONValue.decode(from = invocation.input) }.getOrNull()
            ?: return CapabilityExecutionResult(
                output = AgentToolOutput(kind = AgentToolOutput.Kind.TEXT, text = "log_measurement 需要 JSON 输入。"),
                isError = true,
            )

        val batch = input["items"]?.arrayValue
        val drafts = if (!batch.isNullOrEmpty()) {
            batch.mapIndexed { index, item ->
                parseDraft(item, index = index + 1)
            }
        } else {
            listOf(parseDraft(input, index = null))
        }

        val errors = drafts.mapNotNull { it.error }
        if (errors.isNotEmpty()) {
            return CapabilityExecutionResult(
                output = AgentToolOutput(
                    kind = AgentToolOutput.Kind.TEXT,
                    text = errors.joinToString("\n"),
                ),
                isError = true,
            )
        }

        val saved = mutableListOf<MeasurementCard>()
        for (draft in drafts) {
            val card = store.add(
                name = draft.name,
                value = draft.value,
                unit = draft.unit,
                observedAt = draft.observedAt!!,
                note = draft.note,
            ) ?: return CapabilityExecutionResult(
                output = AgentToolOutput(kind = AgentToolOutput.Kind.TEXT, text = "测量卡片写入失败。"),
                isError = true,
            )
            saved += card
        }

        val text = saved.joinToString("\n") { card ->
            "已记下「${card.name}」${card.displayValue}（${card.observedLabel}）"
        }
        return CapabilityExecutionResult(
            output = AgentToolOutput(kind = AgentToolOutput.Kind.TEXT, text = text),
        )
    }

    private data class Draft(
        val name: String = "",
        val value: String = "",
        val unit: String = "",
        val observedAt: Instant? = null,
        val note: String = "",
        val error: String? = null,
    )

    private fun parseDraft(node: RuntimeJSONValue?, index: Int?): Draft {
        val prefix = if (index != null) "第 ${index} 条：" else ""
        if (node == null) {
            return Draft(error = "${prefix}缺少条目。")
        }
        val name = node["name"]?.stringValue?.trim().orEmpty()
        val value = readValue(node["value"])
        val unit = node["unit"]?.stringValue?.trim().orEmpty()
        val note = node["note"]?.stringValue?.trim().orEmpty()
        val observedRaw = node["observedAt"]?.stringValue?.trim().orEmpty()
        if (name.isEmpty() || value.isEmpty()) {
            return Draft(error = "${prefix}需要 name 和 value。")
        }
        val observedAt = parseObservedAt(observedRaw)
            ?: return Draft(
                error = "${prefix}观测时间 observedAt 必须是具体日期或日期时间" +
                    "（如 2026-08-12 或 2026-08-12T08:30:00）。" +
                    "说不清是哪天时先用 ask_user，不要猜成现在。",
            )
        return Draft(
            name = name,
            value = value,
            unit = unit,
            observedAt = observedAt,
            note = note,
        )
    }

    private fun readValue(node: RuntimeJSONValue?): String {
        if (node == null) return ""
        node.stringValue?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        node.doubleValue?.let { number ->
            return if (number == number.toLong().toDouble()) {
                number.toLong().toString()
            } else {
                number.toString()
            }
        }
        node.intValue?.let { return it.toString() }
        return ""
    }

    internal fun parseObservedAt(raw: String): Instant? {
        if (raw.isBlank()) return null
        val trimmed = raw.trim()
        runCatching { Instant.parse(trimmed) }.getOrNull()?.let { return it }
        runCatching {
            LocalDate.parse(trimmed).atStartOfDayIn(TimeZone.currentSystemDefault())
        }.getOrNull()?.let { return it }
        runCatching {
            LocalDateTime.parse(trimmed).toInstant(TimeZone.currentSystemDefault())
        }.getOrNull()?.let { return it }
        // 常见变体：2026-08-12 08:30
        runCatching {
            LocalDateTime.parse(trimmed.replace(' ', 'T')).toInstant(TimeZone.currentSystemDefault())
        }.getOrNull()?.let { return it }
        return null
    }

    private fun listDefinition() = CapabilityDefinition(
        name = LIST,
        description = "读取用户口述记下的测量卡片（自由名称的 key-value，带观测时间）。" +
            "系统提示里已有每种名称最新一条；需要同名历史、或某段时间的多条时再调用。",
        inputSchema = objectSchema(
            mapOf(
                "name" to stringProp("只看这个指标名，用他说过的叫法。不传则看全部"),
                "days" to RuntimeJSONValue.ObjectValue(
                    mapOf(
                        "type" to RuntimeJSONValue.StringValue("integer"),
                        "description" to RuntimeJSONValue.StringValue(
                            "只看最近几天内观测的，范围 1–365。不传则不限",
                        ),
                        "minimum" to RuntimeJSONValue.IntValue(1),
                        "maximum" to RuntimeJSONValue.IntValue(365),
                    ),
                ),
            ),
        ),
    )

    private fun logDefinition() = CapabilityDefinition(
        name = LOG,
        description = "把用户口述的一次测量记成卡片：自由名称 + 数值 + 观测时间。" +
            "身高、体重、心率、血压可以，你不认识的化验项或他随口说的指标名也照记，不要拒。" +
            "每次都是新卡片，不会覆盖同名旧记录——今天 60、明天 70 会留下两条，方便看变化。" +
            "观测时间说不清时先用 ask_user（今天/昨天/今早/具体哪天），不要默认成现在，也不要用 remember。" +
            "一次可以说多项：用 items 数组；单项就直接填 name/value/observedAt。" +
            "名称保持用户原话；常见指标（身高体重血压心率等）会优先展示，陌生名照记但不抢位置。",
        inputSchema = objectSchema(
            mapOf(
                "name" to stringProp("指标名，用他说的叫法，不超过 40 个字。单项时必填"),
                "value" to stringProp("数值，保留他说的写法，如 72、120/80、36.5。单项时必填"),
                "unit" to stringProp("单位，如 kg、cm、次/分、mmHg、%。已写进 value 里就留空"),
                "observedAt" to stringProp(
                    "观测时间，必须是具体日期或日期时间，如 2026-08-12 或 2026-08-12T08:30:00。" +
                        "说不清时先 ask_user，不要填「今天」这类相对词",
                ),
                "note" to stringProp("一句备注，可选，不超过 40 个字"),
                "items" to RuntimeJSONValue.ObjectValue(
                    mapOf(
                        "type" to RuntimeJSONValue.StringValue("array"),
                        "description" to RuntimeJSONValue.StringValue(
                            "一次记下多张卡片时用。每项含 name、value、observedAt，可选 unit、note",
                        ),
                        "items" to cardItemSchema(),
                    ),
                ),
            ),
        ),
    )

    private fun cardItemSchema() = RuntimeJSONValue.ObjectValue(
        mapOf(
            "type" to RuntimeJSONValue.StringValue("object"),
            "properties" to RuntimeJSONValue.ObjectValue(
                mapOf(
                    "name" to stringProp("指标名"),
                    "value" to stringProp("数值"),
                    "unit" to stringProp("单位"),
                    "observedAt" to stringProp("观测时间，ISO 日期或日期时间"),
                    "note" to stringProp("备注"),
                ),
            ),
            "required" to RuntimeJSONValue.ArrayValue(
                listOf("name", "value", "observedAt").map { RuntimeJSONValue.StringValue(it) },
            ),
            "additionalProperties" to RuntimeJSONValue.BoolValue(false),
        ),
    )

    private fun objectSchema(
        properties: Map<String, RuntimeJSONValue>,
        required: List<String> = emptyList(),
    ): RuntimeJSONValue = RuntimeJSONValue.ObjectValue(
        buildMap {
            put("type", RuntimeJSONValue.StringValue("object"))
            put("properties", RuntimeJSONValue.ObjectValue(properties))
            put("required", RuntimeJSONValue.ArrayValue(required.map { RuntimeJSONValue.StringValue(it) }))
            put("additionalProperties", RuntimeJSONValue.BoolValue(false))
        },
    )

    private fun stringProp(description: String) = RuntimeJSONValue.ObjectValue(
        mapOf(
            "type" to RuntimeJSONValue.StringValue("string"),
            "description" to RuntimeJSONValue.StringValue(description),
        ),
    )
}
