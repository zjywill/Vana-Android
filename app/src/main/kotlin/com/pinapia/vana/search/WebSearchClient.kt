package com.pinapia.vana.search

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class WebSearchResults(
    val query: String,
    val knowledge: Knowledge? = null,
    val items: List<Item> = emptyList(),
) {
    data class Item(
        val title: String,
        val link: String,
        val snippet: String,
        val date: String? = null,
    )

    data class Knowledge(
        val title: String,
        val description: String? = null,
        val attributes: List<Pair<String, String>> = emptyList(),
    )

    val isEmpty: Boolean get() = knowledge == null && items.isEmpty()
}

class WebSearchError(val statusCode: Int) : Exception(
    when (statusCode) {
        401, 403 -> "搜索服务的 key 无效或已过期，请到设置里检查。"
        429 -> "搜索服务的额度用完了，这个月先靠已有的知识回答。"
        else -> "搜索服务返回了错误（$statusCode）。"
    },
)

fun interface WebSearchClient {
    suspend fun search(query: String): WebSearchResults

    companion object {
        private const val RESULT_LIMIT = 6
        private const val SNIPPET_LIMIT = 220
        private const val ATTRIBUTE_LIMIT = 6

        fun storedKey(apiKey: String?): WebSearchClient? {
            val key = apiKey?.trim().orEmpty()
            return if (key.isEmpty()) null else serper(key)
        }

        fun serper(
            apiKey: String,
            locale: Locale = Locale.getDefault(),
            httpClient: OkHttpClient = defaultClient,
        ): WebSearchClient = WebSearchClient { query ->
            withContext(Dispatchers.IO) {
                val body = buildJsonObject {
                    put("q", query)
                    put("num", RESULT_LIMIT)
                    locale.country.takeIf { it.isNotBlank() }?.let { put("gl", it.lowercase()) }
                    put("hl", searchLanguage(locale))
                }.toString()
                val request = Request.Builder()
                    .url("https://google.serper.dev/search")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .header("X-API-KEY", apiKey)
                    .header("Content-Type", "application/json")
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw WebSearchError(response.code)
                    val raw = response.body?.string().orEmpty()
                    parse(raw, query)
                }
            }
        }

        private fun searchLanguage(locale: Locale): String {
            val language = locale.toLanguageTag().lowercase()
            return when {
                language.startsWith("zh-hant") || language.startsWith("zh-tw") || language.startsWith("zh-hk") -> "zh-tw"
                language.startsWith("zh") -> "zh-cn"
                else -> locale.language.ifBlank { "en" }
            }
        }

        private fun parse(raw: String, query: String): WebSearchResults {
            val root = Json.parseToJsonElement(raw).jsonObject
            val knowledge = root["knowledgeGraph"]?.jsonObject?.let { kg ->
                val title = kg["title"]?.jsonPrimitive?.contentOrNull ?: return@let null
                val description = kg["description"]?.jsonPrimitive?.contentOrNull
                val attrs = kg["attributes"]?.jsonObject
                    ?.entries
                    ?.mapNotNull { (k, v) -> v.jsonPrimitive.contentOrNull?.let { k to it } }
                    ?.sortedBy { it.first }
                    ?.take(ATTRIBUTE_LIMIT)
                    .orEmpty()
                WebSearchResults.Knowledge(title = title, description = description, attributes = attrs)
            }
            val items = root["organic"]?.jsonArray.orEmpty().mapNotNull { element ->
                val obj = element.jsonObject
                val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val link = obj["link"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val snippet = obj["snippet"]?.jsonPrimitive?.contentOrNull.orEmpty().take(SNIPPET_LIMIT)
                val date = obj["date"]?.jsonPrimitive?.contentOrNull
                WebSearchResults.Item(title = title, link = link, snippet = snippet, date = date)
            }.take(RESULT_LIMIT)
            return WebSearchResults(query = query, knowledge = knowledge, items = items)
        }

        private val defaultClient = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .writeTimeout(12, TimeUnit.SECONDS)
            .build()
    }
}
