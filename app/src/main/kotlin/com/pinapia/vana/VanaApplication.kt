package com.pinapia.vana

import android.app.Application
import com.pinapia.vana.location.LocationProvider
import com.pinapia.vana.settings.CloudCatalog
import com.pinapia.vana.settings.EngineSettings
import com.pinapia.vana.settings.SecureKeyStore
import com.pinapia.vana.tenant.TenantScope
import com.pinapia.vana.tenant.TenantStore

/**
 * 进程入口。名单必须在任何视图建起来之前就位。
 */
class VanaApplication : Application() {
    lateinit var engineSettings: EngineSettings
        private set
    lateinit var secureKeyStore: SecureKeyStore
        private set
    lateinit var locationProvider: LocationProvider
        private set
    lateinit var tenantStore: TenantStore
        private set

    override fun onCreate() {
        super.onCreate()
        engineSettings = EngineSettings(this)
        secureKeyStore = SecureKeyStore(this)
        locationProvider = LocationProvider(this)
        tenantStore = TenantStore(filesDir)
        TenantScope.bootstrap(parent = filesDir, store = tenantStore)
        CloudCatalog.bootstrap(this)
    }
}
