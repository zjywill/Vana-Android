package com.pinapia.vana.recall

import com.pinapia.vana.agentruntime.AgentToolOutput
import com.pinapia.vana.agentruntime.CapabilityDefinition
import com.pinapia.vana.agentruntime.CapabilityExecutionResult
import com.pinapia.vana.agentruntime.CapabilityInvocation
import com.pinapia.vana.agentruntime.CapabilityRegistry
import com.pinapia.vana.agentruntime.RuntimeJSONValue
import com.pinapia.vana.thread.ThreadArchive
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.max

/**
 * 检索这条对话里**已经滑出窗口**的历史。
 *
 * 工具名(`search_sessions` / `read_session`)沿用旧的——历史 transcript 里已经写过对它们的调用,
 * 改名只会让旧调用对不上号;对模型来说它们的意思是「翻对话历史」。
 *
 * 只搜窗口之外的:窗口里的原文模型本来就看得见,再搜出来只是重复。检索的是**用户说过的话**
 * (精度重于广度:命中数要够到最高分的三分之二才留,最多 6 条),读出来的是那一处前后的原文。
 */
object HistoryRecallTools {
    const val SEARCH_TOOL_NAME = "search_sessions"
    const val READ_TOOL_NAME = "read_session"

    private const val MAX_CHARS = 2500
    private const val MAX_USER = 200
    private const val MAX_ASSISTANT = 320

    val footer = "（以上是当时说过的话，日期见开头。里面的具体数值都可能已经过时，要用就现在重新查一遍工具，一律以本次返回的为准。）"

    fun registry(archive: ThreadArchive, hiddenBeforePos: () -> Double?): CapabilityRegistry =
        CapabilityRegistry(definitions = listOf(searchDefinition(), readDefinition())) { invocation ->
            when (invocation.name) {
                SEARCH_TOOL_NAME -> search(archive, hiddenBeforePos, invocation)
                READ_TOOL_NAME -> read(archive, hiddenBeforePos, invocation)
                else -> text("不支持名为 ${invocation.name} 的工具。", isError = true)
            }
        }

    /** 一条消息的短编号:id 的稳定散列。不按「第几条」编,删掉一条编号就全错位了。 */
    fun handleOf(id: String): String = "H" + Integer.toUnsignedString(id.hashCode(), 36).uppercase()

    private fun text(message: String, isError: Boolean = false) = CapabilityExecutionResult(
        output = AgentToolOutput(kind = AgentToolOutput.Kind.TEXT, text = message),
        isError = isError,
    )

    private suspend fun search(
        archive: ThreadArchive,
        hiddenBeforePos: () -> Double?,
        invocation: CapabilityInvocation,
    ): CapabilityExecutionResult {
        val input = runCatching { RuntimeJSONValue.decode(from = invocation.input) }.getOrNull()
        val query = input?.get("query")?.stringValue?.trim().orEmpty()
        val sinceDays = input?.get("since_days")?.intValue
        archive.await()
        val before = hiddenBeforePos() ?: return text("还没有可以回顾的过往对话。")
        val since = sinceDays?.let {
            System.currentTimeMillis() - it.toLong().coerceIn(1, 365) * 86_400_000L
        }
        val candidates = archive.rowsBefore(before).filter { row ->
            row.isUser && (since == null || row.createdAt.toEpochMilliseconds() >= since)
        }
        if (candidates.isEmpty()) return text("还没有可以回顾的过往对话。")

        val matches = if (query.isEmpty()) {
            candidates.takeLast(6).reversed()
        } else {
            val scored = candidates.mapNotNull { row ->
                val score = relevance(query, row.text)
                if (score <= 0) null else row to score
            }
            if (scored.isEmpty()) return text("没有找到相关的过往对话。")
            val best = scored.maxOf { it.second }
            val floor = best * 2 / 3
            scored.filter { it.second >= max(1, floor) }
                .sortedWith(compareByDescending<Pair<ThreadArchive.Row, Int>> { it.second }.thenByDescending { it.first.pos })
                .map { it.first }
                .take(6)
        }
        val lines = mutableListOf("找到 ${matches.size} 处相关的过往对话：")
        matches.forEach { row ->
            lines += "- ${handleOf(row.id)} · ${formatDate(row.createdAt)} · ${row.text.lineSequence().first().take(80)}"
        }
        lines += "其中确实是用户说的那次，用 read_session 读它；都对不上就别读了，照常回答。"
        return text(lines.joinToString("\n"))
    }

