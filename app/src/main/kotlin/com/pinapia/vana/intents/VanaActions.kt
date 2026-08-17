package com.pinapia.vana.intents

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.pinapia.vana.MainActivity
import com.pinapia.vana.tenant.TenantScope

/**
 * Deep link / App Shortcut 入口。
 *
 * - `vana://action/ask?q=…` → 打开聊天并自动发送
 */
object VanaActions {
    const val SCHEME = "vana"
    const val HOST = "action"
    const val EXTRA_ACTION = "vana_action"
    const val EXTRA_QUESTION = "vana_question"

    const val ACTION_ASK = "ask"

    fun intentFor(context: Context, action: String, question: String? = null): Intent =
        Intent(context, MainActivity::class.java).apply {
            this.action = Intent.ACTION_VIEW
            data = uri(action, question)
            putExtra(EXTRA_ACTION, action)
            if (!question.isNullOrBlank()) putExtra(EXTRA_QUESTION, question)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

    fun uri(action: String, question: String? = null): Uri {
        val builder = Uri.Builder().scheme(SCHEME).authority(HOST).appendPath(action)
        if (!question.isNullOrBlank()) builder.appendQueryParameter("q", question)
        return builder.build()
    }

    fun handle(context: Context, intent: Intent?) {
        val incoming = intent ?: return
        val action = incoming.getStringExtra(EXTRA_ACTION)
            ?: incoming.data?.takeIf { it.scheme == SCHEME && it.host == HOST }?.lastPathSegment
            ?: return
        val question = incoming.getStringExtra(EXTRA_QUESTION)
            ?: incoming.data?.getQueryParameter("q")

        when (action) {
            ACTION_ASK -> {
                if (!question.isNullOrBlank()) {
                    switchToOwnerIfNeeded()
                    VanaLaunchRouter.ask(question)
                }
            }
        }
    }

    private fun switchToOwnerIfNeeded() {
        if (!TenantScope.isOwnerActive) {
            // 快捷方式始终从机主会话开始，避免把问题发到错误的成员档案。
            TenantScope.select(TenantScope.owner)
        }
    }
}
