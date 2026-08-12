package com.pinapia.vana.checkin

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.pinapia.vana.Features
import com.pinapia.vana.MainActivity
import com.pinapia.vana.VanaApplication
import com.pinapia.vana.health.DayPeriod
import com.pinapia.vana.health.HealthSituation
import com.pinapia.vana.health.HealthTrigger
import com.pinapia.vana.settings.EngineSettings
import com.pinapia.vana.tenant.TenantScope
import java.util.Calendar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

data class CheckInContent(
    val title: String,
    val body: String,
    val topicId: String? = null,
    val question: String? = null,
    val followUpId: String? = null,
)

object CheckInScheduler {
    const val TOPIC_KEY = "topicId"
    const val QUESTION_KEY = "question"
    const val FOLLOW_UP_KEY = "followUpId"
    const val TENANT_KEY = "tenantId"
    const val PERIOD_KEY = "period"

    private const val CHANNEL_ID = "vana_checkin"
    private const val MORNING_ID = 1001
    private const val EVENING_ID = 1002
    private const val MORNING_REQ = 2001
    private const val EVENING_REQ = 2002

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "每日 check-in",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "早晚各一条，基于本机健康数据的简短提醒"
        }
        manager.createNotificationChannel(channel)
    }

    fun reschedule(context: Context) {
        scope.launch {
            val app = context.applicationContext
            val settings = (app as? VanaApplication)?.engineSettings ?: EngineSettings(app)
            cancel(app)
            if (!settings.checkInsEnabled) return@launch
            ensureChannel(app)

            // check-in 讲的是本机健康数据,永远是机主(同 iOS)。HC 暂缓时跳过 situation。
            val situation = if (Features.HEALTH_CONNECT) {
                runCatching {
                    val health = (app as? VanaApplication)?.healthStore ?: return@runCatching null
                    val interests = TenantScope.ownerStores.sessions.interests()
                    health.withOwnerAccess {
                        HealthSituation.detect(health, interests = interests)
                    }
                }.getOrNull()
            } else {
                null
            }
            val dueFollowUps = if (settings.memoryEnabled) {
                TenantScope.ownerStores.memory.snapshot().due()
            } else {
                emptyList()
            }
            val morning = content(
                period = DayPeriod.MORNING,
                situation = situation,
                dueFollowUps = dueFollowUps,
            )
            val evening = content(
                period = DayPeriod.EVENING,
                situation = situation,
                dueFollowUps = emptyList(),
            )
            schedule(app, MORNING_REQ, MORNING_ID, settings.morningCheckInHour, morning, "morning")
            schedule(app, EVENING_REQ, EVENING_ID, settings.eveningCheckInHour, evening, "evening")
        }
    }

    fun cancel(context: Context) {
        val alarm = context.getSystemService(AlarmManager::class.java)
        alarm.cancel(pending(context, MORNING_REQ, Intent()))
        alarm.cancel(pending(context, EVENING_REQ, Intent()))
        NotificationManagerCompat.from(context).cancel(MORNING_ID)
        NotificationManagerCompat.from(context).cancel(EVENING_ID)
    }

    /** Debug:立刻发一条和真 check-in 同路径的通知。 */
    suspend fun sendTest(context: Context): String {
        ensureChannel(context)
        val app = context.applicationContext
        val situation = if (Features.HEALTH_CONNECT) {
            runCatching {
                val health = (app as? VanaApplication)?.healthStore ?: return@runCatching null
                val interests = TenantScope.ownerStores.sessions.interests()
                health.withOwnerAccess {
                    HealthSituation.detect(health, interests = interests)
                }
            }.getOrNull()
        } else {
            null
        }
        val payload = content(
            period = DayPeriod.now(),
            situation = situation,
            dueFollowUps = emptyList(),
        )
        showNotification(
            context = context,
            notificationId = 1099,
            title = "[测试] ${payload.title}",
            body = payload.body,
            topicId = payload.topicId,
            question = payload.question,
            followUpId = payload.followUpId,
            tenantId = TenantScope.owner.id,
        )
        return "已发出测试 check-in：${payload.title}"
    }

    /**
     * 说好要回头看的事排在所有触发点前面。
     * 挑不到时段相关触发点就用一句通用的邀请,不硬编故事。
     */
    suspend fun content(
        period: DayPeriod,
        situation: HealthSituation?,
        dueFollowUps: List<com.pinapia.vana.memory.MemoryItem> = emptyList(),
    ): CheckInContent {
        if (period == DayPeriod.MORNING && dueFollowUps.isNotEmpty()) {
            val followUp = dueFollowUps.first()
            val conclusion = com.pinapia.vana.recall.FollowUpRunner.conclusion(
                forFollowUp = followUp,
                inStore = TenantScope.ownerStores.sessions,
            )
            return CheckInContent(
                title = if (conclusion == null) "说好今天回头看的" else "说好今天回头看的，看过了",
                body = conclusion ?: followUp.text,
                question = followUp.text,
                followUpId = followUp.id,
            )
        }

        val relevant = situation?.triggers?.firstOrNull { trigger ->
            when (period) {
                DayPeriod.MORNING -> when (trigger) {
                    is HealthTrigger.ShortSleep,
                    is HealthTrigger.MissingLastNight,
                    is HealthTrigger.LongSleepStillLow,
                    is HealthTrigger.ElevatedRestingHR,
                    is HealthTrigger.SuppressedHRV,
                    is HealthTrigger.LateBedtimeDrift,
                    is HealthTrigger.WeeklyReview,
                    -> true
                    else -> false
                }
                DayPeriod.AFTERNOON, DayPeriod.EVENING -> when (trigger) {
                    is HealthTrigger.JustTrained,
                    is HealthTrigger.BigActivityDay,
                    is HealthTrigger.SedentaryStreak,
                    is HealthTrigger.NoStepsToday,
                    is HealthTrigger.NoWorkouts,
                    -> true
                    else -> false
                }
            }
        }

        if (relevant != null) {
            return CheckInContent(
                title = if (period == DayPeriod.MORNING) "早上好" else "今天收个尾",
                body = relevant.brief,
                topicId = relevant.topicId,
                question = relevant.question,
            )
        }

        return when (period) {
            DayPeriod.MORNING -> CheckInContent(
                title = "早上好",
                body = "昨晚的睡眠数据已经同步好了，要看看吗？",
                topicId = "sleep",
                question = "昨晚睡得怎么样？",
            )
            DayPeriod.AFTERNOON, DayPeriod.EVENING -> CheckInContent(
                title = "今天收个尾",
                body = "今天的活动量已经记完了，要看看吗？",
                topicId = "activity",
                question = "今天运动量够吗？",
            )
        }
    }

    private fun schedule(
        context: Context,
        requestCode: Int,
        notificationId: Int,
        hour: Int,
        content: CheckInContent,
        period: String,
    ) {
        val intent = Intent(context, CheckInAlarmReceiver::class.java).apply {
            putExtra("notificationId", notificationId)
            putExtra("title", content.title)
            putExtra("body", content.body)
            putExtra(TOPIC_KEY, content.topicId)
            putExtra(QUESTION_KEY, content.question)
            putExtra(FOLLOW_UP_KEY, content.followUpId)
            putExtra(TENANT_KEY, TenantScope.owner.id)
            putExtra(PERIOD_KEY, period)
        }
        val pending = pending(context, requestCode, intent)
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }
        val alarm = context.getSystemService(AlarmManager::class.java)
        alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pending)
    }

    private fun pending(context: Context, requestCode: Int, intent: Intent): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    fun showNotification(
        context: Context,
        notificationId: Int,
        title: String,
        body: String,
        topicId: String?,
        question: String?,
        followUpId: String?,
        tenantId: String?,
    ) {
        ensureChannel(context)
        val open = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(TOPIC_KEY, topicId)
            putExtra(QUESTION_KEY, question)
            putExtra(FOLLOW_UP_KEY, followUpId)
            putExtra(TENANT_KEY, tenantId)
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            notificationId,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(notificationId, notification)
        // 排下一天
        reschedule(context)
    }
}

class CheckInAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        CheckInScheduler.showNotification(
            context = context,
            notificationId = intent.getIntExtra("notificationId", 0),
            title = intent.getStringExtra("title").orEmpty(),
            body = intent.getStringExtra("body").orEmpty(),
            topicId = intent.getStringExtra(CheckInScheduler.TOPIC_KEY),
            question = intent.getStringExtra(CheckInScheduler.QUESTION_KEY),
            followUpId = intent.getStringExtra(CheckInScheduler.FOLLOW_UP_KEY),
            tenantId = intent.getStringExtra(CheckInScheduler.TENANT_KEY),
        )
    }
}

class CheckInBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            CheckInScheduler.reschedule(context)
        }
    }
}