    private suspend fun read(
        archive: ThreadArchive,
        hiddenBeforePos: () -> Double?,
        invocation: CapabilityInvocation,
    ): CapabilityExecutionResult {
        val handle = runCatching {
            RuntimeJSONValue.decode(from = invocation.input)["id"]?.stringValue?.trim()
        }.getOrNull().orEmpty()
        archive.await()
        val before = hiddenBeforePos()
            ?: return text("没有编号为 $handle 的对话。先调 search_sessions 拿编号。", isError = true)
        val target = archive.rowsBefore(before).firstOrNull { handleOf(it.id).equals(handle, ignoreCase = true) }
            ?: return text("没有编号为 $handle 的对话。先调 search_sessions 拿编号。", isError = true)
        val rows = archive.around(target.id)
            ?: return text("编号 $handle 的对话已经读不到了，可能刚被删除。", isError = true)
        return text(transcript(rows))
    }

    private fun transcript(rows: List<ThreadArchive.Row>): String {
        val lines = mutableListOf("这是 ${formatDate(rows.first().createdAt)} 的一段对话：", "")
        var used = lines.sumOf { it.length }
        for (row in rows) {
            val prefix = if (row.isUser) "他：" else "Vana："
            val limit = if (row.isUser) MAX_USER else MAX_ASSISTANT
            var body = row.text.take(limit)
            if (!row.isUser && row.toolNames.isNotEmpty()) {
                body += "（当时查了：${row.toolNames.joinToString("、")}）"
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
            val chars = match.value
            if (chars.length == 2) sequenceOf(chars)
            else (0 until chars.length - 1).asSequence().map { chars.substring(it, it + 2) }
        }
        return (ascii + cjk).distinct().toList()
    }

    private fun formatDate(instant: kotlinx.datetime.Instant): String {
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault())
        return formatter.format(java.time.Instant.ofEpochMilli(instant.toEpochMilliseconds()))
    }

    private fun searchDefinition() = CapabilityDefinition(
        name = SEARCH_TOOL_NAME,
        description = "搜索这条对话里更早的、已经不在上面的部分。只有用户自己提起过去（上次、之前说过、我们聊过、你还记得）时才调用。" +
            "先 search 再 read_session。",
        inputSchema = obj(
            "type" to str("object"),
            "properties" to obj(
                "query" to obj(
                    "type" to str("string"),
                    "description" to str("检索词，来自用户提到过去时说的话"),
                ),
                "since_days" to obj(
                    "type" to str("integer"),
                    "description" to str("只看最近多少天，1–365，可选"),
                    "minimum" to RuntimeJSONValue.IntValue(1),
                    "maximum" to RuntimeJSONValue.IntValue(365),
                ),
            ),
            "required" to RuntimeJSONValue.ArrayValue(listOf(str("query"))),
            "additionalProperties" to RuntimeJSONValue.BoolValue(false),
        ),
    )

    private fun readDefinition() = CapabilityDefinition(
        name = READ_TOOL_NAME,
        description = "按 search_sessions 给出的短编号读取那一处前后的对话原文。里面的数值可能过时，要用就重新查。",
        inputSchema = obj(
            "type" to str("object"),
            "properties" to obj(
                "id" to obj(
                    "type" to str("string"),
                    "description" to str("search_sessions 给出的短编号，形如 H3F2A"),
                ),
            ),
            "required" to RuntimeJSONValue.ArrayValue(listOf(str("id"))),
            "additionalProperties" to RuntimeJSONValue.BoolValue(false),
        ),
    )

    private fun obj(vararg entries: Pair<String, RuntimeJSONValue>) = RuntimeJSONValue.ObjectValue(mapOf(*entries))
    private fun str(value: String) = RuntimeJSONValue.StringValue(value)
}
