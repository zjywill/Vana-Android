package com.pinapia.vana.agent

import com.pinapia.vana.agentruntime.ModelFailure
import com.pinapia.vana.ui.L10n
import java.util.Locale

object UserFacingModelFailure {
    val authenticationMessage: String
        get() = L10n.text(
            "API 密钥没通过验证。请到「设置 › 云端模型」确认密钥没有填错或过期，并且和选中的 Provider 对得上。",
            "The API key was rejected. Open Settings > Cloud model and confirm that the key is correct, active, and matches the selected provider.",
        )

    fun message(error: Throwable): String {
        val raw = error.message ?: L10n.text("未知错误", "Unknown error")
        return message(raw)
    }

    fun message(raw: String): String = when (ModelFailure.kind(raw)) {
        ModelFailure.Kind.AUTHENTICATION ->
            authenticationMessage
        ModelFailure.Kind.QUOTA ->
            L10n.text(
                "这把 API 密钥的额度用完了，或者账户欠费。请到 Provider 那边确认额度后再试。",
                "This API key has no remaining quota, or the account has a billing problem. Check the provider account and try again.",
            )
        ModelFailure.Kind.CONTEXT_OVERFLOW ->
            L10n.text(
                "这一轮内容太多，超出了模型一次能看完的范围。请把这条消息缩短一些，或者少附几张图再试。",
                "This turn is too large for the model to take in at once. Shorten the message or attach fewer photos and try again.",
            )
        ModelFailure.Kind.TRANSIENT ->
            L10n.text(
                "网络或模型服务暂时不可用，重试几次都没有成功。请过一会儿再试。",
                "The network or model service is temporarily unavailable after several retries. Try again later.",
            )
        ModelFailure.Kind.OTHER -> raw
    }

    fun isAuthenticationMessage(message: String): Boolean =
        message == L10n.text(
            "API 密钥没通过验证。请到「设置 › 云端模型」确认密钥没有填错或过期，并且和选中的 Provider 对得上。",
            "The API key was rejected. Open Settings > Cloud model and confirm that the key is correct, active, and matches the selected provider.",
            Locale.SIMPLIFIED_CHINESE,
        ) ||
            message == L10n.text(
                "API 密钥没通过验证。请到「设置 › 云端模型」确认密钥没有填错或过期，并且和选中的 Provider 对得上。",
                "The API key was rejected. Open Settings > Cloud model and confirm that the key is correct, active, and matches the selected provider.",
                Locale.ENGLISH,
            )
}
