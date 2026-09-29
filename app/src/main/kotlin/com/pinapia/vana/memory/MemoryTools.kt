package com.pinapia.vana.memory

import com.pinapia.vana.agentruntime.AgentToolOutput
import com.pinapia.vana.agentruntime.CapabilityDefinition
import com.pinapia.vana.agentruntime.CapabilityExecutionResult
import com.pinapia.vana.agentruntime.CapabilityInvocation
import com.pinapia.vana.agentruntime.CapabilityRegistry
import com.pinapia.vana.agentruntime.RuntimeJSONValue

/**
 * 对话里当场动记忆的三个工具:记([REMEMBER])、忘([FORGET])、改([REVISE])。
 *
 * 忘和改是**用户明说**才调的:「忘掉我说过的那个」「不对,其实是周四」。它们按记忆块里的短编号
 * (M1、M2…)指到某一条,编号和模型此刻读到的那一块是同一套——所以工具拿的是**这一轮绑定的快照**,
 * 不是去盘上重新数(盘上的顺序可能已经因为别处的写入变了)。指到之后按 id 动,id 不会指错。
 *
 * 与后台抽取器不同,这里对**用户自己写的条目也能忘、能改**:是用户在对话里明确要求的。
 * 抽取器不许动它们(见 `MemoryStore.apply`),那是另一回事。
 */
object MemoryTools {
    const val REMEMBER = "remember"
    const val FORGET = "forget_memory"
    const val REVISE = "revise_memory"

    private const val BASE_DESCRIPTION = "当用户明确要求记住某件关于自己的长期情况、偏好或约定时调用。"

    /** 别的插件自己存着的话题(标签)。它们有专门的存放处,记进这里就是同一件事两份。 */
    private fun elsewhere(exclusions: List<String>): String? =
        exclusions.takeIf { it.isNotEmpty() }
            ?.let { "${it.joinToString("、")}已经有专门的存放处，不要用 remember 再记一份。" }

    fun description(exclusions: List<String> = emptyList()): String =
        BASE_DESCRIPTION + (elsewhere(exclusions) ?: "")

    /** system 段里那句「什么时候调这三个」。三个工具同挂同撤,所以写在一起。 */
    fun guide(exclusions: List<String> = emptyList()): String =
        "用户明确说「记住…」这类话时，调用 remember；说「忘掉…」「别再提…」时，用 forget_memory；" +
            "纠正一条已经记下的事（「不对，其实是…」）时，用 revise_memory——后两个都按「关于这位用户」里那条的编号（M1、M2…）。" +
            "一次性的要求（「这次简短点」）不是偏好，不要记。" + (elsewhere(exclusions) ?: "")

    fun registry(
        store: MemoryStore,
        snapshot: MemorySnapshot = MemorySnapshot.empty,
        exclusions: List<String> = emptyList(),
    ): CapabilityRegistry {
        val definitions = listOf(rememberDefinition(exclusions), forgetDefinition(), reviseDefinition())
        return CapabilityRegistry(definitions = definitions) { invocation ->
            when (invocation.name) {
                REMEMBER -> remember(store, invocation)
                FORGET -> forget(store, snapshot, invocation)
                REVISE -> revise(store, snapshot, invocation)
                else -> failure("不支持名为 ${invocation.name} 的工具。")
            }
        }
    }

    // ---------------- 定义 ----------------

    private fun obj(vararg entries: Pair<String, RuntimeJSONValue>) =
        RuntimeJSONValue.ObjectValue(mapOf(*entries))

    private fun str(value: String) = RuntimeJSONValue.StringValue(value)

    private fun stringProperty(description: String) =
        obj("type" to str("string"), "description" to str(description))

    private fun schema(properties: Map<String, RuntimeJSONValue>, required: List<String>) = obj(
        "type" to str("object"),
        "properties" to RuntimeJSONValue.ObjectValue(properties),
        "required" to RuntimeJSONValue.ArrayValue(required.map { str(it) }),
        "additionalProperties" to RuntimeJSONValue.BoolValue(false),
    )

    private fun rememberDefinition(exclusions: List<String>) = CapabilityDefinition(
        name = REMEMBER,
        description = description(exclusions),
        inputSchema = schema(
            properties = mapOf(
                "text" to stringProperty("用中文第三人称写，一句话，不超过 40 个字"),
                "kind" to obj(
                    "type" to str("string"),
                    "description" to str(
                        "profile 长期情况；preference 表达偏好；episode 近况（最近发生、还没完的事，配合 days，到点自己淡出）；" +
                            "followUp 待跟进（说好过一阵子再看，配合 days）。",
                    ),
                    "enum" to RuntimeJSONValue.ArrayValue(
                        listOf("profile", "preference", "episode", "interpretation", "followUp").map { str(it) },
                    ),
                ),
                "days" to obj(
                    "type" to str("integer"),
                    "description" to str(
                        "仅 followUp 和 episode：几天后回头看或淡出。followUp 1–180，episode 1–60，默认都是 14",
                    ),
                    "minimum" to RuntimeJSONValue.IntValue(1),
                    "maximum" to RuntimeJSONValue.IntValue(MemoryItem.MAX_FOLLOW_UP_DAYS),
                ),
            ),
            required = listOf("text", "kind"),
        ),
        strictPreferred = false,
    )

