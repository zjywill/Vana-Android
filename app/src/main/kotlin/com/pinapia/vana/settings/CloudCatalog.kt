package com.pinapia.vana.settings

import android.content.Context
import android.content.res.AssetManager
import java.net.URI
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 云端 provider / 模型选项。**全部来自 AIKit 内置 catalog**（`assets/catalog/providers`），
 * 设置页只做选择，不维护一份手写名单。
 *
 * 过滤规则同 iOS [CloudCatalog]：
 * - 有已实现的 wire protocol（Android 目前：openai / anthropic / gemini）
 * - 有可连的托管 API（排除 localhost / 无 api）
 * - 模型列表只列 `tool_call == true`（用药、测量、记忆等能力依赖工具调用）
 */
object CloudCatalog {
    enum class WireProtocol(val adapter: String) {
        OPENAI("openai"),
        ANTHROPIC("anthropic"),
        GEMINI("gemini"),
        ;

        companion object {
            fun fromAdapter(adapter: String?): WireProtocol? = when (adapter) {
                OPENAI.adapter -> OPENAI
                ANTHROPIC.adapter -> ANTHROPIC
                GEMINI.adapter -> GEMINI
                else -> null
            }
        }
    }

    data class ModelInfo(
        val id: String,
        val name: String? = null,
        val toolCall: Boolean? = null,
        val reasoningSupported: Boolean? = null,
        val contextWindow: Int? = null,
        val maxOutputTokens: Int? = null,
        val inputModalities: List<String> = emptyList(),
    ) {
        val displayName: String get() = name ?: id
        val supportsTools: Boolean get() = toolCall == true
        val supportsReasoning: Boolean get() = reasoningSupported == true
        /** 看图看 modalities.input 含 image，不是 attachment 字段。 */
        val supportsVision: Boolean get() = inputModalities.contains("image")
    }

    data class ProviderInfo(
        val id: String,
        val name: String? = null,
        val api: String? = null,
        val adapter: String? = null,
        val models: List<ModelInfo> = emptyList(),
    ) {
        val displayName: String get() = name ?: id
        val apiBaseUrl: String get() = api.orEmpty()
        val wireProtocol: WireProtocol? get() = WireProtocol.fromAdapter(adapter)

        fun requireWireProtocol(): WireProtocol =
            wireProtocol ?: error("provider $id（adapter=$adapter）的协议尚未在 Android 实现")

        fun model(modelId: String): ModelInfo? = models.firstOrNull { it.id == modelId }
    }

    /**
     * 解析放到后台线程,访问方需要时才等它(`FutureTask.get`)。
     *
     * 换到 models.dev 目录之后 catalog 从 423KB/49 家涨到 ~2.4MB/179 家,原来在
     * `Application.onCreate` 里同步解析的那一下变成了百毫秒级的冷启动税。第一个真正
     * 要读名单的地方(设置页、发消息前的 vision 判断)几乎总在启动完成之后,后台那趟
     * 到那时早就跑完了;真赶上了,getter 阻塞到解析完,行为和原来的同步版完全一样——
     * **没有「名单短暂为空」这种中间态**,别把 get() 改成非阻塞的快照。
     */
    @Volatile
    private var loadTask: java.util.concurrent.FutureTask<Pair<List<ProviderInfo>, String>>? = null

    private val loadedPair: Pair<List<ProviderInfo>, String>
        get() = loadTask?.get() ?: (emptyList<ProviderInfo>() to "尚未载入 catalog")

    private val allProviders: List<ProviderInfo> get() = loadedPair.first

    private val json = Json { ignoreUnknownKeys = true }

    fun bootstrap(context: Context) {
        if (loadTask != null) return
        val assets = context.assets
        val task = java.util.concurrent.FutureTask { loadFromAssets(assets) }
        loadTask = task
        Thread(task, "catalog-load").start()
    }

    val diagnostics: String get() = loadedPair.second

    val isLoaded: Boolean get() = providers.isNotEmpty()

    /** 只列 Android 能发请求、且填 key 就能连的托管云端 provider，按显示名排序。 */
    val providers: List<ProviderInfo>
        get() = allProviders
            .filter { it.wireProtocol != null && isHostedCloud(it) }
            .sortedBy { it.displayName.lowercase() }

