package com.pinapia.vana.tasks

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.pinapia.vana.MainActivity
import com.pinapia.vana.VanaApplication
import com.pinapia.vana.recall.BackgroundModelWork
import com.pinapia.vana.recall.BackgroundTurn
import com.pinapia.vana.search.WebFetchClient
import com.pinapia.vana.search.WebSearchClient
import com.pinapia.vana.session.ChatMessage
import com.pinapia.vana.settings.ApiKeyNormalizer
import com.pinapia.vana.settings.EngineSettings
import com.pinapia.vana.settings.SecureKeyStore
import com.pinapia.vana.tenant.Tenant
import com.pinapia.vana.tenant.TenantScope
import com.pinapia.vana.ui.L10n
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.datetime.Clock

/**
 * 后台任务的排队和运行。
 *
 * - **一次只跑一件**:和「后台的模型调用同时只准跑一件」是同一把锁([BackgroundModelWork]),
 *   补的是同一个失灵(并行烧钱)。排在后面的等着,不丢。
 * - **进程活着才跑**。App 被系统杀掉时正在跑的那件会停;下次打开时 [resume] 把它接着排回去(最多再跑一次),
 *   再撞上就记为失败、由用户决定要不要重试。没有前台服务、没有 WorkManager——要不要上,看真有没有人
 *   总在任务跑到一半时切走 app,而不是先假设。
 * - **每一步先过闸**:没配模型、没点过同意,都不开跑(记成失败并说原因),不会静悄悄地发出去。
 */
object SubagentScheduler {
    sealed interface Check {
        data object Ready : Check
        data object NotConfigured : Check
        data class NeedsConsent(val providerId: String) : Check
    }

    private const val CHANNEL_ID = "vana_tasks"
    private const val BASE_NOTIFICATION_ID = 40_000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val running = ConcurrentHashMap<String, Job>()
    private val drainLock = Mutex()

    fun check(settings: EngineSettings, keys: SecureKeyStore): Check {
        if (!ApiKeyNormalizer.normalize(keys.apiKey).isValid || settings.model.isBlank()) return Check.NotConfigured
        val provider = settings.providerId.ifBlank { EngineSettings.DEFAULT_PROVIDER }
        return if (settings.hasProviderConsent(provider)) Check.Ready else Check.NeedsConsent(provider)
    }

    fun isRunning(taskId: String): Boolean = running[taskId]?.isActive == true

    /** 队里有等着的就开始排干。多次调用没关系:同一时刻只有一个在排。 */
    fun kick(app: VanaApplication, tenant: Tenant) {
        scope.launch {
            while (true) {
                if (!drainLock.tryLock()) return@launch
                try {
                    val store = TenantScope.stores(tenant).tasks
                    while (true) {
                        val next = store.byKind(TaskKind.JOB).filter { it.status == TaskStatus.QUEUED }.minByOrNull { it.updatedAt }
                            ?: break
                        BackgroundModelWork.runExclusive { runOne(app, tenant, next) }
                    }
                } finally {
                    drainLock.unlock()
                }
                // 解锁之前刚好进来的新任务,不能丢。
                val more = TenantScope.stores(tenant).tasks.byKind(TaskKind.JOB).any { it.status == TaskStatus.QUEUED }
                if (!more) return@launch
            }
        }
    }

    fun stop(tenant: Tenant, taskId: String) {
        val store = TenantScope.stores(tenant).tasks
        store.update(taskId) { if (it.isActive) it.copy(status = TaskStatus.CANCELLED, error = null) else it }
        running[taskId]?.cancel()
    }

    /**
     * 打开 app 时:把被打断的任务接着排回去(最多 [SubagentLimits.MAX_ATTEMPTS] 次),
     * 再看有没有到点该回顾的目标。
     */
    fun resume(app: VanaApplication) {
        scope.launch {
            val tenants = app.tenantStore.all().ifEmpty { listOf(TenantScope.owner) }
            for (tenant in tenants) {
                val stores = TenantScope.stores(tenant)
                for (job in stores.tasks.byKind(TaskKind.JOB)) {
                    if (job.status != TaskStatus.RUNNING || isRunning(job.id)) continue
                    stores.tasks.update(job.id) {
                        if (it.attempts < SubagentLimits.MAX_ATTEMPTS) {
                            it.copy(status = TaskStatus.QUEUED)
                        } else {
                            it.copy(status = TaskStatus.FAILED, error = L10n.text("中途被系统打断了，可以再试一次。", "It was interrupted by the system — you can try again."))
                        }
                    }
                }
                if (check(app.engineSettings, app.secureKeyStore) == Check.Ready) {
                    GoalDigest.enqueueDue(stores.tasks, Clock.System.now(), stores.threadWriter)
                }
                if (stores.tasks.byKind(TaskKind.JOB).any { it.status == TaskStatus.QUEUED }) kick(app, tenant)
            }
        }
    }

