package com.pinapia.vana.settings

/**
 * API 密钥进 HTTP 头之前的规范化。
 *
 * OkHttp 要求 header 值是 ISO-8859-1 可打印字符；中文、表情会在组 `Authorization`
 * 时直接抛 `Unexpected char 0x….`——那不是服务端拒了，是请求根本没发出去。
 */
object ApiKeyNormalizer {
    data class Result(
        val value: String,
        val error: String? = null,
    ) {
        val isValid: Boolean get() = error == null && value.isNotEmpty()
    }

    fun normalize(raw: String?): Result {
        var key = raw.orEmpty().trim()
        if (key.isEmpty()) {
            return Result("", error = "需要先在设置里填写云端 API 密钥")
        }
        // 有人会整段粘贴 `Bearer sk-…`
        if (key.startsWith("Bearer ", ignoreCase = true)) {
            key = key.substring(7).trim()
        }
        // 去掉粘贴带来的空白/换行
        key = key.replace("\uFEFF", "").replace(Regex("\\s+"), "")
        if (key.isEmpty()) {
            return Result("", error = "需要先在设置里填写云端 API 密钥")
        }
        val badAt = key.indexOfFirst { it.code < 0x20 || it.code > 0x7e }
        if (badAt >= 0) {
            return Result(
                value = "",
                error = "API 密钥含有非法字符（位置 $badAt）。请重新粘贴服务商提供的密钥，不要带中文或空格。",
            )
        }
        return Result(value = key)
    }
}
