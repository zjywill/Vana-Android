package com.pinapia.vana.memory

import com.pinapia.vana.agent.OpenAICompatibleModelClient
import com.pinapia.vana.agentruntime.AgentModelProfile
import com.pinapia.vana.agentruntime.AgentModelRequest
import com.pinapia.vana.agentruntime.MemoryPolicy
import com.pinapia.vana.agentruntime.AgentModelStreamEvent
import com.pinapia.vana.agentruntime.AgentTranscript
import com.pinapia.vana.session.ChatMessage
import com.pinapia.vana.settings.CloudCatalog
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
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
    /** 水位线之后至少攒到这么多条用户消息才值得叫模型抽一次。 */
    const val MINIMUM_USER_MESSAGES = 2

    /** 一次喂给抽取器的转写上限(字符)。超了就分块,旧的先抽。 */
    const val MAX_TRANSCRIPT_CHARACTERS = 6_000
    const val MAX_MESSAGE_CHARACTERS = 400

    fun userMessageCount(messages: List<ChatMessage>): Int =
        messages.count { it.role == ChatMessage.Role.USER && !it.textIsPlaceholder && it.text.isNotBlank() }

    /** 一条消息在转写里占几个字符(含角色前缀和换行)。 */
    internal fun cost(message: ChatMessage): Int? {
        if (message.textIsPlaceholder) return null
        val text = message.text.trim()
        if (text.isEmpty()) return null
        return minOf(text.length, MAX_MESSAGE_CHARACTERS + 1) + 4
    }

    /**
     * 从最旧的开始,取到转写字符数用完为止。**从头取**,不是取末尾:
     * 以前是整段重发再丢掉最旧的,既会把抽过的又看一遍,又会漏掉更早还没抽的。
     * 返回的是要抽的这一块;剩下的下一次再抽。至少取一条,免得一条超长的消息永远卡在水位线上。
     */
    fun chunk(messages: List<ChatMessage>): List<ChatMessage> {
        val taken = ArrayList<ChatMessage>()
        var used = 0
        for (message in messages) {
            val cost = cost(message)
            if (cost != null) {
                if (taken.isNotEmpty() && used + cost > MAX_TRANSCRIPT_CHARACTERS) break
                used += cost
            }
            taken += message
        }
        return taken
    }
}

