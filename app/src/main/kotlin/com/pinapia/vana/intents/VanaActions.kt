package com.pinapia.vana.intents

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.pinapia.vana.MainActivity
import com.pinapia.vana.VanaApplication
import com.pinapia.vana.tenant.TenantScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Deep link / App Shortcut 入口。
 *
 * - `vana://action/today` / `sleep` / `workout` → 本地 SpokenBrief
 * - `vana://action/ask?q=…` → 打开聊天并自动发送
 */
object VanaActions {
    const val SCHEME = "vana"
    const val HOST = "action"
    const val EXTRA_ACTION = "vana_action"
    const val EXTRA_QUESTION = "vana_question"

    const val ACTION_TODAY = "today"
    const val ACTION_SLEEP = "sleep"
    const val ACTION_WORKOUT = "workout"
    const val ACTION_ASK = "ask"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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
            ACTION_TODAY, ACTION_SLEEP, ACTION_WORKOUT -> {
                val app = context.applicationContext as? VanaApplication ?: return
                scope.launch {
                    switchToOwnerIfNeeded()
                    val line = when (action) {
                        ACTION_TODAY -> SpokenBrief.todayStatus(app.healthStore)
                        ACTION_SLEEP -> SpokenBrief.lastNightSleep(app.healthStore)
                        else -> SpokenBrief.lastWorkout(app.healthStore)
                    }
                    VanaLaunchRouter.brief(line)
                }
            }
        }
    }

    private fun switchToOwnerIfNeeded() {
        if (!TenantScope.isOwnerActive) {
            // 快捷方式问的是机主自己的数据;停在家人栏时要切回去。
            TenantScope.select(TenantScope.owner)
        }
    }
}
