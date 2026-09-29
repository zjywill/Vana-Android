package com.pinapia.vana.tasks

import com.pinapia.vana.VanaApplication
import com.pinapia.vana.tenant.TenantScope
import com.pinapia.vana.ui.L10n
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.Clock

/** 用户点了「开始」,但还没同意把数据发给当前这家模型服务:先点名征同意,同意了再开始。 */
data class PendingJobConsent(val taskId: String, val providerId: String)

/** [JobControls] 在 Android 上的接线:碰调度器、设置和当前成员的存储。 */
class AppJobControls(private val app: VanaApplication) : JobControls {
    private val _pendingConsent = MutableStateFlow<PendingJobConsent?>(null)
    val pendingConsent: StateFlow<PendingJobConsent?> = _pendingConsent.asStateFlow()

    override val autoStart: Boolean get() = app.engineSettings.autoStartTasks

    override fun start(taskId: String) {
        val tenant = TenantScope.current
        val store = TenantScope.currentStores.tasks
        val task = store.get(taskId)?.takeIf { it.kind == TaskKind.JOB } ?: return
        if (task.status == TaskStatus.QUEUED || task.status == TaskStatus.RUNNING || task.status == TaskStatus.DONE) return

        when (val check = SubagentScheduler.check(app.engineSettings, app.secureKeyStore)) {
            SubagentScheduler.Check.Ready -> Unit
            SubagentScheduler.Check.NotConfigured -> {
                fail(store, taskId, L10n.text("还没配置云端模型，先到设置里填好。", "No cloud model is configured yet — set one up in Settings."))
                return
            }
            is SubagentScheduler.Check.NeedsConsent -> {
                _pendingConsent.value = PendingJobConsent(taskId, check.providerId)
                return
            }
        }
        SubagentLimits.problem(store, Clock.System.now(), ZoneId.systemDefault())?.let {
            fail(store, taskId, it)
            return
        }
        store.update(taskId) { it.copy(status = TaskStatus.QUEUED, error = null) }
        SubagentScheduler.kick(app, tenant)
    }

    /** 他在点名确认的 dialog 上同意了:记下来,把刚才那件开始。 */
    fun confirmConsent() {
        val pending = _pendingConsent.value ?: return
        app.engineSettings.recordProviderConsent(pending.providerId)
        _pendingConsent.value = null
        start(pending.taskId)
    }

    fun declineConsent() {
        _pendingConsent.value = null
    }

    override fun stop(taskId: String) = SubagentScheduler.stop(TenantScope.current, taskId)

    override fun decide(taskId: String, proposalId: String, accept: Boolean) {
        val stores = TenantScope.currentStores
        val env = TasksEnvironment(
            store = stores.tasks,
            scheduling = ReminderScheduler.scheduling(app, TenantScope.current.id),
        )
        val memory = stores.memory.takeIf { app.engineSettings.memoryEnabled }
        TaskActions.decide(env, memory, taskId, proposalId, accept)?.let { problem ->
            stores.tasks.update(taskId) { it.copy(error = L10n.text("有一条没能照做：$problem", "One suggestion couldn't be applied: $problem")) }
        }
    }

    private fun fail(store: TaskStore, taskId: String, reason: String) {
        store.update(taskId) { it.copy(error = reason) }
    }
}