    private fun forgetDefinition() = CapabilityDefinition(
        name = FORGET,
        description = "用户明确要求忘掉、别再提某件已经记下的事时调用。按「关于这位用户」里那一条的编号（形如 M3）忘掉它。" +
            "只在用户明说时才调用，不要自己判断哪条该忘。",
        inputSchema = schema(
            properties = mapOf("handle" to stringProperty("记忆块里那一条的编号，形如 M3")),
            required = listOf("handle"),
        ),
        strictPreferred = false,
    )

    private fun reviseDefinition() = CapabilityDefinition(
        name = REVISE,
        description = "用户纠正一条已经记下的事时调用（「不对，其实是…」）。把编号指到的那一条改成新的一句话，种类不变。",
        inputSchema = schema(
            properties = mapOf(
                "handle" to stringProperty("记忆块里那一条的编号，形如 M3"),
                "text" to stringProperty("改后的那一句，用中文第三人称，不超过 40 个字"),
            ),
            required = listOf("handle", "text"),
        ),
        strictPreferred = false,
    )

    // ---------------- 执行 ----------------

    private fun failure(message: String) = CapabilityExecutionResult(
        output = AgentToolOutput(kind = AgentToolOutput.Kind.TEXT, text = message),
        isError = true,
    )

    private fun success(message: String) = CapabilityExecutionResult(
        output = AgentToolOutput(kind = AgentToolOutput.Kind.TEXT, text = message),
    )

    private fun input(invocation: CapabilityInvocation): RuntimeJSONValue? =
        runCatching { RuntimeJSONValue.decode(from = invocation.input) }.getOrNull()

    private fun kindOf(raw: String): MemoryItem.Kind? = when (raw) {
        "profile" -> MemoryItem.Kind.PROFILE
        "preference" -> MemoryItem.Kind.PREFERENCE
        "episode" -> MemoryItem.Kind.EPISODE
        "interpretation" -> MemoryItem.Kind.INTERPRETATION
        "followUp" -> MemoryItem.Kind.FOLLOW_UP
        else -> null
    }

    private fun remember(store: MemoryStore, invocation: CapabilityInvocation): CapabilityExecutionResult {
        val input = input(invocation)
        val text = input?.get("text")?.stringValue?.trim().orEmpty()
        val kind = kindOf(input?.get("kind")?.stringValue.orEmpty())
        if (text.isEmpty() || kind == null) return failure("remember 需要 text 和合法的 kind。")
        if (text.length > MemoryItem.MAX_TEXT_CHARS) {
            return failure("这一句太长了（${text.length} 字）。压成一句话、不超过 40 个字再记。")
        }
        val item = store.remember(text = text, kind = kind, days = input?.get("days")?.intValue)
            ?: return failure("记忆已满，这条没有记下来。让用户到设置里清理一下。")
        val suffix = when {
            MemoryItem.expires(kind) -> {
                val days = input?.get("days")?.intValue ?: MemoryItem.DEFAULT_EXPIRY_DAYS
                val limit = if (kind == MemoryItem.Kind.EPISODE) MemoryItem.MAX_EPISODE_DAYS else MemoryItem.MAX_FOLLOW_UP_DAYS
                val label = if (kind == MemoryItem.Kind.EPISODE) "后淡出" else "后回头看"
                "（${days.coerceIn(1, limit)} 天$label）"
            }
            else -> ""
        }
        return success("已记住：${item.text}$suffix")
    }

    private fun forget(
        store: MemoryStore,
        snapshot: MemorySnapshot,
        invocation: CapabilityInvocation,
    ): CapabilityExecutionResult {
        val handle = input(invocation)?.get("handle")?.stringValue?.trim().orEmpty()
        val id = snapshot.resolve(handle)
            ?: return failure("没有编号为 $handle 的记忆。编号见系统提示里「关于这位用户」那一块。")
        val item = store.load().firstOrNull { it.id == id }
            ?: return failure("编号 $handle 的那条记忆已经不在了。")
        store.delete(id)
        return success("已忘掉：${item.text}")
    }

    private fun revise(
        store: MemoryStore,
        snapshot: MemorySnapshot,
        invocation: CapabilityInvocation,
    ): CapabilityExecutionResult {
        val input = input(invocation)
        val handle = input?.get("handle")?.stringValue?.trim().orEmpty()
        val text = input?.get("text")?.stringValue?.trim().orEmpty()
        if (text.isEmpty()) return failure("revise_memory 需要改后的 text。")
        if (text.length > MemoryItem.MAX_TEXT_CHARS) {
            return failure("这一句太长了（${text.length} 字）。压成一句话、不超过 40 个字再改。")
        }
        val id = snapshot.resolve(handle)
            ?: return failure("没有编号为 $handle 的记忆。编号见系统提示里「关于这位用户」那一块。")
        val item = store.load().firstOrNull { it.id == id }
            ?: return failure("编号 $handle 的那条记忆已经不在了。")
        // 后台抽出来的条目被用户当面纠正过,就成了他说的话:从此受保护,不再被抽取器改回去。
        val origin = if (item.origin == MemoryItem.Origin.EXTRACTED) MemoryItem.Origin.ASKED else item.origin
        store.update(item.copy(text = text, origin = origin))
        return success("已改成：$text")
    }
}