    private suspend fun runOne(app: VanaApplication, tenant: Tenant, task: Task) {
        val stores = TenantScope.stores(tenant)
        val settings = app.engineSettings

        when (val check = check(settings, app.secureKeyStore)) {
            Check.Ready -> Unit
            Check.NotConfigured, is Check.NeedsConsent -> {
                val reason = if (check == Check.NotConfigured) {
                    L10n.text("还没配置云端模型，先到设置里填好。", "No cloud model is configured yet — set one up in Settings.")
                } else {
                    L10n.text("还没同意把数据发给当前的模型服务。", "You haven't agreed to send data to the current model service yet.")
                }
                stores.tasks.update(task.id) { it.copy(status = TaskStatus.FAILED, error = reason) }
                return
            }
        }

        val provider = settings.providerId.ifBlank { EngineSettings.DEFAULT_PROVIDER }
        val model = settings.model.trim()
        val key = ApiKeyNormalizer.normalize(app.secureKeyStore.apiKey).value
        val runner = SubagentRunner(stores.tasks, engineFor = { collector ->
            val environment = BackgroundTurn.backgroundEnvironment(
                now = Clock.System.now(),
                memoryStore = stores.memory,
                writer = stores.threadWriter,
                engineSettings = settings,
                tenant = tenant,
                webSearch = WebSearchClient.storedKey(app.secureKeyStore.serperApiKey),
                webFetch = WebFetchClient.direct(),
            )
            BackgroundTurn.engine(
                provider = provider,
                model = model,
                apiKey = key,
                environment = environment,
                extraPlugins = listOf(SubagentPlugin(collector)),
                maxToolRounds = SubagentLimits.MAX_TOOL_ROUNDS,
            )
        })

        var outcome: SubagentRunner.Outcome = SubagentRunner.Outcome.Skipped
        val job = scope.launch { outcome = runner.run(task.id) }
        running[task.id] = job
        try {
            job.join()
        } finally {
            running.remove(task.id)
        }

        when (val result = outcome) {
            is SubagentRunner.Outcome.Done -> announce(app, tenant, result.task, ok = true)
            is SubagentRunner.Outcome.Failed -> announce(app, tenant, result.task, ok = false)
            SubagentRunner.Outcome.Skipped -> Unit
        }
    }

    /** 做完(或没做成)了:一条主动消息落进对话末尾,再发一条通知。 */
    private suspend fun announce(app: VanaApplication, tenant: Tenant, task: Task, ok: Boolean) {
        val text = if (ok) {
            L10n.text("「${task.title}」做完了：${task.result?.summary.orEmpty()}", "“${task.title}” is done: ${task.result?.summary.orEmpty()}")
        } else {
            L10n.text("「${task.title}」没做成：${task.error.orEmpty()}", "“${task.title}” didn't work out: ${task.error.orEmpty()}")
        }
        TenantScope.stores(tenant).threadWriter.postProactive(
            ChatMessage(role = ChatMessage.Role.ASSISTANT, text = text, origin = ChatMessage.Origin.TASK, refTaskId = task.id),
        )
        notify(app, task, text)
    }

    private fun notify(context: Context, task: Task, text: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, L10n.text(context, "后台任务", "Background tasks"), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = L10n.text(context, "后台任务做完或没做成时的通知", "Notifications when a background task finishes or fails")
            },
        )
        val open = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val notificationId = BASE_NOTIFICATION_ID + (task.id.hashCode() and 0xFFFF)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(L10n.text(context, "后台任务", "Background task"))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(
                PendingIntent.getActivity(context, notificationId, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
            )
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(notificationId, notification) }
    }
}