class MemoryExtractor(
    private val providerId: String,
    private val model: String,
    private val apiKey: String,
    private val snapshot: MemorySnapshot,
    /** 各插件声明的「有专门存放处」和领域补充,来自 `PluginHost.memoryPolicy`。 */
    private val policy: MemoryPolicy = MemoryPolicy(),
) {
    suspend fun operations(from: List<ChatMessage>): List<MemoryOperation> {
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
                    AgentTranscript.Message.system(instructions(policy)),
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

        /**
         * 通用规则 + 各插件贡献的排除项与领域补充。核心不认识任何一个领域:
         * 「用药走用药表」「测量数字走测量卡片」这类话是健康插件在它真的存着那些东西时才带来的。
         */
        fun instructions(policy: MemoryPolicy = MemoryPolicy()): String {
            val elsewhere = policy.exclusions.takeIf { it.isNotEmpty() }?.let {
                "\n- ${it.joinToString("、")}：这些有专门的地方存（用户能在那儿直接编辑），" +
                    "记进这里就是同一件事两份，改了一份另一份还是旧的。"
            }.orEmpty()
            val extra = policy.guidance.takeIf { it.isNotEmpty() }?.let {
                "\n\n另外要注意：\n" + it.joinToString("\n") { line -> "- $line" }
            }.orEmpty()
            return """
                你在为一个日常助手 app 维护「关于这位用户」的长期记忆。这份记忆会放进之后每一次对话的系统提示里，
                所以它必须是长期成立的，而且要少而准。

                只记这几类，查得到的一律不记：
                - profile 长期情况：作息、工作或学习安排、身体或行动上的限制、家庭和重要的人、正在进行的目标和计划。
                - preference 表达偏好：他希望助手怎么说话、怎么做事，他自己看重什么。一次性的要求不是偏好。
                - episode 近况：最近发生、还没了结、接下来几天很可能还会被提起的事（「下周三面试」「最近在装修」），必须给出 days（几天后淡出，一般 7–30）。它过了这段时间会自己消失，所以不要把长期成立的事记成近况，也不要把近况记成长期情况。
                - followUp 待跟进：说好过一阵子再看的事，必须给出 days（几天后失效）。

                绝对不要记：
                - 任何具体的数值和某一天的数据（价格、步数、体重、余额、比分……）。这些每次都该重新查，记进这里第二天就过期。$elsewhere
                - 只在这次对话里成立的话题，或者一次性的提问。$extra

                已有记忆每条前面有一个编号（M1、M2…）。你输出的是对这份记忆的**修改**，不是重写：
                - 已经记过的事不要再 add。有更准确的说法就 update 那一条。
                - 事实变了或者已经不成立，delete。
                - 这次对话没有值得记的，就输出空数组。宁可什么都不记，也不要记一堆用不上的。

                只输出 JSON，不要任何解释：
                {"operations":[
                  {"op":"add","kind":"profile","text":"…"},
                  {"op":"add","kind":"episode","text":"…","days":14},
                  {"op":"add","kind":"followUp","text":"…","days":14},
                  {"op":"update","id":"M2","text":"…"},
                  {"op":"delete","id":"M5"}
                ]}

                每条 text 用中文第三人称写，一句话，不超过 40 个字。
            """.trimIndent()
        }

        /**
         * 转写只有**用户和助手说过的话**,工具输出一条都不给——抽取器无从记起会过期的数字。
         * 每条最多 400 字。块的大小已经由 [MemoryHarvest.chunk] 定好,这里不再丢消息。
         */
        fun transcript(of: List<ChatMessage>): String =
            of.mapNotNull { message ->
                if (message.textIsPlaceholder) return@mapNotNull null
                val text = message.text.trim()
                if (text.isEmpty()) return@mapNotNull null
                val clipped = if (text.length <= MemoryHarvest.MAX_MESSAGE_CHARACTERS) text else text.take(MemoryHarvest.MAX_MESSAGE_CHARACTERS) + "…"
                val role = if (message.role == ChatMessage.Role.USER) "用户" else "助手"
                "$role：$clipped"
            }.joinToString("\n")

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
                        if (body.isEmpty() || body.length > MemoryItem.MAX_TEXT_CHARS) return@mapNotNull null
                        MemoryOperation.Add(
                            kind = kind,
                            text = body,
                            // 带过期的种类缺 days 就按默认;上限由 MemoryItem.dueFor 按种类夹。
                            expiresInDays = if (MemoryItem.expires(kind)) raw.days ?: MemoryItem.DEFAULT_EXPIRY_DAYS else null,
                        )
                    }
                    "update" -> {
                        val id = raw.id?.let { snapshot.resolve(handle = it) } ?: return@mapNotNull null
                        val body = raw.text?.trim().orEmpty()
                        if (body.isEmpty() || body.length > MemoryItem.MAX_TEXT_CHARS) return@mapNotNull null
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
                MemoryItem.Kind.EPISODE -> "episode"
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
            "${MemorySnapshot.handle(index)}. [${item.kind.promptLabel}] ${item.text}"
        }.joinToString("\n")
    }

fun MemorySnapshot.resolve(handle: String): String? {
    val match = Regex("""^M(\d+)$""", RegexOption.IGNORE_CASE).matchEntire(handle.trim()) ?: return null
    val index = match.groupValues[1].toIntOrNull()?.minus(1) ?: return null
    return items.getOrNull(index)?.id
}

/**
 * 读改写整个在 store 的锁里做:抽取、`remember`、手动编辑各自都是「读—改—写」,
 * 不在同一把锁下并发就会互相顶掉对方的更新。
 */
fun MemoryStore.apply(
    operations: List<MemoryOperation>,
    now: Instant = Clock.System.now(),
): List<MemoryItem> = synchronized(this) { applyLocked(operations, now) }

private fun MemoryStore.applyLocked(
    operations: List<MemoryOperation>,
    now: Instant,
): List<MemoryItem> {
    if (operations.isEmpty()) return load(now)
    val items = load(now).toMutableList()
    for (op in operations) {
        when (op) {
            is MemoryOperation.Add -> {
                // 去掉空白和标点再比:「不吃香菜。」和「不吃 香菜」是同一句,以前会各记一条。
                val key = MemoryItem.normalized(op.text)
                val duplicate = items.any { it.kind == op.kind && MemoryItem.normalized(it.text) == key }
                if (duplicate) continue
                val dueAt = MemoryItem.dueFor(op.kind, op.expiresInDays, now)
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
