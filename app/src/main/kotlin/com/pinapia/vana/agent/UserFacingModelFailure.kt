package com.pinapia.vana.agent

import com.pinapia.vana.agentruntime.ModelFailure

object UserFacingModelFailure {
    fun message(error: Throwable): String {
        val raw = error.message ?: "未知错误"
        return message(raw)
    }

    fun message(raw: String): String = when (ModelFailure.kind(raw)) {
        ModelFailure.Kind.AUTHENTICATION ->
            "API 密钥没通过验证。请到「设置 › 云端模型」确认密钥没有填错或过期，" +
                "并且和选中的 Provider 对得上。"
        ModelFailure.Kind.QUOTA ->
            "这把 API 密钥的额度用完了，或者账户欠费。请到 Provider 那边确认额度后再试。"
        ModelFailure.Kind.CONTEXT_OVERFLOW ->
            "这条对话太长，已经超出模型的上下文限制。请开一条新对话再问一次。"
        ModelFailure.Kind.TRANSIENT ->
            "网络或模型服务暂时不可用，重试几次都没有成功。请过一会儿再试。"
        ModelFailure.Kind.OTHER -> raw
    }
}
