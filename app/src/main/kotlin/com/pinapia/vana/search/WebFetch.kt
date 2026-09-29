package com.pinapia.vana.search

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/** 读回来的一页。[text] 已经是去掉标记的正文,[truncated] 说明后面还有没读的。 */
data class WebPage(
    val url: String,
    val title: String?,
    val text: String,
    val truncated: Boolean,
)

class WebFetchError(message: String) : Exception(message)

fun interface WebFetchClient {
    suspend fun fetch(url: String): WebPage

    companion object {
        /** 走本机网络直连目标网站(不经过任何中转服务)。 */
        fun direct(): WebFetchClient = OkHttpWebFetch
    }
}

/**
 * 这个工具能去哪儿。**只读公开网页**:模型拼出来的、或者网页里带的地址,不能成为进内网的入口。
 * 分两层挡:这里是能在地址字面上看出来的(协议、内网域名后缀、IP 字面量、奇怪的端口、带口令的地址),
 * 域名解析出来落在内网的由 [OkHttpWebFetch] 里的 DNS 在真正连接时再挡一次(每一跳重定向都过)。
 */
object FetchUrlPolicy {
    sealed interface Verdict {
        data class Allowed(val url: HttpUrl) : Verdict
        data class Blocked(val reason: String) : Verdict
    }

    private val allowedPorts = setOf(80, 443, 8080, 8443)
    private val privateSuffixes = listOf(".localhost", ".local", ".internal", ".lan", ".home", ".corp", ".intranet", ".private")

    fun check(raw: String): Verdict {
        val url = raw.trim().toHttpUrlOrNull() ?: return Verdict.Blocked("这不是一个有效的网址。")
        if (url.scheme != "http" && url.scheme != "https") return Verdict.Blocked("只能读 http 或 https 的网页。")
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return Verdict.Blocked("带账号口令的网址不读。")
        if (url.port !in allowedPorts) return Verdict.Blocked("这个端口不读。")
        val host = url.host.lowercase().trimEnd('.')
        if (host == "localhost" || privateSuffixes.any { host.endsWith(it) }) return Verdict.Blocked("内网或本机地址不读。")
        if (isIpLiteral(host)) {
            val address = runCatching { InetAddress.getByName(host) }.getOrNull()
                ?: return Verdict.Blocked("这个地址不对。")
            if (isBlocked(address)) return Verdict.Blocked("内网或本机地址不读。")
        } else if (!host.contains('.')) {
            return Verdict.Blocked("内网主机名不读。")
        }
        return Verdict.Allowed(url)
    }

    private fun isIpLiteral(host: String): Boolean =
        host.contains(':') || Regex("""^\d{1,3}(\.\d{1,3}){3}$""").matches(host)

    /** 本机、内网、链路本地、组播、运营商级 NAT、保留段:一律不读。 */
    fun isBlocked(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress
        ) {
            return true
        }
        val bytes = address.address
        return when (address) {
            is Inet4Address -> {
                val a = bytes[0].toInt() and 0xFF
                val b = bytes[1].toInt() and 0xFF
                a == 0 ||
                    (a == 100 && b in 64..127) || // 100.64.0.0/10 运营商级 NAT
                    (a == 192 && b == 0 && (bytes[2].toInt() and 0xFF) == 0) || // 192.0.0.0/24
                    (a == 198 && b in 18..19) || // 198.18.0.0/15 基准测试
                    a >= 240 // 保留和广播
            }
            is Inet6Address -> (bytes[0].toInt() and 0xFE) == 0xFC // fc00::/7 唯一本地地址
            else -> false
        }
    }

    /** 解析出来的地址里只要有一个落在不该去的地方,整个域名都不连。 */
    val guardedDns: Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val addresses = Dns.SYSTEM.lookup(hostname)
            if (addresses.any(::isBlocked)) throw UnknownHostException("blocked: $hostname")
            return addresses
        }
    }
}

