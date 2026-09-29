package com.pinapia.vana.tasks

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.pinapia.vana.MainActivity
import com.pinapia.vana.VanaApplication
import com.pinapia.vana.session.ChatMessage
import com.pinapia.vana.tenant.Tenant
import com.pinapia.vana.tenant.TenantScope
import com.pinapia.vana.ui.L10n
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.minutes
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * 提醒落到系统闹钟上。
 *
 * **到点不调模型**:响的时候只做两件事——发一条本地通知,并往那条对话末尾追加一条 Vana 主动说的话
 * ([ChatMessage.Origin.REMINDER],模型下次看得到)。所以提醒不花用户一分钱。
 *
 * 用**非精确**闹钟(`setAndAllowWhileIdle`),不申请 `SCHEDULE_EXACT_ALARM`:Android 14 起新装默认拒绝、
 * Play 对 `USE_EXACT_ALARM` 还有类别限制。代价是可能晚几分钟——工具的回答和界面文案都照实这么写。
 * 系统重启或 app 重装之后闹钟没了,靠 [rescheduleAll] 从每个成员的 `tasks.json` 重新排。
 */
object ReminderScheduler {
    const val TASK_KEY = "taskId"
    const val TENANT_KEY = "tenantId"
    private const val CHANNEL_ID = "vana_reminders"
    private const val BASE_NOTIFICATION_ID = 30_000
    private val MISSED_AFTER = 5.minutes
    private val EARLY_SLACK = 2.minutes

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun scheduling(context: Context, tenantId: String): ReminderScheduling = object : ReminderScheduling {
        override fun schedule(task: Task) = schedule(context, tenantId, task)
        override fun cancel(taskId: String) = cancel(context, taskId)
    }

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                L10n.text(context, "提醒", "Reminders"),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = L10n.text(context, "你让 Vana 设的提醒", "Reminders you asked Vana to set")
            },
        )
    }

    private fun pendingIntent(context: Context, taskId: String, tenantId: String?): PendingIntent {
        val intent = Intent(context, ReminderAlarmReceiver::class.java).apply {
            putExtra(TASK_KEY, taskId)
            putExtra(TENANT_KEY, tenantId)
        }
        return PendingIntent.getBroadcast(
            context,
            taskId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun schedule(context: Context, tenantId: String, task: Task, now: Instant = Clock.System.now()) {
        if (task.kind != TaskKind.REMINDER || !task.isActive) return
        val due = ReminderRules.scheduledFor(task, now, ZoneId.systemDefault()) ?: return
        context.getSystemService(AlarmManager::class.java)
            .setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, due.toEpochMilliseconds(), pendingIntent(context, task.id, tenantId))
    }

    fun cancel(context: Context, taskId: String) {
        context.getSystemService(AlarmManager::class.java).cancel(pendingIntent(context, taskId, null))
        NotificationManagerCompat.from(context).cancel(notificationId(taskId))
    }

    private fun tenants(context: Context): List<Tenant> =
        (context.applicationContext as? VanaApplication)?.tenantStore?.all()
            ?.takeIf { it.isNotEmpty() } ?: listOf(TenantScope.owner)

    /**
     * 启动、开机、换 provider 之后都可能需要:按每个成员盘上的提醒重新排一遍。
     * 关机期间已经过点、又没响过的(不重复的)补响一次——错过一条提醒比迟到一条糟得多。
     */
    fun rescheduleAll(context: Context) {
        val app = context.applicationContext
        scope.launch {
            val now = Clock.System.now()
            for (tenant in tenants(app)) {
                val store = TenantScope.stores(tenant).tasks
                for (task in store.active().filter { it.kind == TaskKind.REMINDER }) {
                    val due = task.dueAt ?: continue
                    when {
                        // 过点很久了(关机、被系统杀了):补响一次,标明是错过的。
                        due < now - MISSED_AFTER -> fire(app, tenant, task.id, missed = true)
                        due <= now -> fire(app, tenant, task.id)
                        else -> schedule(app, tenant.id, task, now)
                    }
                }
            }
        }
    }

    /** 到点了(或补响):通知加一条主动消息;重复的排下一次,不重复的记为完成。 */
    suspend fun fire(context: Context, tenant: Tenant, taskId: String, missed: Boolean = false) {
        val stores = TenantScope.stores(tenant)
        val task = stores.tasks.get(taskId) ?: return
        if (task.kind != TaskKind.REMINDER || !task.isActive) return
        val now = Clock.System.now()
        // 重复的提醒响过之后已经挪到下一次;这时候再来一个迟到的旧闹钟,不能提前响。
        val due = task.dueAt
        if (!missed && due != null && due > now + EARLY_SLACK) {
            schedule(context, tenant.id, task, now)
            return
        }

        val text = if (missed) {
            L10n.text(context, "错过的提醒：${task.title}", "Missed reminder: ${task.title}")
        } else {
            L10n.text(context, "提醒：${task.title}", "Reminder: ${task.title}")
        }
        stores.threadWriter.postProactive(
            ChatMessage(role = ChatMessage.Role.ASSISTANT, text = text, origin = ChatMessage.Origin.REMINDER),
        )
        notify(context, task, text)

        val advanced = ReminderRules.afterFiring(task, now, ZoneId.systemDefault())
        val saved = stores.tasks.update(task.id) { advanced }
        if (saved != null && saved.isActive) schedule(context, tenant.id, saved, now)
    }

    private fun notificationId(taskId: String) = BASE_NOTIFICATION_ID + (taskId.hashCode() and 0xFFFF)

    private fun notify(context: Context, task: Task, text: String) {
        ensureChannel(context)
        val open = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val content = PendingIntent.getActivity(
            context,
            notificationId(task.id),
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(L10n.text(context, "提醒", "Reminder"))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(content)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(notificationId(task.id), notification) }
    }
}

class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getStringExtra(ReminderScheduler.TASK_KEY) ?: return
        val tenantId = intent.getStringExtra(ReminderScheduler.TENANT_KEY)
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                val tenant = (app as? VanaApplication)?.tenantStore?.all()?.firstOrNull { it.id == tenantId }
                    ?: TenantScope.owner
                ReminderScheduler.fire(app, tenant, taskId)
            } finally {
                pending.finish()
            }
        }
    }
}
