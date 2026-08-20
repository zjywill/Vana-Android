package com.pinapia.vana.agentruntime

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.UnknownServiceException
import javax.net.ssl.SSLException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * 一轮失败之后要不要再试一次,以及等多久。
 *
 * 手机上跑 agent 和服务器上不一样:一次 502、一次基站切换、一次流被中间设备掐断,都不该
 * 让用户重问一遍。但重试必须是**有分类的**——对着「余额不足」重试三次只是把同一个错误
 * 报三遍,还耽误了给用户看到真正的原因。分类在 [ModelFailure],预算在这里。
 */
data class RetryPolicy(
    val isEnabled: Boolean = true,
    /** 最多重试几次。首次请求不算重试。 */
    val maxRetries: Int = 3,
    /** 第 n 次重试等 `baseDelay * 2^(n-1)`,封顶 `maxDelay`。 */
    val baseDelay: Duration = 1.seconds,
    val maxDelay: Duration = 20.seconds,
) {
    fun allowsRetry(attempt: Int): Boolean = isEnabled && attempt <= maxRetries

    /** 第 [attempt] 次重试(从 1 开始)之前要等的时间。 */
    fun delay(forAttempt: Int): Duration {
        if (forAttempt <= 0) return Duration.ZERO
        val exponent = minOf(forAttempt - 1, 16)
        val multiplied = baseDelay * (1 shl exponent)
        return minOf(multiplied, maxDelay)
    }

    companion object {
        val default = RetryPolicy()
        val disabled = RetryPolicy(isEnabled = false, maxRetries = 0)
    }
}

/**
 * 失败原因的分类器。
 *
 * 两条通道,顺序不能反:
 * 1. **传输层看错误码/异常类型**。拿本地化字符串做网络故障分类,等于只在英文设备上能重试。
 * 2. **provider 侧看字符串**。那段话是 API 返回的原始 payload,不本地化,而且经过各家 SDK、
 *    网关、代理转手之后,能稳定留下来的也只有它——错误码在这条链路上活不下来。
 */
object ModelFailure {
    private val quota = listOf(
        "insufficient quota", "quota exceeded", "out of budget", "billing",
        "usage limit", "credit balance", "payment required",
    )

    private val authentication = listOf(
        "invalid api key", "invalid x api key", "incorrect api key", "api key not valid",
        "unauthorized", "authentication", "permission denied", "forbidden",
    )

    private val malformed = listOf(
        "invalid request error", "model not found", "does not exist",
    )

    /** 明确不该重试的:重试也还是这个结果,而且每试一次都在拖延用户看到真正的原因。 */
    private val permanent = quota + authentication + malformed

    /** 拥塞、限流、网络抖动、流被提前掐断——都是再试一次就可能好的。 */
    private val transient = listOf(
        // provider 侧的负载和 HTTP 状态。
        "overloaded", "rate limit", "ratelimit", "too many requests",
        "429", "500", "502", "503", "504", "529",
        "service unavailable", "server error", "internal error", "temporarily unavailable",
        "capacity", "try again", "retry",
        // 网络和传输层。手机上这一类最多。
        "network", "connection refused", "connection lost", "connection reset",
        "connection error", "cannot connect", "not connected to the internet",
        "the request timed out", "timed out", "timeout",
        "software caused connection abort", "socket", "other side closed",
        "fetch failed", "getaddrinfo", "enotfound", "eai again", "dns",
        // 流没走完就断了。SDK 各写各的话,但都长这样。
        "ended without", "stream ended", "unexpected end", "incomplete",
        "terminated", "cancelled by the server",
    )

    /** 上下文塞不下。这条不能走重试——原样再发一次还是塞不下,得先压缩。 */
    private val overflow = listOf(
        "context length", "context window", "context_length", "maximum context",
        "too many tokens", "too many input tokens", "prompt is too long",
        "input is too long", "exceeds the maximum", "reduce the length",
        "request too large", "413", "string too long",
    )

    /** 大小写、下划线、连字符各家写法不一,统一成小写空格再比。 */
    private fun normalized(description: String): String =
        description
            .lowercase()
            .replace('_', ' ')
            .replace('-', ' ')

    enum class Kind {
        AUTHENTICATION,
        QUOTA,
        CONTEXT_OVERFLOW,
        TRANSIENT,
        OTHER,
    }

    fun kind(description: String): Kind {
        val text = normalized(description)
        if (overflow.any { text.contains(it) }) return Kind.CONTEXT_OVERFLOW
        if (authentication.any { text.contains(it) } || hasHttpCode(text, 401, 403)) {
            return Kind.AUTHENTICATION
        }
        if (quota.any { text.contains(it) } || text.contains("429 quota") || hasHttpCode(text, 402)) {
            return Kind.QUOTA
        }
        if (transient.any { text.contains(it) }) return Kind.TRANSIENT
        return Kind.OTHER
    }

    private fun hasHttpCode(text: String, vararg codes: Int): Boolean =
        codes.any { code ->
            Regex("""(^|\D)$code(\D|$)""").containsMatchIn(text)
        }

    fun isContextOverflow(description: String): Boolean {
        val text = normalized(description)
        return overflow.any { text.contains(it) }
    }

    fun isRetryable(description: String): Boolean {
        val text = normalized(description)
        // 顺序有意义:溢出和永久失败都会命中 transient 里的某个词(比如 "413" 里的 "13"、
        // 账单错误里的 "try again"),必须先被挡下来。
        if (isContextOverflow(text)) return false
        if (permanent.any { text.contains(it) }) return false
        return transient.any { text.contains(it) }
    }

    /** 传输层的失败按异常类型判,判不了才回落到文案。 */
    fun isRetryable(error: Throwable, description: String): Boolean =
        transportVerdict(error) ?: isRetryable(description)

    /**
     * 这是不是一个可以再试一次的传输故障。不是传输故障就返回 null,交给文案那条通道。
     *
     * JVM 侧对应 iOS 的 NSURLErrorDomain 分类:超时、DNS、断连可重试;证书/协议类不可重试。
     */
    private fun transportVerdict(error: Throwable): Boolean? {
        var current: Throwable? = error
        while (current != null) {
            when (current) {
                is SocketTimeoutException,
                is UnknownHostException,
                is ConnectException,
                is NoRouteToHostException,
                is SocketException,
                -> return true
                is SSLException -> return true
                is UnknownServiceException -> return false
                is IOException -> {
                    // 笼统的 IO 故障在手机上多数是瞬时的(换基站、进电梯)。
                    // 把「明确不可恢复」的子类排除后,其余按可重试处理。
                    if (current !is java.io.FileNotFoundException &&
                        current !is java.io.InvalidClassException
                    ) {
                        return true
                    }
                }
            }
            current = current.cause
        }
        return null
    }
}

/** 一次重试的通知。给 UI 用——退避期间界面上什么都不动的话,用户只会以为卡死了。 */
data class AgentRetryNotice(
    val attempt: Int,
    val maxAttempts: Int,
    val delay: Duration,
    /** provider 报的原文。 */
    val reason: String,
)

/** 这次压缩是谁触发的。 */
enum class AgentCompactionReason {
    /** 估算跨过了水位线,主动压。 */
    THRESHOLD,

    /** provider 已经报了上下文超限,压完重跑这一轮。 */
    OVERFLOW_RECOVERY,
}