    private fun isHostedCloud(provider: ProviderInfo): Boolean {
        val api = provider.api ?: return false
        val host = runCatching { URI(api).host?.lowercase() }.getOrNull() ?: return false
        val loopback = setOf("localhost", "127.0.0.1", "::1", "0.0.0.0")
        return host !in loopback && !host.endsWith(".local")
    }

    fun provider(id: String): ProviderInfo? = providers.firstOrNull { it.id == id }

    fun providerName(forId: String): String = provider(forId)?.displayName ?: forId

    /** 该 provider 内置模型；不支持工具调用的不列。保持 catalog 原始顺序。 */
    fun models(forProviderId: String): List<ModelInfo> =
        (provider(forProviderId)?.models ?: emptyList()).filter { it.supportsTools }

    fun model(modelId: String, inProviderId: String): ModelInfo? =
        provider(inProviderId)?.model(modelId)
            ?: models(inProviderId).firstOrNull { it.id == modelId }

    /** 任意 provider 里找同 id（拉列表时补能力用）。 */
    fun modelAnywhere(modelId: String): ModelInfo? {
        for (provider in allProviders) {
            provider.model(modelId)?.let { return it }
        }
        return null
    }

    fun defaultModel(forProviderId: String): String? =
        models(forProviderId).firstOrNull()?.id

    fun modelName(modelId: String, inProviderId: String): String =
        model(modelId, inProviderId)?.displayName ?: modelId

