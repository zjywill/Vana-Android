package com.pinapia.vana.recall

import android.content.Context
import com.pinapia.vana.VanaApplication
import com.pinapia.vana.checkin.CheckInScheduler
import com.pinapia.vana.tenant.TenantScope
import kotlinx.datetime.Clock

/**
 * App 切前后台时替用户跑的后台活：一次只跑一件（到期的待跟进）。
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
        val stores = TenantScope.ownerStores
        val followUp = FollowUpRunner.pending(
            now = now,
            memoryStore = stores.memory,
            writer = stores.threadWriter,
            memoryEnabled = app.engineSettings.memoryEnabled,
        ) ?: return false
        return FollowUpRunner.run(
            followUp = followUp,
            now = now,
            memoryStore = stores.memory,
            writer = stores.threadWriter,
            engineSettings = app.engineSettings,
            secureKeyStore = app.secureKeyStore,
            tenant = TenantScope.owner,
        )
    }
}
