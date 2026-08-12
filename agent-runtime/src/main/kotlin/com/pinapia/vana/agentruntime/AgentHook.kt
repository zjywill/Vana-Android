package com.pinapia.vana.agentruntime

import java.util.UUID
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.time.Duration
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * loop 生命周期上的旁观位。
 *
 * 事件流([AgentTurnEvent])和 hook 是**两条通道**,不要合并:
 *
 * - 事件流是给正在看这条回复的人的:每个字都要送到,顺序不能错,慢一帧就是界面卡。它的
 *   消费者只有一个,就是那块屏幕。
 * - hook 是给不在场的那些消费者的:埋点、统计、答完之后再花一次调用生成点什么。它们不该
 *   为了知道「刚才发生了什么」去挤那条通道——挤进去之后一个慢消费者就能拖住流式输出,
 *   而且每加一件事都要在界面的状态机上开一个洞。
 *
 * 四条边界,不要破坏:
 *
 * 1. **hook 改不了这一轮的任何东西**。[observe] 没有返回值,loop 也不读它的任何输出。想
 *    否决一次工具调用、想往 prompt 里塞一段,都不走这儿——那些决定发生在**装配的时候**
 *    (挂哪些能力、system 段拼什么),那时候还没有人在等。挪进 loop 就是让用户等一个外部
 *    决定,而且这一轮的预算已经估过了,账会当场对不上。
 * 2. **hook 永不阻塞 loop**。派发即返回:一个卡住的 hook 不能让用户多等一个字。
 * 3. **hook 看不见 delta**,只看得见边界。思考模型一秒吐几十个 delta,给每个 delta 加一次
 *    往返是纯亏——而它们本来就不需要:要内容的在 `turnFinished` 里拿整份 transcript,
 *    那份就是模型当时看到的原样。
 * 4. **失败即放弃**。[observe] 不能抛:hook 里出的事跟这一轮没关系,不该让用户这一句问不
 *    出去。
 */
fun interface AgentHook {
    suspend fun observe(notice: AgentHookNotice)
}

/**
 * 一次 [AgentLoop.run] 在某个边界上发出的通知。
 *
 * `turnId` 一次 run 一个:hook 多半是跨轮有状态的(上一轮答完的东西被下一轮作废),而它
 * 只靠通知本身分不出「这是同一轮的收尾」还是「下一轮的开场」。
 */
data class AgentHookNotice(
    val turnId: UUID,
    val at: Instant = Clock.System.now(),
    val kind: Kind,
) {
    sealed class Kind {
        /** 这一轮要发第一次请求了。压缩、预算、工具都还没跑。 */
        data class TurnStarted(val start: AgentHookTurnStart) : Kind()

        /** 一次能力跑完了(成功或失败),输出已经按 [ContextPolicy] 截过。 */
        data class ToolFinished(val outcome: AgentHookToolOutcome) : Kind()

        /**
         * 这一轮收尾了。**四条出口只有这一个**:答完、轮数用光、用户按停止、救不回来的
         * 错误,都走这一条,由 `state` 分。少一条出口,hook 就得靠超时去猜自己在等的那一轮
         * 是不是已经不会来了。
         */
        data class TurnFinished(val outcome: AgentHookTurnOutcome) : Kind()
    }
}

data class AgentHookTurnStart(
    /**
     * 这一轮开跑时的历史,值语义的一份快照。
     *
     * 给了历史,hook 才不用回头去翻 app 的状态——那份状态在 hook 真正跑起来的时候可能已经
     * 变了(用户切了会话、又发了一句)。代价是里面含着每一轮的 `exactTranscript`:**用完
     * 就扔,不要留着**,留一份就是把整段对话钉在内存里。
     */
    val history: List<AgentChatMessageDTO>,
    val profile: AgentModelProfile,
)

data class AgentHookToolOutcome(
    val toolCallId: String,
    val name: String,
    val isError: Boolean,
    /**
     * 进上下文的那段输出有多长(已经截过)。
     *
     * 只给长度不给内容:要内容的在 `turnFinished` 的 transcript 里拿一份完整的,不必让每
     * 一次调用都往 hook 那边多复制一遍几千字符。
     */
    val outputCharacters: Int,
    val duration: Duration,
)

data class AgentHookTurnOutcome(
    val state: State,
    /**
     * 这一轮真正发生的一切:模型说的话、思考、工具调用和结果,以及用户中途插的那几句。
     * 和存进会话、回放给模型的是同一份。
     */
    val transcript: AgentTranscript,
    val finishReason: AgentFinishReason? = null,
    val usage: AgentUsage? = null,
    val context: TurnContextSnapshotDTO? = null,
) {
    sealed class State {
        /**
         * 模型说完了,或者轮数用光正常收尾(`finishReason.raw` 是
         * [AgentLoop.TOOL_ROUND_LIMIT_REASON])。
         */
        data object Completed : State()

        /** 用户按了停止。已经说出来的话和查到的东西都在 `transcript` 里。 */
        data object Stopped : State()

        /** 重试、压缩都救不回来。带的是给分类器看的那段原文。 */
        data class Failed(val description: String) : State()
    }
}

/**
 * hook 的宿主。**由 app 持有,活得比一轮长**。
 *
 * 不由 loop 现造:hook 是跨轮有状态的——「上一轮答完了」和「下一轮开跑了」必须按顺序到达
 * 同一个 hook(答完之后要生成的那点东西,被下一轮作废)。每次回复现造一个宿主的话,两轮
 * 之间的顺序就没人保证了,而那正是唯一要紧的一处顺序。
 *
 * 派发的形状是「每个 hook 一条尾巴」:
 * - 同一个 hook 收到的通知**严格按发生顺序**。
 * - 不同 hook 之间不保证顺序,也**不互相拖累**:一个 hook 卡住十秒,别的照常收。
 * - [post] 只做入队,不等 hook 跑完——这是「hook 永不阻塞 loop」那条边界的落点。
 */
class AgentHookDispatcher(
    private val hooks: List<AgentHook>,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val mutex = Mutex()
    private val tails: Array<Job?> = arrayOfNulls(hooks.size)

    /**
     * 入队即返回。等的是入队完成,不是 hook 跑完。
     *
     * 不是 child job:这一轮被取消(用户按停止)时,`turnFinished(.stopped)` 这条
     * 通知照样要送到——它恰恰是「这一轮不用再管了」的唯一信号。
     */
    suspend fun post(notice: AgentHookNotice) {
        mutex.withLock {
            for (index in hooks.indices) {
                val hook = hooks[index]
                val previous = tails[index]
                tails[index] = scope.launch {
                    previous?.join()
                    hook.observe(notice)
                }
            }
        }
    }

    /**
     * 等已经入队的都跑完。**测试用**。
     *
     * 生产代码不该等 hook:等它就等于把那条边界作废了。
     */
    suspend fun settle() {
        val pending = mutex.withLock { tails.toList() }
        pending.forEach { it?.join() }
    }
}

/** 便于测时长的单调时钟标记。 */
typealias MonotonicMark = TimeSource.Monotonic.ValueTimeMark