    fun limitSummary(of: ModelInfo): String? {
        val parts = mutableListOf<String>()
        of.contextWindow?.let { parts += "上下文 ${tokenCount(it)}" }
        of.maxOutputTokens?.let { parts += "输出 ${tokenCount(it)}" }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    /**
     * 向服务端要模型列表。catalog 没内置模型、或要看最新可用模型时用。
     * 存在性以服务端为准；能力以 catalog 为准（enrich）。
     */
    fun fetchModels(providerId: String, apiKey: String): List<ModelInfo> {
        val provider = provider(providerId)
            ?: error("未知 provider：$providerId")
        val wire = provider.wireProtocol
            ?: error("该 provider 的协议尚未实现")
        val base = provider.api ?: error("该 provider 没有 API 地址")
        val listed = ModelListClient.fetch(baseUrl = base, apiKey = apiKey, wire = wire)
        return listed.map { bare ->
            provider.model(bare.id)
                ?: modelAnywhere(bare.id)
                ?: bare
        }
    }

    private fun tokenCount(tokens: Int): String = when {
        tokens >= 1_000_000 -> {
            val value = tokens / 1_000_000.0
            if (value == value.toInt().toDouble()) "${value.toInt()}M" else String.format("%.1fM", value)
        }
        tokens >= 1_000 -> "${tokens / 1_000}K"
        else -> "$tokens"
    }

    private fun loadFromAssets(assets: AssetManager): Pair<List<ProviderInfo>, String> {
        val dir = "catalog/providers"
        val names = runCatching { assets.list(dir)?.toList().orEmpty() }.getOrDefault(emptyList())
        if (names.isEmpty()) {
            return emptyList<ProviderInfo>() to "assets/$dir 为空或未打包"
        }
        val loaded = mutableListOf<ProviderInfo>()
        val errors = mutableListOf<String>()
        for (name in names.sorted()) {
            if (!name.endsWith(".json")) continue
            try {
                val text = assets.open("$dir/$name").bufferedReader().use { it.readText() }
                val raw = json.decodeFromString(ProviderJson.serializer(), text)
                loaded += raw.toProvider()
            } catch (error: Throwable) {
                errors += "$name: ${error.message}"
            }
        }
        val diagnostics = buildString {
            append("载入 ${loaded.size} 个 provider")
            if (errors.isNotEmpty()) {
                append("；失败 ${errors.size}：")
                append(errors.take(3).joinToString("；"))
            }
        }
        return loaded to diagnostics
    }

    @Serializable
    private data class ProviderJson(
        val id: String,
        val name: String? = null,
        val api: String? = null,
        val adapter: String? = null,
        val models: List<ModelJson>? = null,
    ) {
        fun toProvider(): ProviderInfo = ProviderInfo(
            id = id,
            name = name,
            api = api,
            adapter = adapter,
            models = models.orEmpty().map { it.toModel() },
        )
    }

    @Serializable
    private data class ModelJson(
        val id: String,
        val name: String? = null,
        @SerialName("tool_call") val toolCall: Boolean? = null,
        val reasoning: ReasoningJson? = null,
        val limit: LimitJson? = null,
        val modalities: ModalitiesJson? = null,
    ) {
        fun toModel(): ModelInfo = ModelInfo(
            id = id,
            name = name,
            toolCall = toolCall,
            reasoningSupported = reasoning?.supported,
            contextWindow = limit?.context,
            maxOutputTokens = limit?.output,
            inputModalities = modalities?.input.orEmpty(),
        )
    }

    @Serializable
    private data class ReasoningJson(val supported: Boolean? = null)

    @Serializable
    private data class LimitJson(
        val context: Int? = null,
        val output: Int? = null,
    )

    @Serializable
    private data class ModalitiesJson(
        val input: List<String>? = null,
        val output: List<String>? = null,
    )
}

/** `GET …/models`，三种协议里 Android 实现了 openai / anthropic 两种响应形。 */
internal object ModelListClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    fun fetch(
        baseUrl: String,
        apiKey: String,
        wire: CloudCatalog.WireProtocol,
    ): List<CloudCatalog.ModelInfo> {
        val url = modelsEndpoint(baseUrl, wire)
        val builder = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/json")
        when (wire) {
            CloudCatalog.WireProtocol.OPENAI -> {
                val key = ApiKeyNormalizer.normalize(apiKey)
                if (key.isValid) builder.header("Authorization", "Bearer ${key.value}")
            }
            CloudCatalog.WireProtocol.ANTHROPIC -> {
                val key = ApiKeyNormalizer.normalize(apiKey)
                if (key.isValid) {
                    builder.header("x-api-key", key.value)
                    builder.header("anthropic-version", "2023-06-01")
                }
            }
            CloudCatalog.WireProtocol.GEMINI -> {
                val key = ApiKeyNormalizer.normalize(apiKey)
                if (key.isValid) builder.header("x-goog-api-key", key.value)
            }
        }
        http.newCall(builder.build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                error("HTTP ${response.code}: ${body.take(200)}")
            }
            return decodeModels(body, wire)
        }
    }

    internal fun modelsEndpoint(baseUrl: String, wire: CloudCatalog.WireProtocol): String {
        val trimmed = baseUrl.trimEnd('/')
        val needsV1 = !trimmed.endsWith("/v1")
        return when (wire) {
            CloudCatalog.WireProtocol.OPENAI,
            CloudCatalog.WireProtocol.ANTHROPIC,
            -> if (needsV1) "$trimmed/v1/models" else "$trimmed/models"
            CloudCatalog.WireProtocol.GEMINI ->
                if (trimmed.endsWith("/v1beta")) "$trimmed/models" else "$trimmed/v1beta/models"
        }
    }

    internal fun decodeModels(
        body: String,
        wire: CloudCatalog.WireProtocol = CloudCatalog.WireProtocol.OPENAI,
    ): List<CloudCatalog.ModelInfo> {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return emptyList()
        val data = when (wire) {
            CloudCatalog.WireProtocol.GEMINI -> root["models"]?.jsonArray
            CloudCatalog.WireProtocol.OPENAI,
            CloudCatalog.WireProtocol.ANTHROPIC,
            -> root["data"]?.jsonArray
        } ?: return emptyList()
        return data.mapNotNull { entry ->
            val obj = entry as? JsonObject ?: return@mapNotNull null
            val rawId = when (wire) {
                CloudCatalog.WireProtocol.GEMINI -> obj["name"]?.jsonPrimitive?.contentOrNull
                CloudCatalog.WireProtocol.OPENAI,
                CloudCatalog.WireProtocol.ANTHROPIC,
                -> obj["id"]?.jsonPrimitive?.contentOrNull
            } ?: return@mapNotNull null
            if (
                wire == CloudCatalog.WireProtocol.GEMINI &&
                obj["supportedGenerationMethods"]?.jsonArray?.none {
                    it.jsonPrimitive.contentOrNull == "generateContent"
                } == true
            ) {
                return@mapNotNull null
            }
            val id = rawId.removePrefix("models/")
            val name = when (wire) {
                CloudCatalog.WireProtocol.GEMINI -> obj["displayName"]?.jsonPrimitive?.contentOrNull
                CloudCatalog.WireProtocol.OPENAI,
                CloudCatalog.WireProtocol.ANTHROPIC,
                -> obj["display_name"]?.jsonPrimitive?.contentOrNull
            }
            CloudCatalog.ModelInfo(id = id, name = name)
        }
    }
}
