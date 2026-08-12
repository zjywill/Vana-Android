package com.pinapia.vana.memory

import com.pinapia.vana.agent.OpenAICompatibleModelClient
import com.pinapia.vana.agentruntime.AgentModelProfile
import com.pinapia.vana.agentruntime.AgentModelRequest
import com.pinapia.vana.agentruntime.AgentModelStreamEvent
import com.pinapia.vana.agentruntime.AgentTranscript
import com.pinapia.vana.session.ChatMessage
import com.pinapia.vana.session.ChatSession
import com.pinapia.vana.settings.CloudCatalog
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

sealed class MemoryOperation {
    data class Add(
        val kind: MemoryItem.Kind,
        val text: String,
        val expiresInDays: Int? = null,
    ) : MemoryOperation()

    data class Update(val id: String, val text: String) : MemoryOperation()
    data class Delete(val id: String) : MemoryOperation()
}

object MemoryHarvest {
    const val MINIMUM_USER_MESSAGES = 2

    fun shouldHarvest(session: ChatSession, memoryEnabled: Boolean): Boolean {
        if (session.isPrivate || !memoryEnabled) return false
        if (session.messages.size <= session.memoryHarvestedMessageCount) return false
        return session.messages.count { it.role == ChatMessage.Role.USER } >= MINIMUM_USER_MESSAGES
    }
}

class MemoryExtractor(
    private val providerId: String,
    private val model: String,
    private val apiKey: String,
    private val snapshot: MemorySnapshot,
) {
    suspend fun operations(from: ChatSession): List<MemoryOperation> {
        val transcript = transcript(of = from)
        if (transcript.isEmpty()) return emptyList()
        val provider = CloudCatalog.provider(providerId) ?: return emptyList()
        val modelInfo = CloudCatalog.model(model, providerId)
        val client = OpenAICompatibleModelClient(
            profile = AgentModelProfile(
                providerId = providerId,
                modelId = model,
                contextWindow = modelInfo?.contextWindow,
                maxOutputTokens = 800,
            ),
            apiKey = apiKey,
            baseUrl = provider.apiBaseUrl,
            wireProtocol = provider.requireWireProtocol(),
            thinkingEnabled = false,
            supportsReasoning = modelInfo?.supportsReasoning == true,
        )
        val request = AgentModelRequest(
            profile = client.profile,
            prompt = AgentTranscript(
                messages = listOf(
                    AgentTranscript.Message.system(INSTRUCTIONS),
                    AgentTranscript.Message.user(
                        "已有记忆：\n${snapshot.handleListing}\n\n这次对话：\n$transcript",
                    ),
                ),
            ),
            capabilities = emptyList(),
        )
        var text = ""
        client.stream(request).collect { event ->
            if (event is AgentModelStreamEvent.TextDelta) text += event.text
        }
        return parse(text, snapshot)
    }

    companion object {
        private const val MAX_TRANSCRIPT_CHARACTERS = 6_000
        private const val MAX_MESSAGE_CHARACTERS = 400
        private const val MAX_ITEM_CHARACTERS = 120

        val INSTRUCTIONS = """
            你在为一个健康分析 app 维护「关于这位用户」的长期记忆。这份记忆会放进之后每一次对话的系统提示里，
            所以它必须是长期成立的，而且要少而准。

            只记这四类，查得到的一律不记：
            - profile 长期情况：作息、工作安排、伤病或身体限制、正在进行的目标和训练计划。
            - preference 表达偏好：他希望助手怎么说话，他自己看重哪个指标。
            - interpretation 已有解释：对他而言某个指标的正常范围，或者某段异常已经查明的原因。
            - followUp 待跟进：说好过一阵子再看的事，必须给出 days（几天后失效）。

            绝对不要记：
            - 任何具体的健康数值和某一天的数据（步数、睡眠时长、心率、体重……）。
              设备数据每次都会重新查；他自己口述的测量有专门的测量卡片（log_measurement），
              记进这里第二天就过期还和卡片打架。
            - 他在吃什么药或补剂、对什么过敏、试过什么没用。这些有专门的地方存（用户能在那儿直接编辑），
              记进这里就是同一件事两份，改了一份另一份还是旧的。
            - 只在这次对话里成立的话题，或者一次性的提问。
            - 诊断结论。可以记「他说自己有房颤」，不能记「他有房颤，需要重点关注」。

            已有记忆每条前面有一个编号（M1、M2…）。你输出的是对这份记忆的**修改**，不是重写：
            - 已经记过的事不要再 add。有更准确的说法就 update 那一条。
            - 事实变了或者已经不成立，delete。
            - 这次对话没有值得记的，就输出空数组。宁可什么都不记，也不要记一堆用不上的。

            只输出 JSON，不要任何解释：
            {"operations":[
              {"op":"add","kind":"profile","text":"…"},
              {"op":"add","kind":"followUp","text":"…","days":14},
              {"op":"update","id":"M2","text":"…"},
              {"op":"delete","id":"M5"}
            ]}

            每条 text 用中文第三人称写，一句话，不超过 40 个字。
        """.trimIndent()

        fun transcript(of: ChatSession): String {
            val lines = mutableListOf<String>()
            for (message in of.messages) {
                if (message.textIsPlaceholder) continue
                val text = message.text.trim()
                if (text.isEmpty()) continue
                val clipped = if (text.length <= MAX_MESSAGE_CHARACTERS) {
                    text
                } else {
                    text.take(MAX_MESSAGE_CHARACTERS) + "…"
                }
                val role = if (message.role == ChatMessage.Role.USER) "用户" else "助手"
                lines += "$role：$clipped"
            }
            var joined = lines.joinToString("\n")
            while (joined.length > MAX_TRANSCRIPT_CHARACTERS && lines.isNotEmpty()) {
                lines.removeAt(0)
                joined = lines.joinToString("\n")
            }
            return joined
        }

        fun parse(text: String, snapshot: MemorySnapshot): List<MemoryOperation> {
            val payload = jsonPayload(inText = text) ?: return emptyList()
            val decoded = runCatching {
                Json { ignoreUnknownKeys = true }.decodeFromString(RawOperations.serializer(), payload)
            }.getOrNull() ?: return emptyList()
            return decoded.operations.mapNotNull { raw ->
                when (raw.op.lowercase()) {
                    "add" -> {
                        val kind = MemoryItem.Kind.entries.firstOrNull {
                            it.name.equals(raw.kind, ignoreCase = true) ||
                                it.serialNameEquals(raw.kind)
                        } ?: return@mapNotNull null
                        val body = raw.text?.trim().orEmpty()
                        if (body.isEmpty() || body.length > MAX_ITEM_CHARACTERS) return@mapNotNull null
                        val days = raw.days?.coerceIn(1, 180)
                        MemoryOperation.Add(
                            kind = kind,
                            text = body,
                            expiresInDays = if (kind == MemoryItem.Kind.FOLLOW_UP) days ?: 14 else null,
                        )
                    }
                    "update" -> {
                        val id = raw.id?.let { snapshot.resolve(handle = it) } ?: return@mapNotNull null
                        val body = raw.text?.trim().orEmpty()
                        if (body.isEmpty() || body.length > MAX_ITEM_CHARACTERS) return@mapNotNull null
                        MemoryOperation.Update(id = id, text = body)
                    }
                    "delete" -> {
                        val id = raw.id?.let { snapshot.resolve(handle = it) } ?: return@mapNotNull null
                        MemoryOperation.Delete(id = id)
                    }
                    else -> null
                }
            }
        }

        private fun jsonPayload(inText: String): String? {
            val start = inText.indexOf('{')
            val end = inText.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            return inText.substring(start, end + 1)
        }

        private fun MemoryItem.Kind.serialNameEquals(raw: String?): Boolean {
            if (raw == null) return false
            val expected = when (this) {
                MemoryItem.Kind.PROFILE -> "profile"
                MemoryItem.Kind.PREFERENCE -> "preference"
                MemoryItem.Kind.INTERPRETATION -> "interpretation"
                MemoryItem.Kind.FOLLOW_UP -> "followUp"
            }
            return expected.equals(raw, ignoreCase = true)
        }
    }

    @Serializable
    private data class RawOperations(val operations: List<RawOperation> = emptyList())

    @Serializable
    private data class RawOperation(
        val op: String,
        val kind: String? = null,
        val text: String? = null,
        val id: String? = null,
        val days: Int? = null,
    )
}

