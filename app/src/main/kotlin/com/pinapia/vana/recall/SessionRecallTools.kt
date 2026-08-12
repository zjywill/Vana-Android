package com.pinapia.vana.recall

import com.pinapia.vana.agentruntime.AgentToolOutput
import com.pinapia.vana.agentruntime.CapabilityDefinition
import com.pinapia.vana.agentruntime.CapabilityExecutionResult
import com.pinapia.vana.agentruntime.CapabilityInvocation
import com.pinapia.vana.agentruntime.CapabilityRegistry
import com.pinapia.vana.agentruntime.RuntimeJSONValue
import com.pinapia.vana.session.ChatMessage
import com.pinapia.vana.session.ChatSession
import com.pinapia.vana.session.SessionStore
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.max

object SessionRecallTools {
    const val SEARCH_TOOL_NAME = "search_sessions"
    const val READ_TOOL_NAME = "read_session"

    private const val MAX_CHARS = 2500
    private const val MAX_USER = 200
    private const val MAX_ASSISTANT = 320

    val footer = "（以上是当时说过的话，日期见开头。里面的具体数值都可能已经过时，要用就现在重新查一遍工具，一律以本次返回的为准。）"

    fun registry(
        store: SessionStore,
        currentSessionId: String?,
    ): CapabilityRegistry {
        val definitions = listOf(searchDefinition(), readDefinition())
        return CapabilityRegistry(definitions = definitions) { invocation ->
            when (invocation.name) {
                SEARCH_TOOL_NAME -> search(store, currentSessionId, invocation)
                READ_TOOL_NAME -> read(store, currentSessionId, invocation)
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

    private fun search(
        store: SessionStore,
        currentSessionId: String?,
        invocation: CapabilityInvocation,
    ): CapabilityExecutionResult {
        val input = runCatching { RuntimeJSONValue.decode(from = invocation.input) }.getOrNull()
        val query = input?.get("query")?.stringValue?.trim().orEmpty()
        val sinceDays = input?.get("since_days")?.intValue
        val indexed = buildIndex(store, currentSessionId, sinceDays)
        if (indexed.isEmpty()) {
            return CapabilityExecutionResult(
                output = AgentToolOutput(kind = AgentToolOutput.Kind.TEXT, text = "还没有可以回顾的过往对话。"),
            )
        }
        val matches = if (query.isEmpty()) {
            indexed.takeLast(6).reversed()
        } else {
            val scored = indexed.mapNotNull { entry ->
                val score = relevance(query, entry.userText)
                if (score <= 0) null else entry to score
            }
            if (scored.isEmpty()) {
                return CapabilityExecutionResult(
                    output = AgentToolOutput(kind = AgentToolOutput.Kind.TEXT, text = "没有找到相关的过往对话。"),
                )
            }
            val best = scored.maxOf { it.second }
            val floor = best * 2 / 3
            scored.filter { it.second >= max(1, floor) }
                .sortedByDescending { it.second }
                .map { it.first }
                .take(6)
        }
        if (matches.isEmpty()) {
            return CapabilityExecutionResult(
                output = AgentToolOutput(kind = AgentToolOutput.Kind.TEXT, text = "没有找到相关的过往对话。"),
            )
        }
        val lines = mutableListOf("找到 ${matches.size} 次相关对话：")
        matches.forEach { entry ->
            lines += "- ${entry.handle} · ${entry.dateLabel} · ${entry.title}"
            entry.preview.takeIf { it.isNotBlank() }?.let { lines += "  $it" }
        }
        lines += "其中确实是用户说的那次，用 read_session 读它；都对不上就别读了，照常回答。"
        return CapabilityExecutionResult(
            output = AgentToolOutput(kind = AgentToolOutput.Kind.TEXT, text = lines.joinToString("\n")),
        )
    }

    private fun read(
        store: SessionStore,
        currentSessionId: String?,
        invocation: CapabilityInvocation,
    ): CapabilityExecutionResult {
        val handle = runCatching {
            RuntimeJSONValue.decode(from = invocation.input)["id"]?.stringValue?.trim()
        }.getOrNull().orEmpty()
        val indexed = buildIndex(store, currentSessionId, sinceDays = null)
        val entry = indexed.firstOrNull { it.handle.equals(handle, ignoreCase = true) }
            ?: return CapabilityExecutionResult(
                output = AgentToolOutput(
                    kind = AgentToolOutput.Kind.TEXT,
                    text = "没有编号为 $handle 的对话。先调 search_sessions 拿编号。",
                ),
                isError = true,
            )
        val session = store.load(entry.id)
            ?: return CapabilityExecutionResult(
                output = AgentToolOutput(
                    kind = AgentToolOutput.Kind.TEXT,
                    text = "编号 $handle 的对话已经读不到了，可能刚被删除。",
                ),
                isError = true,
            )
        return CapabilityExecutionResult(
            output = AgentToolOutput(kind = AgentToolOutput.Kind.TEXT, text = transcript(session, entry)),
        )
    }

    private data class IndexEntry(
        val id: String,
        val handle: String,
        val title: String,
        val dateLabel: String,
        val userText: String,
        val preview: String,
        val createdAt: kotlinx.datetime.Instant,
    )

    private fun buildIndex(
        store: SessionStore,
        currentSessionId: String?,
        sinceDays: Int?,
    ): List<IndexEntry> {
        val summaries = store.listSummaries()
        val sessions = summaries.mapNotNull { summary ->
            store.load(summary.id)?.takeIf { it.id != currentSessionId }
        }.sortedBy { it.createdAt }
        val sinceInstant = sinceDays?.let {
            java.time.Instant.now().minus(it.toLong().coerceIn(1, 365), java.time.temporal.ChronoUnit.DAYS)
        }
        return sessions.mapIndexedNotNull { index, session ->
            if (sinceInstant != null) {
                val created = java.time.Instant.ofEpochMilli(session.createdAt.toEpochMilliseconds())
                if (created.isBefore(sinceInstant)) return@mapIndexedNotNull null
            }
            val userTexts = session.messages
                .filter { it.role == ChatMessage.Role.USER && !it.textIsPlaceholder }
                .joinToString("\n") { it.text }
            IndexEntry(
                id = session.id,
                handle = "S${index + 1}",
                title = session.title,
                dateLabel = formatDate(session.createdAt),
                userText = userTexts.take(800),
                preview = userTexts.lineSequence().firstOrNull()?.take(80).orEmpty(),
                createdAt = session.createdAt,
            )
        }
    }

    private fun relevance(query: String, haystack: String): Int {
        val terms = terms(query)
        if (terms.isEmpty()) return 0
        val lower = haystack.lowercase()
        return terms.count { lower.contains(it) }
    }

    private fun terms(query: String): List<String> {
        val trimmed = query.lowercase().trim()
        if (trimmed.isEmpty()) return emptyList()
        val ascii = Regex("[a-z0-9]+").findAll(trimmed).map { it.value }.filter { it.length >= 2 }
        val cjk = Regex("[\\u4e00-\\u9fff]{2,}").findAll(trimmed).flatMap { match ->
            val text = match.value
            if (text.length == 2) sequenceOf(text)
            else (0 until text.length - 1).asSequence().map { text.substring(it, it + 2) }
        }
        return (ascii + cjk).distinct().toList()
    }

    private fun transcript(session: ChatSession, entry: IndexEntry): String {
        val lines = mutableListOf("这是 ${entry.dateLabel} 的一次对话（话题：${entry.title}）：", "")
        var used = lines.sumOf { it.length }
        for (message in session.messages) {
            if (message.textIsPlaceholder) continue
            val prefix = if (message.role == ChatMessage.Role.USER) "他：" else "Vana："
            val limit = if (message.role == ChatMessage.Role.USER) MAX_USER else MAX_ASSISTANT
            var body = message.text.take(limit)
            if (message.role == ChatMessage.Role.ASSISTANT && message.toolCalls.isNotEmpty()) {
                val names = message.toolCalls.map { it.name }.distinct().joinToString("、")
                body += "（当时查了：$names）"
            }
            val line = prefix + body
            if (used + line.length + footer.length + 4 > MAX_CHARS) break
            lines += line
            used += line.length
        }
        lines += ""
        lines += footer
        return lines.joinToString("\n")
    }

    private fun formatDate(instant: kotlinx.datetime.Instant): String {
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault())
        return formatter.format(java.time.Instant.ofEpochMilli(instant.toEpochMilliseconds()))
    }

    private fun searchDefinition() = CapabilityDefinition(
        name = SEARCH_TOOL_NAME,
        description = "搜索过往对话。只有用户自己提起过去（上次、之前说过、我们聊过、你还记得）时才调用。" +
            "问眼前健康数据请走健康工具。先 search 再 read_session。",
        inputSchema = RuntimeJSONValue.ObjectValue(
            mapOf(
                "type" to RuntimeJSONValue.StringValue("object"),
                "properties" to RuntimeJSONValue.ObjectValue(
                    mapOf(
                        "query" to RuntimeJSONValue.ObjectValue(
                            mapOf(
                                "type" to RuntimeJSONValue.StringValue("string"),
                                "description" to RuntimeJSONValue.StringValue("检索词，来自用户提到过去时说的话"),
                            ),
                        ),
                        "since_days" to RuntimeJSONValue.ObjectValue(
                            mapOf(
                                "type" to RuntimeJSONValue.StringValue("integer"),
                                "description" to RuntimeJSONValue.StringValue("只看最近多少天，1–365，可选"),
                                "minimum" to RuntimeJSONValue.IntValue(1),
                                "maximum" to RuntimeJSONValue.IntValue(365),
                            ),
                        ),
                    ),
                ),
                "required" to RuntimeJSONValue.ArrayValue(listOf(RuntimeJSONValue.StringValue("query"))),
                "additionalProperties" to RuntimeJSONValue.BoolValue(false),
            ),
        ),
    )

    private fun readDefinition() = CapabilityDefinition(
        name = READ_TOOL_NAME,
        description = "按 search_sessions 给出的短编号读取一次过往对话原文。里面的数值可能过时，要用就重新查健康工具。",
        inputSchema = RuntimeJSONValue.ObjectValue(
            mapOf(
                "type" to RuntimeJSONValue.StringValue("object"),
                "properties" to RuntimeJSONValue.ObjectValue(
                    mapOf(
                        "id" to RuntimeJSONValue.ObjectValue(
                            mapOf(
                                "type" to RuntimeJSONValue.StringValue("string"),
                                "description" to RuntimeJSONValue.StringValue("search_sessions 给出的短编号，形如 S3"),
                            ),
                        ),
                    ),
                ),
                "required" to RuntimeJSONValue.ArrayValue(listOf(RuntimeJSONValue.StringValue("id"))),
                "additionalProperties" to RuntimeJSONValue.BoolValue(false),
            ),
        ),
    )
}
