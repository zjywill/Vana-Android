package com.pinapia.vana.recall

import android.content.Context
import com.pinapia.vana.Features
import com.pinapia.vana.VanaApplication
import com.pinapia.vana.checkin.CheckInScheduler
import com.pinapia.vana.tenant.TenantScope
import kotlinx.datetime.Clock

/**
 * App 切前后台时替用户跑的后台活：一次只跑一件（待跟进优先于目标周报）。
 */
object BackgroundDigest {
    suspend fun runIfDue(context: Context): Boolean {
        val app = context.applicationContext as? VanaApplication ?: return false
        if (!app.engineSettings.checkInsEnabled) return false
        if (!app.engineSettings.isConfigured(app.secureKeyStore)) return false

        val ran = BackgroundModelWork.run {
            runOnce(app)
        } ?: return false

        if (ran) {
            CheckInScheduler.reschedule(app)
        }
        return ran
    }

    private suspend fun runOnce(app: VanaApplication): Boolean {
        val now = Clock.System.now()
        val memoryStore = TenantScope.ownerStores.memory
        val sessionStore = TenantScope.ownerStores.sessions
        val tenant = TenantScope.owner
        val healthTools = if (Features.HEALTH_CONNECT && tenant.isOwner) {
            app.healthStore.let { com.pinapia.vana.health.HealthTools(it) }
        } else {
            null
        }

        val followUp = FollowUpRunner.pending(
            now = now,
            memoryStore = memoryStore,
            sessionStore = sessionStore,
            memoryEnabled = app.engineSettings.memoryEnabled,
        )
        if (followUp != null) {
            return FollowUpRunner.run(
                followUp = followUp,
                now = now,
                memoryStore = memoryStore,
                sessionStore = sessionStore,
                engineSettings = app.engineSettings,
                secureKeyStore = app.secureKeyStore,
                tenant = tenant,
                healthTools = healthTools,
            )
        }

        val goal = GoalDigest.pending(now = now, sessionStore = sessionStore) ?: return false
        return GoalDigest.run(
            goal = goal,
            now = now,
            memoryStore = memoryStore,
            sessionStore = sessionStore,
            engineSettings = app.engineSettings,
            secureKeyStore = app.secureKeyStore,
            tenant = tenant,
            healthTools = healthTools,
        )
    }
}
