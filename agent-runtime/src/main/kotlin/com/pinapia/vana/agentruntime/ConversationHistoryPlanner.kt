package com.pinapia.vana.agentruntime

import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 换模型时怎么处理已有历史。 */
@Serializable
enum class HistoryMigrationPolicy {
    /** 不管窗口大小,历史照原样回放。 */
    @SerialName("never") NEVER,
    /**
     * 换到窗口更小(或大小未知)的模型时,老的几轮**优先**被压缩,阈值也压低一档。
     *
     * 注意是"优先",不是"无条件"。之前那版换个模型就把所有工具轨迹铲平,哪怕整段对话
     * 只有两轮、离新窗口还差得远——白丢了追问要用的数据。压不压由预算说了算,换模型只
     * 决定**先压谁**。
     */
    @SerialName("whenWindowShrinks") WHEN_WINDOW_SHRINKS,
    /** 只要 provider 或模型变了,老的几轮就立刻换成压缩形态。 */
    @SerialName("always") ALWAYS,
}

/**
 * 把 app 的会话历史铺成一份可以直接发给模型的 transcript。
 *
 * 三档,从轻到重:
 * 1. 整段摘要([SummarizationPlan] → [CompactionArtifact])——由 loop 在跨过阈值时叫模型生成,
 *    这里只负责认出并回放它;
 * 2. 逐轮压缩——把某一轮的原始工具输出换成它的摘要形态;
 * 3. 丢掉最老的一轮——前两档都不够时的最后手段。
 *
 * 最近 `preservedRecentTurns` 轮在第 1、2 档里受保护,只有真的超预算了才动。
 */