val MemorySnapshot.handleListing: String
    get() {
        if (items.isEmpty()) return "（空）"
        return items.take(MemorySnapshot.MAX_ITEMS).mapIndexed { index, item ->
            "M${index + 1}. [${item.kind.label}] ${item.text}"
        }.joinToString("\n")
    }

fun MemorySnapshot.resolve(handle: String): String? {
    val match = Regex("""^M(\d+)$""", RegexOption.IGNORE_CASE).matchEntire(handle.trim()) ?: return null
    val index = match.groupValues[1].toIntOrNull()?.minus(1) ?: return null
    return items.getOrNull(index)?.id
}

fun MemoryStore.apply(
    operations: List<MemoryOperation>,
    now: Instant = Clock.System.now(),
): List<MemoryItem> {
    if (operations.isEmpty()) return load(now)
    val items = load(now).toMutableList()
    for (op in operations) {
        when (op) {
            is MemoryOperation.Add -> {
                val duplicate = items.any { it.text == op.text && it.kind == op.kind }
                if (duplicate) continue
                val dueAt = if (op.kind == MemoryItem.Kind.FOLLOW_UP) {
                    val days = (op.expiresInDays ?: 14).coerceIn(1, 180)
                    now.plus(days, DateTimeUnit.DAY, TimeZone.currentSystemDefault())
                } else {
                    null
                }
                items += MemoryItem(
                    text = op.text,
                    kind = op.kind,
                    origin = MemoryItem.Origin.EXTRACTED,
                    dueAt = dueAt,
                    createdAt = now,
                    updatedAt = now,
                )
            }
            is MemoryOperation.Update -> {
                val index = items.indexOfFirst { it.id == op.id }
                if (index < 0) continue
                // pinned 条目不接受抽取覆盖
                if (items[index].pinned) continue
                items[index] = items[index].copy(text = op.text, updatedAt = now)
            }
            is MemoryOperation.Delete -> {
                val target = items.firstOrNull { it.id == op.id } ?: continue
                if (target.pinned) continue
                items.removeAll { it.id == op.id }
            }
        }
    }
    save(items, now)
    return load(now)
}
