package com.pinapia.vana.settings

/**
 * 精选的托管云端 provider。健康工具是刚需,只列支持 tools 的默认模型。
 */
object CloudCatalog {
    enum class WireProtocol {
        OPENAI,
        ANTHROPIC,
    }

    data class ModelInfo(
        val id: String,
        val name: String = id,
        val contextWindow: Int? = null,
        val maxOutputTokens: Int? = null,
        val supportsTools: Boolean = true,
        val supportsReasoning: Boolean = false,
        val supportsVision: Boolean = false,
    )

    data class ProviderInfo(
        val id: String,
        val name: String,
        val apiBaseUrl: String,
        val wireProtocol: WireProtocol,
        val models: List<ModelInfo>,
    )

    val providers: List<ProviderInfo> = listOf(
        ProviderInfo(
            id = "anthropic",
            name = "Anthropic",
            apiBaseUrl = "https://api.anthropic.com",
            wireProtocol = WireProtocol.ANTHROPIC,
            models = listOf(
                ModelInfo("claude-sonnet-4-5", "Claude Sonnet 4.5", 200_000, 64_000, supportsVision = true),
                ModelInfo("claude-opus-4-5", "Claude Opus 4.5", 200_000, 64_000, supportsVision = true),
                ModelInfo("claude-haiku-4-5", "Claude Haiku 4.5", 200_000, 64_000, supportsVision = true),
            ),
        ),
        ProviderInfo(
            id = "openai",
            name = "OpenAI",
            apiBaseUrl = "https://api.openai.com/v1",
            wireProtocol = WireProtocol.OPENAI,
            models = listOf(
                ModelInfo("gpt-4.1", "GPT-4.1", 1_047_576, 32_768, supportsVision = true),
                ModelInfo("gpt-4.1-mini", "GPT-4.1 mini", 1_047_576, 32_768, supportsVision = true),
                ModelInfo("gpt-4o", "GPT-4o", 128_000, 16_384, supportsVision = true),
            ),
        ),
        ProviderInfo(
            id = "deepseek",
            name = "DeepSeek",
            apiBaseUrl = "https://api.deepseek.com",
            wireProtocol = WireProtocol.OPENAI,
            models = listOf(
                ModelInfo("deepseek-chat", "DeepSeek Chat", 128_000, 8_192),
                ModelInfo("deepseek-reasoner", "DeepSeek Reasoner", 128_000, 64_000, supportsReasoning = true),
            ),
        ),
        ProviderInfo(
            id = "moonshot",
            name = "月之暗面 Kimi",
            apiBaseUrl = "https://api.moonshot.cn/v1",
            wireProtocol = WireProtocol.OPENAI,
            models = listOf(
                ModelInfo("kimi-k2-turbo-preview", "Kimi K2 Turbo", 256_000, 16_384),
                ModelInfo("moonshot-v1-128k", "Moonshot v1 128K", 128_000, 8_192),
            ),
        ),
        ProviderInfo(
            id = "dashscope",
            name = "通义千问",
            apiBaseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            wireProtocol = WireProtocol.OPENAI,
            models = listOf(
                ModelInfo("qwen-plus", "Qwen Plus", 131_072, 16_384, supportsReasoning = true),
                ModelInfo("qwen-max", "Qwen Max", 32_768, 8_192, supportsVision = true),
                ModelInfo("qwen-turbo", "Qwen Turbo", 131_072, 8_192),
            ),
        ),
        ProviderInfo(
            id = "zhipuai",
            name = "智谱 GLM",
            apiBaseUrl = "https://open.bigmodel.cn/api/paas/v4",
            wireProtocol = WireProtocol.OPENAI,
            models = listOf(
                ModelInfo("glm-4.5", "GLM-4.5", 128_000, 16_384, supportsReasoning = true),
                ModelInfo("glm-4-plus", "GLM-4 Plus", 128_000, 4_096),
            ),
        ),
        ProviderInfo(
            id = "groq",
            name = "Groq",
            apiBaseUrl = "https://api.groq.com/openai/v1",
            wireProtocol = WireProtocol.OPENAI,
            models = listOf(
                ModelInfo("llama-3.3-70b-versatile", "Llama 3.3 70B", 128_000, 32_768),
                ModelInfo("openai/gpt-oss-120b", "GPT OSS 120B", 128_000, 65_536),
            ),
        ),
        ProviderInfo(
            id = "openrouter",
            name = "OpenRouter",
            apiBaseUrl = "https://openrouter.ai/api/v1",
            wireProtocol = WireProtocol.OPENAI,
            models = listOf(
                ModelInfo("anthropic/claude-sonnet-4.5", "Claude Sonnet 4.5", 200_000, 64_000, supportsVision = true),
                ModelInfo("openai/gpt-4.1", "GPT-4.1", 1_047_576, 32_768, supportsVision = true),
                ModelInfo("deepseek/deepseek-chat", "DeepSeek Chat", 128_000, 8_192),
            ),
        ),
        ProviderInfo(
            id = "siliconflow",
            name = "硅基流动",
            apiBaseUrl = "https://api.siliconflow.cn/v1",
            wireProtocol = WireProtocol.OPENAI,
            models = listOf(
                ModelInfo("deepseek-ai/DeepSeek-V3", "DeepSeek V3", 64_000, 8_192),
                ModelInfo("Qwen/Qwen2.5-72B-Instruct", "Qwen2.5 72B", 32_768, 8_192),
            ),
        ),
        ProviderInfo(
            id = "minimax",
            name = "MiniMax",
            apiBaseUrl = "https://api.minimaxi.com/anthropic",
            wireProtocol = WireProtocol.ANTHROPIC,
            models = listOf(
                ModelInfo("MiniMax-M2.5", "MiniMax M2.5", 200_000, 16_384),
            ),
        ),
    ).sortedBy { it.name.lowercase() }

    fun provider(id: String): ProviderInfo? = providers.firstOrNull { it.id == id }

    fun providerName(forId: String): String = provider(forId)?.name ?: forId

    fun models(forProviderId: String): List<ModelInfo> =
        provider(forProviderId)?.models?.filter { it.supportsTools }.orEmpty()

    fun model(modelId: String, inProviderId: String): ModelInfo? =
        models(inProviderId).firstOrNull { it.id == modelId }

    fun defaultModel(forProviderId: String): String? =
        models(forProviderId).firstOrNull()?.id

    fun modelName(modelId: String, inProviderId: String): String =
        model(modelId, inProviderId)?.name ?: modelId

    fun limitSummary(of: ModelInfo): String? {
        val parts = mutableListOf<String>()
        of.contextWindow?.let { parts += "上下文 ${tokenCount(it)}" }
        of.maxOutputTokens?.let { parts += "输出 ${tokenCount(it)}" }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    private fun tokenCount(tokens: Int): String = when {
        tokens >= 1_000_000 -> {
            val value = tokens / 1_000_000.0
            if (value == value.toInt().toDouble()) "${value.toInt()}M" else String.format("%.1fM", value)
        }
        tokens >= 1_000 -> "${tokens / 1_000}K"
        else -> "$tokens"
    }
}