/** HTML → 可读文字。不追求还原排版:只要模型能读、体量小。 */
object HtmlText {
    private val dropBlocks = Regex("""<(script|style|noscript|svg|iframe|template|head|nav|footer|form)\b[^>]*>.*?</\1\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val comments = Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL)
    private val blockTags = Regex("""</?(p|div|br|li|ul|ol|tr|table|h[1-6]|section|article|main|blockquote|pre|hr)\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val anyTag = Regex("""<[^>]+>""")
    private val title = Regex("""<title[^>]*>(.*?)</title\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val article = Regex("""<article\b[^>]*>(.*?)</article\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val entity = Regex("""&(#x?[0-9a-fA-F]+|[a-zA-Z]+);""")

    fun title(html: String): String? =
        title.find(html)?.groupValues?.get(1)?.let { decode(anyTag.replace(it, "")).trim() }?.takeIf { it.isNotEmpty() }?.take(200)

    fun text(html: String): String {
        // 有 <article> 就取最长的那一篇:多数文章类页面正文都在里面,侧栏和推荐不在。
        val focus = article.findAll(html).map { it.groupValues[1] }.maxByOrNull { it.length }?.takeIf { it.length > 400 } ?: html
        val cleaned = comments.replace(dropBlocks.replace(focus, " "), " ")
        val withBreaks = blockTags.replace(cleaned, "\n")
        val plain = decode(anyTag.replace(withBreaks, ""))
        return plain.lines()
            .map { it.replace(Regex("""[ \t ]+"""), " ").trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    private fun decode(text: String): String = entity.replace(text) { match ->
        val body = match.groupValues[1]
        when {
            body.startsWith("#x") || body.startsWith("#X") -> body.drop(2).toIntOrNull(16)?.let(::codePoint)
            body.startsWith("#") -> body.drop(1).toIntOrNull()?.let(::codePoint)
            else -> named[body.lowercase()]
        } ?: match.value
    }

    private fun codePoint(value: Int): String? =
        if (value in 1..0x10FFFF) String(Character.toChars(value)) else null

    private val named = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "ldquo" to "“", "rdquo" to "”", "lsquo" to "‘", "rsquo" to "’", "hellip" to "…",
        "mdash" to "—", "ndash" to "–", "middot" to "·", "copy" to "©",
    )
}

object OkHttpWebFetch : WebFetchClient {
    private const val MAX_BYTES = 600_000L
    const val MAX_CHARS = 8_000
    private const val MAX_REDIRECTS = 3

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .dns(FetchUrlPolicy.guardedDns)
        .build()

    private val textTypes = listOf("text/html", "text/plain", "application/xhtml+xml", "text/markdown", "application/json", "text/xml", "application/xml")

    override suspend fun fetch(url: String): WebPage = withContext(Dispatchers.IO) {
        var current = url
        repeat(MAX_REDIRECTS + 1) { hop ->
            val allowed = when (val verdict = FetchUrlPolicy.check(current)) {
                is FetchUrlPolicy.Verdict.Allowed -> verdict.url
                is FetchUrlPolicy.Verdict.Blocked -> throw WebFetchError(verdict.reason)
            }
            val request = Request.Builder().url(allowed)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android) Vana/1.0")
                .header("Accept", "text/html,text/plain,application/json;q=0.9,*/*;q=0.1")
                .build()
            val response = try {
                client.newCall(request).execute()
            } catch (error: UnknownHostException) {
                throw WebFetchError("打不开这个网址（域名解析失败）。")
            } catch (error: java.io.IOException) {
                throw WebFetchError("打不开这个网址：${error.message ?: "网络错误"}")
            }
            response.use { r ->
                if (r.isRedirect) {
                    val next = r.header("Location")?.let { allowed.resolve(it) }?.toString()
                        ?: throw WebFetchError("网页跳转到了一个无效的地址。")
                    if (hop == MAX_REDIRECTS) throw WebFetchError("网页跳转的次数太多了。")
                    current = next
                    return@use
                }
                if (!r.isSuccessful) throw WebFetchError("网页返回了错误（${r.code}）。")
                val type = r.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase().orEmpty()
                if (type.isNotEmpty() && textTypes.none { type == it }) {
                    throw WebFetchError("这不是文字网页（类型 $type），读不了。")
                }
                val body = r.peekBody(MAX_BYTES)
                val bytes = body.bytes()
                val overflow = bytes.size.toLong() >= MAX_BYTES
                val raw = String(bytes, charsetOf(r.body?.contentType()?.charset(), bytes))
                val isHtml = type.isEmpty() || type.contains("html")
                val text = if (isHtml) HtmlText.text(raw) else raw.trim()
                val clipped = clip(text)
                return@withContext WebPage(
                    url = allowed.toString(),
                    title = if (isHtml) HtmlText.title(raw) else null,
                    text = clipped.first,
                    truncated = clipped.second || overflow,
                )
            }
        }
        throw WebFetchError("网页跳转的次数太多了。")
    }

    private fun charsetOf(header: Charset?, bytes: ByteArray): Charset {
        header?.let { return it }
        val head = String(bytes, 0, minOf(bytes.size, 2048), Charsets.ISO_8859_1)
        val declared = Regex("""charset\s*=\s*["']?([\w-]+)""", RegexOption.IGNORE_CASE).find(head)?.groupValues?.get(1)
        return runCatching { declared?.let { Charset.forName(it) } }.getOrNull() ?: Charsets.UTF_8
    }

    /** 按行截到 [MAX_CHARS] 以内:半行数字比没有数字更危险。返回(截后的文字, 是否截过)。 */
    fun clip(text: String): Pair<String, Boolean> {
        if (text.length <= MAX_CHARS) return text to false
        var kept = ""
        for (line in text.lines()) {
            if (kept.length + line.length + 1 > MAX_CHARS) break
            kept = if (kept.isEmpty()) line else "$kept\n$line"
        }
        if (kept.isEmpty()) kept = text.take(MAX_CHARS)
        return kept to true
    }
}