class ConversationHistoryPlanner(
    val systemInstruction: String,
    val profile: AgentModelProfile,
    val reservedOutputTokens: Int? = null,
    val compactor: TranscriptCompactor = TranscriptCompactor.default,
    val migrationPolicy: HistoryMigrationPolicy = HistoryMigrationPolicy.WHEN_WINDOW_SHRINKS,
    val policy: ContextPolicy = ContextPolicy.default,
    /** provider 已经报过一次上下文超限。水位线降到 `thresholdAfterOverflow`,压得更早更狠。 */
    val isRecoveringFromOverflow: Boolean = false,
    /**
     * 只估消息、不带工具面的估算器。
     *
     * 逐条计价时用它:工具 schema 是每次请求算一遍的常量,让它跟着每一条重新序列化一遍
     * 是纯浪费——实测占掉规划耗时的三成。给 null 就退回 [estimateTokens] 再自己扣常量。
     */
    val estimateMessageTokens: ((AgentTranscript) -> Int)? = null,
    val estimateTokens: (AgentTranscript) -> Int,
) {
    data class PreparedHistory(
        val prompt: AgentTranscript,
        val estimatedPromptTokens: Int,
        val compactedAssistantMessages: Int,
        val droppedConversationTurns: Int,
        val summarizedSpans: Int,
        val migrationNotes: List<String>,
        /** 该压的都压了、该丢的都丢了,还是超预算。 */
        val exceedsBudget: Boolean,
        val providerId: String,
        val requestedModelId: String,
        val contextWindow: Int?,
        val reservedOutputTokens: Int?,
    ) {
        fun snapshot(
            servedModelId: String?,
            actualPromptTokens: Int?,
        ): TurnContextSnapshotDTO = TurnContextSnapshotDTO(
            providerId = providerId,
            requestedModelId = requestedModelId,
            servedModelId = servedModelId,
            contextWindow = contextWindow,
            reservedOutputTokens = reservedOutputTokens,
            estimatedPromptTokens = estimatedPromptTokens,
            actualPromptTokens = actualPromptTokens,
            compactedAssistantMessages = compactedAssistantMessages,
            droppedConversationTurns = droppedConversationTurns,
            summarizedSpans = summarizedSpans,
            migrationNotes = migrationNotes,
        )
    }

    /** 发得出去的那份预算(窗口减掉留给输出的部分)。 */
    val budget: Int?
        get() = profile.contextWindow?.let { maxOf(0, it - (reservedOutputTokens ?: 0)) }

    /** 开始压缩的水位。没超之前就动手,别等撞墙。 */
    fun softBudget(modelSwitched: Boolean): Int? {
        val budget = this.budget ?: return null
        var ratio = if (modelSwitched) policy.thresholdAfterModelSwitch else policy.compactionThreshold
        if (isRecoveringFromOverflow) {
            ratio = minOf(ratio, policy.thresholdAfterOverflow)
        }
        return kotlin.math.round(budget.toDouble() * minOf(maxOf(ratio, 0.1), 1.0)).toInt()
    }

    /**
     * 这一轮要不要叫模型写一份整段摘要;要的话总结哪一段。
     *
     * 只有跨过水位线才返回非 null——总结是一次真实的模型调用,不该每轮都花。[force] 是
     * 溢出恢复用的:provider 已经拒收了,水位线怎么算都不重要了。
     */
    fun summarizationPlan(
        history: List<AgentChatMessageDTO>,
        runtimeTranscript: AgentTranscript = AgentTranscript(),
        force: Boolean = false,
    ): SummarizationPlan? {
        val items = historyItems(from = history)
        val soft = softBudget(modelSwitched = items.any { it.isMigrationCandidate }) ?: return null
        if (!force) {
            if (estimateTokens(render(items = items, runtimeTranscript = runtimeTranscript)) <= soft) {
                return null
            }
        }

        val absorbed = absorbedMessageIDs(inHistory = history)
        val visible = history.filter { it.uuid !in absorbed }
        val cut = firstUnprotectedBoundary(inMessages = visible)
        val span = visible.subList(0, cut).filter {
            it.hasReplayableContent || it.role == AgentChatMessageDTO.Role.USER
        }
        val owner = span.lastOrNull() ?: return null

        // 已经被吸收进来的那些也要记在账上,否则下次压缩会以为它们还原样躺着。
        val sourceIDs = mutableListOf<UUID>()
        sourceIDs += span.map { it.uuid }
        for (message in span) {
            val existing = message.storedTurn.compaction
            if (existing != null && existing.sourceUUIDs().size > 1) {
                sourceIDs += existing.sourceUUIDs()
            }
        }

        // 上一版摘要单独拎出来,不混进要压的原文里——它是"已知",新对话才是"待并入"。
        var previousSummary: String? = null
        val spanMessages = span.flatMap { message ->
            when (message.role) {
                AgentChatMessageDTO.Role.USER ->
                    if (message.textIsPlaceholder) emptyList()
                    else listOf(AgentTranscript.Message.user(message.text))
                AgentChatMessageDTO.Role.ASSISTANT -> {
                    val artifact = message.storedTurn.compaction
                    if (artifact != null && artifact.sourceUUIDs().size > 1) {
                        previousSummary = artifact.replaySummary.text
                        emptyList()
                    } else if (message.exactReplayMessages.isEmpty()) {
                        message.reconstructedReplayMessages
                    } else {
                        message.exactReplayMessages
                    }
                }
            }
        }
        val spanTranscript = AgentTranscript(messages = spanMessages)
        if (spanTranscript.messages.isEmpty()) return null
        // 「太短不值得单独花一次调用」的前提是这次调用可省。溢出恢复时 provider 已经拒收了,
        // 省不掉;接着上一版摘要往下并也一样,两三条新消息就值得。
        if (!force && previousSummary == null && span.size < policy.minimumSpanMessages) {
            return null
        }

        return SummarizationPlan(
            ownerMessageID = owner.uuid,
            sourceMessageIDs = sourceIDs.toSet().sortedBy { it.toString() },
            spanText = spanTranscript.plainTextRendering(),
            messageCount = span.size,
            previousSummary = previousSummary,
        )
    }

    fun prepare(
        history: List<AgentChatMessageDTO>,
        runtimeTranscript: AgentTranscript = AgentTranscript(),
    ): PreparedHistory {
        var items = historyItems(from = history).toMutableList()
        val summarizedSpans = items.count { it is HistoryItem.Summary }
        val modelSwitched = items.any { it.isMigrationCandidate }
        var compacted = 0
        var dropped = 0
        var migrated = false

        var prompt = render(items = items, runtimeTranscript = runtimeTranscript)
        var estimate = estimateTokens(prompt)
        val soft = softBudget(modelSwitched = modelSwitched)

        // 绝大多数会话到这儿就结束了:估一次,发出去。下面那套按条记账是压缩才付的钱,
        // 不该让根本不用压的会话替它买单。
        val needsCompaction = (soft != null && estimate > soft) ||
            (budget != null && estimate > budget!!)
        if (needsCompaction) {
            // 挑压谁靠成本模型,不靠反复全量重估。
            //
            // 每压一条就把整份 transcript 重新估一遍是 O(n²):长会话实测能到七百毫秒,而且
            // 每个工具轮都要重来一遍。模型只在被改动的那一条上重估,搜索退回 O(n)。
            var cost = CostModel(items = items, runtimeTranscript = runtimeTranscript, planner = this)

            // 第一档:没超预算但过了水位线,先压远处的,最近几轮不动。
            if (soft != null) {
                val protectedFrom = firstProtectedItemIndex(inItems = items)
                while (cost.total > soft &&
                    compactOldestAssistant(
                        items = items,
                        before = protectedFrom,
                        migratedOut = { migrated = true },
                        cost = cost,
                    )
                ) {
                    compacted += 1
                }
            }

            // 第二档:真超了,最近几轮也保不住;还不够就丢最老的一轮。
            val hardBudget = budget
            if (hardBudget != null) {
                while (cost.total > hardBudget) {
                    if (compactOldestAssistant(
                            items = items,
                            before = items.size,
                            migratedOut = { migrated = true },
                            cost = cost,
                        )
                    ) {
                        compacted += 1
                    } else if (dropOldestConversationTurn(items = items, cost = cost)) {
                        dropped += 1
                    } else {
                        break
                    }
                }
            }

            // 发出去的那个数必须是估算器自己算的整份 prompt。成本模型是用来**搜索**的,
            // 它假设估算是可加的;真到了要报给上层的数字上,不拿假设当结论。
            prompt = render(items = items, runtimeTranscript = runtimeTranscript)
            estimate = estimateTokens(prompt)

            // 假设不成立时(某个 provider 的估算不可加)模型会偏。偏了就退回逐条重估——
            // 该压的这时候基本已经压完了,循环不会跑几次。
            if (hardBudget != null && estimate > hardBudget) {
                while (estimate > hardBudget) {
                    if (compactOldestAssistant(
                            items = items,
                            before = items.size,
                            migratedOut = { migrated = true },
                            cost = cost,
                        )
                    ) {
                        compacted += 1
                    } else if (dropOldestConversationTurn(items = items, cost = cost)) {
                        dropped += 1
                    } else {
                        break
                    }
                    prompt = render(items = items, runtimeTranscript = runtimeTranscript)
                    estimate = estimateTokens(prompt)
                }
            }
        }

        return PreparedHistory(
            prompt = prompt,
            estimatedPromptTokens = estimate,
            compactedAssistantMessages = compacted,
            droppedConversationTurns = dropped,
            summarizedSpans = summarizedSpans,
            // 换模型的记号只有在真因为它压了东西时才写。换个模型什么都没发生,不该留痕。
            migrationNotes = if (migrated) listOf(MODEL_SWITCH_NOTE) else emptyList(),
            exceedsBudget = budget?.let { estimate > it } ?: false,
            providerId = profile.providerId,
            requestedModelId = profile.modelId,
            contextWindow = profile.contextWindow,
            reservedOutputTokens = reservedOutputTokens,
        )
    }

    private data class AssistantItem(
        var exact: List<AgentTranscript.Message>,
        var compact: List<AgentTranscript.Message>,
        var isCompacted: Boolean = false,
        /** 换模型之后,这一轮是"该先压的"。不代表一定会被压。 */
        var isMigrationCandidate: Boolean = false,
    ) {
        val active: List<AgentTranscript.Message>
            get() = if (isCompacted) compact else exact
        val canCompact: Boolean
            get() = !isCompacted && compact.isNotEmpty() && compact != exact
    }

    private sealed class HistoryItem {
        /**
         * 用户说的一句话,外加随它发出去的图片或文档。
         *
         * 图不单独成为一条 `HistoryItem`:它和那句话是同一次发言,压缩时也必须一起走——
         * 分开的话会出现「图还在、说明它的那句话已经被压成一句摘要」,而模型手上就剩一张
         * 没有来历的图。
         */
        data class User(
            val text: String,
            val files: List<AgentTranscript.FilePart> = emptyList(),
        ) : HistoryItem()

        data class Assistant(val item: AssistantItem) : HistoryItem()

        /** 一整段的摘要。已经是最紧的形态,不能再压。 */
        data class Summary(val message: AgentTranscript.Message) : HistoryItem()

        val isMigrationCandidate: Boolean
            get() = (this as? Assistant)?.item?.isMigrationCandidate == true

        /** 这一条当前形态下真正会发出去的消息。 */
        val messages: List<AgentTranscript.Message>
            get() = when (this) {
                is User -> {
                    if (files.isEmpty()) {
                        listOf(AgentTranscript.Message.user(text))
                    } else {
                        // 文字排在前面:那句话里写着「原图附在下面」和它是第几张,先读到它,
                        // 模型才知道后面这几张图是谁。
                        listOf(
                            AgentTranscript.Message(
                                role = AgentTranscript.Role.USER,
                                parts = listOf(AgentTranscript.Part.Text(text)) +
                                    files.map { AgentTranscript.Part.File(it) },
                            ),
                        )
                    }
                }
                is Assistant -> item.active
                is Summary -> listOf(message)
            }
    }

    /**
     * 按条记账的预算模型。
     *
     * 搜索「压哪几条才够」时不需要每步都把整份 transcript 交给估算器重算一遍:除了被改动的
     * 那一条,别的都没变。这里假设估算是可加的——同一个估算器对 [A, B] 的结果等于对 [A]
     * 加对 [B]。工具 schema 那部分是每次请求算一遍的常量,所以先单独量出来再从每条里扣掉,
     * 否则每条都会把整个工具面重复计一次。
     *
     * 这个假设只用来**挑**,不用来**报**:最终的数字仍然是估算器对整份 prompt 的结果。
     */
    private class CostModel(
        items: List<HistoryItem>,
        runtimeTranscript: AgentTranscript,
        planner: ConversationHistoryPlanner,
    ) {
        private val measure: (List<AgentTranscript.Message>) -> Int
        private val baseline: Int
        private val itemCosts: MutableList<Int>

        val total: Int
            get() = baseline + itemCosts.sum()

        init {
            val estimate = planner.estimateTokens
            // 空 transcript 的开销 = 工具面,压缩动不了它。
            val toolSurface = estimate(AgentTranscript(messages = emptyList()))
            measure = if (planner.estimateMessageTokens != null) {
                val lean = planner.estimateMessageTokens
                { messages ->
                    if (messages.isEmpty()) 0
                    else lean(AgentTranscript(messages = messages))
                }
            } else {
                // 没有专门的消息估算器,就用整份的那个再把常量扣掉。
                { messages ->
                    if (messages.isEmpty()) 0
                    else estimate(AgentTranscript(messages = messages)) - toolSurface
                }
            }

            baseline = toolSurface +
                measure(
                    listOf(AgentTranscript.Message.system(planner.systemInstruction)) +
                        runtimeTranscript.messages,
                )
            itemCosts = items.map { measure(it.messages) }.toMutableList()
        }

        fun refresh(index: Int, item: HistoryItem) {
            if (index !in itemCosts.indices) return
            itemCosts[index] = measure(item.messages)
        }

        fun removeSubrange(range: IntRange) {
            if (range.isEmpty()) return
            val from = range.first.coerceAtLeast(0)
            val toExclusive = (range.last + 1).coerceAtMost(itemCosts.size)
            if (from >= toExclusive) return
            itemCosts.subList(from, toExclusive).clear()
        }
    }

    private fun absorbedMessageIDs(inHistory: List<AgentChatMessageDTO>): Set<UUID> {
        val absorbed = mutableSetOf<UUID>()
        for (message in inHistory) {
            // 在工具轮边界并进那一轮的插话。列表里还留着一条(界面要显示),但 transcript
            // 中间已经有了。
            //
            // 要先确认 transcript 真的存下来了:那一轮被停掉或者失败时它是空的,回放走的是
            // `reconstructedReplayMessages`——那份是从 app 侧的工具记录重建的,里面没有插话。
            // 这时候还跳过,用户补的那句就凭空消失了。
            if (message.storedTurn.exactTranscript.messages.isNotEmpty()) {
                absorbed += message.storedTurn.inlinedUUIDs()
            }
            val artifact = message.storedTurn.compaction ?: continue
            val sources = artifact.sourceUUIDs()
            if (sources.size <= 1) continue
            absorbed += sources.filter { it != message.uuid }
        }
        return absorbed
    }

    private fun historyItems(from: List<AgentChatMessageDTO>): List<HistoryItem> {
        val absorbed = absorbedMessageIDs(inHistory = from)
        val items = mutableListOf<HistoryItem>()

        for (message in from) {
            // 已经被某段摘要吸收掉了,不再单独出现。
            if (message.uuid in absorbed) continue

            val artifact = message.storedTurn.compaction
            if (artifact != null && artifact.sourceUUIDs().size > 1) {
                items += HistoryItem.Summary(artifact.replaySummary)
                continue
            }

            when (message.role) {
                AgentChatMessageDTO.Role.USER -> {
                    if (message.text.isEmpty() || message.textIsPlaceholder) continue
                    items += HistoryItem.User(text = message.text, files = message.files)
                }
                AgentChatMessageDTO.Role.ASSISTANT -> {
                    if (!message.hasReplayableContent) continue
                    val exact = if (message.exactReplayMessages.isEmpty()) {
                        message.reconstructedReplayMessages
                    } else {
                        message.exactReplayMessages
                    }
                    val compact = message.compactReplayMessages(compactor)
                    if (exact.isEmpty() && compact.isEmpty()) continue
                    items += HistoryItem.Assistant(
                        AssistantItem(
                            exact = if (exact.isEmpty()) compact else exact,
                            compact = compact,
                            isMigrationCandidate = shouldPreferCompactionAfterModelSwitch(message),
                        ),
                    )
                }
            }
        }
        return items
    }

    private fun render(
        items: List<HistoryItem>,
        runtimeTranscript: AgentTranscript,
    ): AgentTranscript {
        val messages = mutableListOf(AgentTranscript.Message.system(systemInstruction))
        for (item in items) {
            messages += item.messages
        }
        messages += runtimeTranscript.messages
        return AgentTranscript(messages = messages)
    }

    /** 最近 `preservedRecentTurns` 轮从哪儿开始。它之后的东西在第一档里碰不得。 */
    private fun firstProtectedItemIndex(inItems: List<HistoryItem>): Int {
        val userIndices = inItems.mapIndexedNotNull { index, item ->
            if (item is HistoryItem.User) index else null
        }
        if (userIndices.size <= policy.preservedRecentTurns) return 0
        return userIndices[userIndices.size - policy.preservedRecentTurns]
    }

    /** 同样的边界,但按原始消息数组算——给 `summarizationPlan` 切段用。 */
    private fun firstUnprotectedBoundary(inMessages: List<AgentChatMessageDTO>): Int {
        val userIndices = inMessages.mapIndexedNotNull { index, message ->
            if (message.role == AgentChatMessageDTO.Role.USER) index else null
        }
        if (userIndices.size <= policy.preservedRecentTurns) return 0
        return userIndices[userIndices.size - policy.preservedRecentTurns]
    }

    /** 压一条。换模型的候选排在前面——它们本来就是从别的窗口带过来的。 */
    private fun compactOldestAssistant(
        items: MutableList<HistoryItem>,
        before: Int,
        migratedOut: () -> Unit,
        cost: CostModel,
    ): Boolean {
        val limit = minOf(before, items.size)

        fun compact(preferMigrated: Boolean): Boolean {
            for (index in 0 until limit) {
                val current = items[index] as? HistoryItem.Assistant ?: continue
                val item = current.item
                if (!item.canCompact || item.isMigrationCandidate != preferMigrated) continue
                item.isCompacted = true
                items[index] = HistoryItem.Assistant(item)
                cost.refresh(index, items[index])
                if (preferMigrated) migratedOut()
                return true
            }
            return false
        }

        return compact(preferMigrated = true) || compact(preferMigrated = false)
    }

    private fun dropOldestConversationTurn(
        items: MutableList<HistoryItem>,
        cost: CostModel,
    ): Boolean {
        val userCount = items.count { it is HistoryItem.User }
        // 最后一轮是用户刚问的那句,丢了就没得答了。
        if (userCount <= 1) return false

        var end = 1
        while (end < items.size) {
            if (items[end] is HistoryItem.User) break
            end += 1
        }
        items.subList(0, end).clear()
        cost.removeSubrange(0 until end)
        return true
    }

    private fun shouldPreferCompactionAfterModelSwitch(message: AgentChatMessageDTO): Boolean {
        if (migrationPolicy == HistoryMigrationPolicy.NEVER) return false
        val context = message.storedTurn.context ?: return false
        if (context.matches(profile)) return false
        if (migrationPolicy == HistoryMigrationPolicy.ALWAYS) return true

        val currentWindow = profile.contextWindow ?: return true
        val priorWindow = context.contextWindow ?: return true
        return priorWindow > currentWindow
    }

    companion object {
        const val MODEL_SWITCH_NOTE = "history_compacted_for_model_switch"
    }
}
