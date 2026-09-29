package com.pinapia.vana.agent

import com.pinapia.vana.agentruntime.AgentChatMessageDTO
import com.pinapia.vana.agentruntime.AgentLoopError
import com.pinapia.vana.agentruntime.AgentPendingInputProvider
import com.pinapia.vana.agentruntime.AgentTurnEvent
import com.pinapia.vana.agentruntime.ContextPolicy
import com.pinapia.vana.agentruntime.ModelSummarizer
import com.pinapia.vana.agentruntime.TranscriptCompactor
import com.pinapia.vana.session.ChatMessage
import kotlinx.coroutines.flow.Flow

sealed class AgentError(message: String) : Exception(message) {
    data object NeedsAPIKey : AgentError("需要先在设置里填写云端 API 密钥") {
        private fun readResolve(): Any = NeedsAPIKey
    }

    data class InvalidAPIKey(val detail: String) : AgentError(detail)

    data object NeedsModelSelection : AgentError("需要先在设置里选择云端模型") {
        private fun readResolve(): Any = NeedsModelSelection
    }

    data class CloudService(val detail: String) : AgentError("云端服务返回错误：$detail")

    data object IncompleteResponse : AgentError("模型回复没有正常结束，请重试") {
        private fun readResolve(): Any = IncompleteResponse
    }

    data object ContextWindowExceeded : AgentError("这一轮内容太多，超出了模型一次能看完的范围") {
        private fun readResolve(): Any = ContextWindowExceeded
    }

    companion object {
        fun wrapping(error: Throwable): Throwable = when (error) {
            is AgentError -> error
            is kotlinx.coroutines.CancellationException -> error
            is AgentLoopError.Service -> CloudService(error.detail)
            is AgentLoopError.ContentFilter -> CloudService("模型因安全策略拒绝了这次请求")
            is AgentLoopError.IncompleteResponse -> IncompleteResponse
            is AgentLoopError.ContextWindowExceeded -> ContextWindowExceeded
            else -> error
        }
    }
}

interface AgentEngine {
    val name: String
    val supportsVision: Boolean get() = false

    fun reply(
        history: List<ChatMessage>,
        pendingInput: AgentPendingInputProvider? = null,
    ): Flow<AgentTurnEvent>
}

fun ModelSummarizer.Companion.chat(client: com.pinapia.vana.agentruntime.AgentModelClient): ModelSummarizer {
    val sections = """
        ## 用户目标
        ## 用户情况与偏好（用户说过的限制、习惯、在意的事）
        ## 已有结论（连同支撑它的具体数字、日期、名称）
        ## 查询轨迹（调用过哪个工具、参数是什么、返回了什么）
        ## 待跟进（用户接下来大概要问什么）
    """.trimIndent()
    return ModelSummarizer(
        client = client,
        instruction = """
            你在压缩一段助手与用户的对话，好让它能在更小的上下文窗口里继续。
            只输出两段，各自用标签包起来：

            <visible>一句话给用户看的回顾：到目前为止聊过什么。</visible>
            <replay>给接着聊下去的助手看的要点笔记，严格用这几个小标题：
            $sections
            数字原样保留，不要四舍五入。可以丢掉：寒暄、重复的提问、已经被结论概括掉的逐行原始数据。写成笔记，不要写成文章。某个小标题下没内容就写「（无）」。</replay>

            不要编造对话里没有的事实。
        """.trimIndent(),
        updateInstruction = """
            你在维护一份助手与用户对话的滚动摘要。给你的是上一版摘要，和它之后新发生的对话。
            只输出两段，各自用标签包起来：

            <visible>一句话给用户看的回顾：到目前为止聊过什么。</visible>
            <replay>更新后的要点笔记，严格用这几个小标题：
            $sections
            上一版摘要里仍然成立的内容必须全部带过来——原始对话已经不在了，你丢掉的就永久丢了。再把新对话并进去：查完的结论并入「已有结论」，「待跟进」按最新情况改写。数字原样保留，不要四舍五入。写成笔记，不要写成文章。</replay>

            不要编造上一版摘要和新对话里都没有的事实。
        """.trimIndent(),
        requestFormat = "下面是要压缩的对话：\n\n%s",
        updateRequestFormat = """
            <previous-summary>
            %1${'$'}s
            </previous-summary>

            这版摘要之后新发生的对话：

            %2${'$'}s
        """.trimIndent(),
        fallbackVisibleFormat = "早先的 %d 条对话已折叠",
    )
}

val ContextPolicy.Companion.chat: ContextPolicy
    get() = ContextPolicy(
        toolOutputTruncationNotice = "…（结果过长已截断：省略 %d 字，原文共 %d 字。需要更细可以缩小范围再查一次）",
    )

val chatTruncatedToolCallNotice =
    "这次调用没有执行：上一条回复达到了输出长度上限，参数可能不完整。请用完整的参数重新调用一次。"

val TranscriptCompactor.Companion.chat: TranscriptCompactor
    get() = TranscriptCompactor(
        maxCharactersPerToolCall = 180,
        maxToolCallsInDigest = 6,
        digestHeaderFormat = "[这一轮折叠了 %d 次工具调用，只保留要点]",
        truncationSuffix = "…（已截断）",
        overflowFormat = "（另有 %d 次查询未列出）",
    )

fun List<ChatMessage>.toAgentDTOs(): List<AgentChatMessageDTO> = HistoryMarkers.apply(this)
