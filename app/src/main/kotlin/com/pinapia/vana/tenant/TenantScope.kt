package com.pinapia.vana.tenant

import com.pinapia.vana.medications.MedicationStore
import com.pinapia.vana.measurements.MeasurementStore
import com.pinapia.vana.memory.MemoryStore
import com.pinapia.vana.session.SessionStore
import com.pinapia.vana.vision.AttachmentStore
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class TenantStores(
    val sessions: SessionStore,
    val memory: MemoryStore,
    val medications: MedicationStore,
    val measurements: MeasurementStore,
    val attachments: AttachmentStore,
) {
    constructor(root: File) : this(
        sessions = SessionStore(parent = root),
        memory = MemoryStore(directory = root),
        medications = MedicationStore(directory = root),
        measurements = MeasurementStore(directory = root),
        attachments = AttachmentStore(parent = root),
    )
}

/**
 * 「此刻是谁」。全局、同步。
 *
 * 冷启动永远回到机主。
 */
object TenantScope {
    private data class State(
        var owner: Tenant,
        var current: Tenant,
        var isolationAvailable: Boolean,
        val parent: File,
    )

    @Volatile
    private var state: State? = null
    private val bundles = ConcurrentHashMap<String, TenantStores>()

    private val legacyOwnerId = UUID.fromString("00000000-0000-0000-0000-000000000001").toString()

    @Synchronized
    fun bootstrap(parent: File, store: TenantStore = TenantStore(parent)): Boolean {
        return try {
            val owner = store.owner()
            state = State(
                owner = owner,
                current = owner,
                isolationAvailable = true,
                parent = parent,
            )
            true
        } catch (error: Throwable) {
            val owner = Tenant.owner(id = legacyOwnerId)
            state = State(
                owner = owner,
                current = owner,
                isolationAvailable = false,
                parent = parent,
            )
            false
        }
    }

    private fun resolved(): State {
        state?.let { return it }
        val owner = Tenant.owner(id = legacyOwnerId)
        val fallback = State(
            owner = owner,
            current = owner,
            isolationAvailable = false,
            parent = File(System.getProperty("java.io.tmpdir"), "vana-fallback"),
        )
        state = fallback
        return fallback
    }

    val current: Tenant get() = resolved().current
    val owner: Tenant get() = resolved().owner
    val isOwnerActive: Boolean get() = resolved().current.isOwner
    val isolationAvailable: Boolean get() = resolved().isolationAvailable

    fun select(tenant: Tenant) {
        resolved().current = tenant
    }

    fun refresh(tenant: Tenant) {
        val s = resolved()
        if (s.current.id == tenant.id) s.current = tenant
        if (s.owner.id == tenant.id) s.owner = tenant
    }

    fun fallBackToOwnerIfNeeded(removedId: String) {
        val s = resolved()
        if (s.current.id == removedId) {
            s.current = s.owner
        }
        bundles.remove(removedId)
    }

    fun stores(forTenant: Tenant): TenantStores {
        bundles[forTenant.id]?.let { return it }
        val made = TenantStores(root = root(forTenant))
        return bundles.putIfAbsent(forTenant.id, made) ?: made
    }

    val currentStores: TenantStores get() = stores(current)
    val ownerStores: TenantStores get() = stores(owner)

    private fun root(forTenant: Tenant): File {
        val s = resolved()
        if (!s.isolationAvailable) return s.parent
        return TenantPaths.root(forTenant.id, s.parent)
    }
}
